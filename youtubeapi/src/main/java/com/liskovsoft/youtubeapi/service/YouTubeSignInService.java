package com.liskovsoft.youtubeapi.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.SignInService;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase;
import com.liskovsoft.youtubeapi.auth.V2.AuthService;
import com.liskovsoft.googlecommon.common.models.auth.AccessToken;
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.youtubeapi.service.internal.YouTubeAccountManager;
import io.reactivex.rxjava3.core.Observable;

import java.util.List;
import java.util.Map;

public class YouTubeSignInService implements SignInService {
    private static final String TAG = YouTubeSignInService.class.getSimpleName();
    private static final long TOKEN_REFRESH_PERIOD_MS = 60 * 60 * 1_000; // NOTE: auth token max lifetime is 60 min
    private static final String AUTH_CACHE_DELIM = "|-|";
    private static YouTubeSignInService sInstance;
    private final YouTubeAccountManager mAccountManager;
    private String mCachedAuthorizationHeader;
    private long mCacheUpdateTime;
    private AuthTokenCache mAuthTokenCache;

    /**
     * Own tiny pref file for the short-lived access header. The refresh token is already
     * persisted in the account store, so keeping the (shorter-lived) access header at rest
     * adds no new secret class — and saves a full OAuth round trip on every process start
     * while the token is still inside its lifetime.
     */
    private static class AuthTokenCache extends SharedPreferencesBase {
        private static final String PREF_NAME = AuthTokenCache.class.getSimpleName();
        private static final String AUTH_TOKEN_CACHE = "auth_token_cache";

        public AuthTokenCache(Context context) {
            super(context, PREF_NAME, true);
        }

        public String getData() {
            return getString(AUTH_TOKEN_CACHE, null);
        }

        public void setData(String data) {
            putString(AUTH_TOKEN_CACHE, data);
        }
    }

    private YouTubeSignInService() {
        mAccountManager = YouTubeAccountManager.instance(this);

        GlobalPreferences.setOnInit(() -> {
            mAccountManager.init();
            try {
                updateAuthHeadersIfNeeded();
            } catch (Exception e) {
                // Host not found
                e.printStackTrace();
            }
        });
    }

    public static YouTubeSignInService instance() {
        if (sInstance == null) {
            sInstance = new YouTubeSignInService();
        }

        return sInstance;
    }

    @Override
    public Observable<String> signInObserve() {
        return mAccountManager.signInObserve();
    }

    public void checkAuth() {
        updateAuthHeadersIfNeeded();
    }

    private synchronized void updateAuthHeadersIfNeeded() {
        if (mCachedAuthorizationHeader != null && Helpers.equals(mCachedAuthorizationHeader, RetrofitOkHttpHelper.getAuthHeaders().get("Authorization"))
                && System.currentTimeMillis() - mCacheUpdateTime < TOKEN_REFRESH_PERIOD_MS) {
            return;
        }

        if (restorePersistedAuthHeader()) {
            // The persisted header is still inside its lifetime — the feed can fire now.
            startStorageSyncAsync();
            return;
        }

        updateAuthHeaders();

        mCacheUpdateTime = System.currentTimeMillis();

        persistAuthHeader();
    }

    private void updateAuthHeaders() {
        Account account = mAccountManager.getSelectedAccount();
        String refreshToken = account != null ? ((YouTubeAccount) account).getRefreshToken() : null;
        // get or create authorization on fly
        mCachedAuthorizationHeader = createAuthorizationHeader(refreshToken);
        syncWithRetrofit();
        // Avatar/name/email sync is drawer cosmetics — it must not keep the auth monitor held
        // (the first feed's checkAuth blocks on it) for its own accounts_list round trip.
        startStorageSyncAsync();
    }

    private void startStorageSyncAsync() {
        new Thread(() -> {
            try {
                mAccountManager.syncStorage();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "AccountStorageSync").start();
    }

    /**
     * Adopt the persisted access header when it belongs to the CURRENT account's refresh token
     * and is still inside {@link #TOKEN_REFRESH_PERIOD_MS}. A revoked-early token is handled by
     * the transport's one-shot 401 retry through {@link #refreshRejectedAuthHeader}.
     */
    private boolean restorePersistedAuthHeader() {
        AuthTokenCache cache = getAuthTokenCache();
        if (cache == null) {
            return false;
        }

        String data = cache.getData();
        if (data == null) {
            return false;
        }

        String[] parts = Helpers.split(data, AUTH_CACHE_DELIM);
        if (parts == null || parts.length != 3) {
            return false;
        }

        Account account = mAccountManager.getSelectedAccount();
        String refreshToken = account != null ? ((YouTubeAccount) account).getRefreshToken() : null;
        if (refreshToken == null || !Helpers.equals(refreshToken, parts[2])) {
            return false;
        }

        long persistedTime = Helpers.parseLong(parts, 1, -1);
        long age = System.currentTimeMillis() - persistedTime;
        if (persistedTime <= 0 || age < 0 || age >= TOKEN_REFRESH_PERIOD_MS) {
            return false;
        }

        mCachedAuthorizationHeader = parts[0];
        mCacheUpdateTime = persistedTime;
        syncWithRetrofit();
        Log.d(TAG, "Restored persisted authorization header (age " + (age / 60_000) + " min)");
        return true;
    }

    private void persistAuthHeader() {
        AuthTokenCache cache = getAuthTokenCache();
        if (cache == null) {
            return;
        }

        Account account = mAccountManager.getSelectedAccount();
        String refreshToken = account != null ? ((YouTubeAccount) account).getRefreshToken() : null;
        if (mCachedAuthorizationHeader == null || refreshToken == null) {
            cache.setData(null);
            return;
        }

        cache.setData(Helpers.join(AUTH_CACHE_DELIM,
                mCachedAuthorizationHeader, String.valueOf(mCacheUpdateTime), refreshToken));
    }

    private AuthTokenCache getAuthTokenCache() {
        if (Helpers.isJUnitTest() || GlobalPreferences.sInstance == null) {
            return null;
        }

        if (mAuthTokenCache == null) {
            mAuthTokenCache = new AuthTokenCache(GlobalPreferences.sInstance.getContext());
        }

        return mAuthTokenCache;
    }

    /**
     * Transport callback: an authed API call got a 401, i.e. the access token died before its
     * 60-min window ended (server-side revoke). Drop it (memory + disk) and mint a fresh one.
     *
     * @return true when the caller now has a DIFFERENT header to retry with
     */
    public synchronized boolean refreshRejectedAuthHeader(String rejectedHeader) {
        if (rejectedHeader == null) {
            return false;
        }

        if (Helpers.equals(mCachedAuthorizationHeader, rejectedHeader)) {
            invalidateCache();
        }

        updateAuthHeadersIfNeeded();

        return mCachedAuthorizationHeader != null && !Helpers.equals(mCachedAuthorizationHeader, rejectedHeader);
    }

    @Override
    public boolean isSigned() {
        // Condition created for the case when a device in offline mode.
        return mAccountManager.getSelectedAccount() != null;
    }

    @Override
    public List<Account> getAccounts() {
        return mAccountManager.getAccounts();
    }

    @Nullable
    @Override
    public Account getSelectedAccount() {
        return mAccountManager.getSelectedAccount();
    }

    public void invalidateCache() {
        mCachedAuthorizationHeader = null;
        mCacheUpdateTime = 0;

        // The persisted copy must die with the in-memory one (account switch, 401 revoke) —
        // otherwise restorePersistedAuthHeader() resurrects the invalidated header.
        AuthTokenCache cache = getAuthTokenCache();
        if (cache != null) {
            cache.setData(null);
        }
    }

    // Fix empty content when quickly switch accounts???
    @Override
    public synchronized void selectAccount(Account account) {
        mAccountManager.selectAccount(account);
    }

    @Override
    public synchronized void removeAccount(Account account) {
        mAccountManager.removeAccount(account);
    }

    @Override
    public String printDebugInfo() {
        String name = "none";
        String header = "none";
        String token = "none";

        if (mCachedAuthorizationHeader != null) {
            header = "ok";
        }

        Account account = getSelectedAccount();

        if (account instanceof YouTubeAccount) {
            if (account.getName() != null) {
                name = "ok";
            }
            if (((YouTubeAccount) account).getRefreshToken() != null) {
                token = "ok";
            }
        }

        return String.format("name=%s;header=%s;token=%s", name, header, token);
    }

    /**
     * Authorization should be updated periodically (see expire_in field in response)
     */
    private String createAuthorizationHeader(String refreshToken) {
        Log.d(TAG, "Updating authorization header...");

        String authorizationHeader = null;

        AccessToken token = obtainAccessToken(refreshToken);

        if (token != null) {
            authorizationHeader = String.format("%s %s", token.getTokenType(), token.getAccessToken());
        } else {
            Log.e(TAG, "Access token is null!");
        }

        return authorizationHeader;
    }

    private AccessToken obtainAccessToken(String refreshToken) {
        // We don't have context, so can't create instance here.
        // Let's hope someone already created one for us.
        if (GlobalPreferences.sInstance == null) {
            Log.e(TAG, "GlobalPreferences is null!");
            return null;
        }

        AccessToken token = null;

        if (refreshToken != null) {
            token = getAuthService().updateAccessToken(refreshToken);
        }

        return token;
    }

    private void syncWithRetrofit() {
        if (Helpers.isJUnitTest()) {
            return;
        }

        Map<String, String> headers = RetrofitOkHttpHelper.getAuthHeaders();
        headers.clear();

        Account selectedAccount = getSelectedAccount();

        if (mCachedAuthorizationHeader != null && selectedAccount != null) {
            headers.put("Authorization", mCachedAuthorizationHeader);
            String pageIdToken = ((YouTubeAccount) selectedAccount).getPageIdToken();
            if (pageIdToken != null) {
                // Apply branded account rights (restricted videos). Branded refresh token with current account page id.
                headers.put("X-Goog-Pageid", pageIdToken);
            }
        }
    }

    @Override
    public void addOnAccountChange(OnAccountChange listener) {
        mAccountManager.addOnAccountChange(listener);
    }

    @NonNull
    private static AuthService getAuthService() {
        return AuthService.instance();
    }
}

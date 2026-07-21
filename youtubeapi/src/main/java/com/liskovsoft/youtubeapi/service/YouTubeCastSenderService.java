package com.liskovsoft.youtubeapi.service;

import com.liskovsoft.mediaserviceinterfaces.CastSenderService;
import com.liskovsoft.mediaserviceinterfaces.data.CastEvent;
import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.lounge.InfoManager;
import com.liskovsoft.youtubeapi.lounge.models.info.TokenInfoList;
import com.liskovsoft.youtubeapi.lounge.sender.LoungeSenderSession;
import com.liskovsoft.youtubeapi.lounge.sender.PairingManager;
import com.liskovsoft.youtubeapi.lounge.sender.SenderCommand;
import com.liskovsoft.youtubeapi.lounge.sender.SenderParams;
import com.liskovsoft.youtubeapi.lounge.sender.models.PairedScreen;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.reactivex.rxjava3.core.Observable;

/**
 * Sender side of the Lounge protocol (cast TO a TV).<br/>
 * Counterpart of {@link YouTubeRemoteControlService} (receiver side); reuses the
 * receiver's {@link InfoManager#getTokenInfo} for lounge token (re)minting.
 */
public class YouTubeCastSenderService implements CastSenderService {
    private static final String TAG = YouTubeCastSenderService.class.getSimpleName();
    private static YouTubeCastSenderService sInstance;
    private final PairingManager mPairingManager;
    private final InfoManager mInfoManager;
    /**
     * loungeToken minted by get_screen, kept for the first connect (screenId -> token)
     */
    private final Map<String, String> mCachedTokens = new HashMap<>();
    private volatile LoungeSenderSession mSession;
    private String mSenderName;
    private String mDeviceId;

    private YouTubeCastSenderService() {
        mPairingManager = RetrofitHelper.create(PairingManager.class);
        mInfoManager = RetrofitHelper.create(InfoManager.class);
    }

    public static YouTubeCastSenderService instance() {
        if (sInstance == null) {
            sInstance = new YouTubeCastSenderService();
        }

        return sInstance;
    }

    @Override
    public Observable<CastScreen> pairWithCodeObserve(String tvCode) {
        return RxHelper.fromCallable(() -> pairWithCode(tvCode));
    }

    @Override
    public Observable<CastEvent> connectObserve(CastScreen screen) {
        return RxHelper.createLong(emitter -> {
            if (screen == null || screen.getScreenId() == null) {
                RxHelper.onError(emitter, "connect: empty screen");
                return;
            }

            String loungeToken;

            synchronized (mCachedTokens) {
                loungeToken = mCachedTokens.remove(screen.getScreenId());
            }

            if (loungeToken == null) {
                loungeToken = mintLoungeToken(screen.getScreenId());
            }

            if (loungeToken == null) {
                RxHelper.onError(emitter, "connect: can't obtain lounge token. Unpaired?");
                return;
            }

            LoungeSenderSession session = new LoungeSenderSession(
                    loungeToken,
                    getSenderName(),
                    getDeviceId(),
                    () -> mintLoungeToken(screen.getScreenId()),
                    emitter::onNext);
            mSession = session;
            // Disposal interrupts the long-poll and tears the session down
            emitter.setCancellable(session::stop);

            try {
                // Blocks on this io thread. Emits connected() after the bind, then streams
                // events. Throws only when the very first bind fails (surfaces as onError);
                // later failures end as a disconnected(reason) event + normal return.
                session.start();
            } finally {
                if (mSession == session) {
                    mSession = null;
                }
            }

            emitter.onComplete();
        });
    }

    @Override
    public Observable<Void> loadVideoObserve(String videoId, long positionMs) {
        return postCommandObserve(SenderCommand.setPlaylist(videoId, positionMs));
    }

    @Override
    public Observable<Void> playObserve() {
        return postCommandObserve(SenderCommand.play());
    }

    @Override
    public Observable<Void> pauseObserve() {
        return postCommandObserve(SenderCommand.pause());
    }

    @Override
    public Observable<Void> seekToObserve(long positionMs) {
        return postCommandObserve(SenderCommand.seekTo(positionMs));
    }

    @Override
    public Observable<Void> setVolumeObserve(int volume) {
        return postCommandObserve(SenderCommand.setVolume(volume));
    }

    @Override
    public Observable<Void> setSubtitleObserve(String videoId, String vssId, String languageCode) {
        return postCommandObserve(SenderCommand.setSubtitlesTrack(videoId, vssId, languageCode));
    }

    @Override
    public Observable<Void> stopVideoObserve() {
        return postCommandObserve(SenderCommand.stopVideo());
    }

    @Override
    public boolean isConnected() {
        LoungeSenderSession session = mSession;

        return session != null && session.isConnected();
    }

    private CastScreen pairWithCode(String tvCode) {
        String pairingCode = SenderParams.normalizePairingCode(tvCode);

        if (pairingCode == null || pairingCode.isEmpty()) {
            Log.e(TAG, "Empty tv code");
            return null; // fromCallable surfaces null as onError
        }

        PairedScreen screen = RetrofitHelper.get(mPairingManager.getScreen(pairingCode));

        if (screen == null || screen.getScreenId() == null || screen.getLoungeToken() == null) {
            Log.e(TAG, "Pairing failed. Invalid or expired tv code?");
            return null;
        }

        synchronized (mCachedTokens) {
            mCachedTokens.put(screen.getScreenId(), screen.getLoungeToken());
        }

        return new CastScreen(screen.getScreenId(), screen.getName());
    }

    private Observable<Void> postCommandObserve(SenderCommand command) {
        return RxHelper.fromRunnable(() -> {
            LoungeSenderSession session = mSession;

            if (session == null || !session.isConnected()) {
                Log.w(TAG, "Can't send %s. Not connected.", command.getName());
                return;
            }

            session.sendCommand(command);
        });
    }

    private String mintLoungeToken(String screenId) {
        TokenInfoList tokenInfoList = RetrofitHelper.get(mInfoManager.getTokenInfo(screenId));

        if (tokenInfoList == null || tokenInfoList.getTokenInfos() == null || tokenInfoList.getTokenInfos().isEmpty()) {
            Log.e(TAG, "Can't mint lounge token. Screen unknown/unpaired?");
            return null;
        }

        return tokenInfoList.getTokenInfos().get(0).getLoungeToken();
    }

    /**
     * Shown on the TV: "&lt;name&gt; connected"
     */
    private String getSenderName() {
        if (mSenderName == null) {
            try {
                mSenderName = String.format(
                        "%s (%s)",
                        Helpers.getUserDeviceName(GlobalPreferences.context()),
                        AppInfoHelpers.getAppLabel(GlobalPreferences.context())
                );
            } catch (RuntimeException e) { // no context yet
                mSenderName = "SmartTube";
            }
        }

        return mSenderName;
    }

    /**
     * Stable opaque sender id. Kept separate from the receiver's persisted device id
     * so casting can't collide with the remote-control role on the same install.
     */
    private String getDeviceId() {
        if (mDeviceId == null) {
            mDeviceId = UUID.randomUUID().toString();
        }

        return mDeviceId;
    }
}

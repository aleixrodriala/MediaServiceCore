package com.liskovsoft.youtubeapi.lounge.sender;

import com.liskovsoft.googlecommon.common.converters.jsonpath.WithJsonPath;
import com.liskovsoft.youtubeapi.lounge.sender.models.PairedScreen;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * Sender-side pairing: exchange a manual "Link with TV code" for a screen.<br/>
 * Token re-minting reuses the existing receiver-side
 * {@link com.liskovsoft.youtubeapi.lounge.InfoManager#getTokenInfo} (get_lounge_token_batch).
 */
@WithJsonPath
public interface PairingManager {
    /**
     * NOTE: normalize the code first (strip whitespace and dashes) — see
     * {@link SenderParams#normalizePairingCode}
     */
    @GET("https://www.youtube.com/api/lounge/pairing/get_screen")
    Call<PairedScreen> getScreen(@Query("pairing_code") String pairingCode);
}

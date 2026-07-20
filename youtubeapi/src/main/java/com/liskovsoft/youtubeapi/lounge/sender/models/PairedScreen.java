package com.liskovsoft.youtubeapi.lounge.sender.models;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;

/**
 * Response of {@code pairing/get_screen} (manual TV-code pairing, sender side).<br/>
 * NOTE: {@code expiration} is a STRING of epoch ms here, unlike
 * {@code get_lounge_token_batch} where it's a number.
 */
public class PairedScreen {
    @JsonPath("$.screen.screenId")
    private String mScreenId;

    @JsonPath("$.screen.loungeToken")
    private String mLoungeToken;

    @JsonPath("$.screen.name")
    private String mName;

    @JsonPath("$.screen.expiration")
    private String mExpiration;

    public String getScreenId() {
        return mScreenId;
    }

    public String getLoungeToken() {
        return mLoungeToken;
    }

    public String getName() {
        return mName;
    }

    public String getExpiration() {
        return mExpiration;
    }
}

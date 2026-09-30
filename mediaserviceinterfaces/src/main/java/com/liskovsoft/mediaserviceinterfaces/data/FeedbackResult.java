package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * NEWTUBE(not-interested): YouTube's answer to one feedback token - the HTTP status (-1 when no
 * response arrived) and the answer's {@code isProcessed} flag.
 */
public interface FeedbackResult {
    int getCode();
    boolean isProcessed();
}

package com.liskovsoft.youtubeapi.feedback;

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackReasons;
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackReasons.FeedbackItem;
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackResult;
import com.liskovsoft.youtubeapi.feedback.models.FeedbackReason;
import com.liskovsoft.youtubeapi.feedback.models.FeedbackResponse;
import com.liskovsoft.youtubeapi.track.TrackingService;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

public class FeedbackService {
    private static FeedbackService sInstance;
    private final FeedbackApi mFeedbackApi;

    private FeedbackService() {
        mFeedbackApi = RetrofitHelper.create(FeedbackApi.class);
    }

    public static FeedbackService instance() {
        if (sInstance == null) {
            sInstance = new FeedbackService();
        }

        return sInstance;
    }

    public void markAsNotInterested(String feedbackToken) {
        Call<FeedbackResponse> wrapper = mFeedbackApi.setNotInterested(
                FeedbackApiHelper.getNotInterestedQuery(feedbackToken));
        RetrofitHelper.get(wrapper); // ignore result
        // The same feedback endpoint removes history entries. A later replay must be
        // allowed to create a new record instead of reusing the removed one.
        TrackingService.instance().clearCache();
    }

    /**
     * NEWTUBE(not-interested): {@link #markAsNotInterested} that keeps YouTube's answer - the HTTP
     * status (-1 when no response arrived) and whether the token was processed.
     */
    public FeedbackResult sendFeedback(String feedbackToken) {
        Call<FeedbackResponse> wrapper = mFeedbackApi.setNotInterested(
                FeedbackApiHelper.getNotInterestedQuery(feedbackToken));
        Response<FeedbackResponse> response = RetrofitHelper.getResponse(wrapper);
        int code = response != null ? response.code() : -1;
        boolean processed = response != null && response.body() != null && response.body().isProcessed();

        return new FeedbackResult() {
            @Override
            public int getCode() {
                return code;
            }

            @Override
            public boolean isProcessed() {
                return processed;
            }
        };
    }

    public FeedbackReasons getReasons(String feedbackToken) {
        Call<FeedbackResponse> wrapper = mFeedbackApi.setNotInterested(
                FeedbackApiHelper.getNotInterestedQuery(feedbackToken));
        FeedbackResponse feedbackResponse = RetrofitHelper.get(wrapper);

        return getFeedbackReasons(feedbackResponse);
    }

    private static FeedbackReasons getFeedbackReasons(FeedbackResponse feedbackResponse) {
        if (feedbackResponse == null || feedbackResponse.getReasons() == null || feedbackResponse.getReasons().isEmpty()) {
            return emptyReasons();
        }

        List<FeedbackItem> feedbackItems = getFeedbackItems(feedbackResponse);

        return new FeedbackReasons() {
            @Override
            public String getTitle() {
                return feedbackResponse.getTitle();
            }

            @Override
            public List<FeedbackItem> getItems() {
                return feedbackItems;
            }
        };
    }
    
    private static List<FeedbackItem> getFeedbackItems(FeedbackResponse feedbackResponse) {
        List<FeedbackItem> feedbackItems = new ArrayList<>();
        for (FeedbackReason reason : feedbackResponse.getReasons()) {
            feedbackItems.add(new FeedbackItem() {
                @Override
                public String getTitle() {
                    return reason.getTitle();
                }

                @Override
                public String getToken() {
                    return reason.getToken();
                }
            });
        }
        return feedbackItems;
    }

    private static FeedbackReasons emptyReasons() {
        return new FeedbackReasons() {
            @Override
            public String getTitle() {
                return null;
            }

            @Override
            public List<FeedbackItem> getItems() {
                return null;
            }
        };
    }
}

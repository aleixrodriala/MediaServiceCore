package com.liskovsoft.youtubeapi.search;

import static com.liskovsoft.mediaserviceinterfaces.data.SearchOptions.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SearchFilterHelperTest {
    @Test
    public void singleFiltersKeepTheirWireValues() {
        int[] options = {UPLOAD_DATE_LAST_HOUR, UPLOAD_DATE_TODAY, UPLOAD_DATE_THIS_WEEK,
                UPLOAD_DATE_THIS_MONTH, UPLOAD_DATE_THIS_YEAR, DURATION_UNDER_4,
                DURATION_BETWEEN_4_20, DURATION_OVER_20, TYPE_VIDEO, TYPE_CHANNEL,
                TYPE_PLAYLIST, TYPE_MOVIE, FEATURE_LIVE, FEATURE_4K, FEATURE_HDR,
                SORT_BY_UPLOAD_DATE, SORT_BY_VIEW_COUNT, SORT_BY_RATING};
        String[] params = {"EgQIARAB", "EgQIAhAB", "EgQIAxAB", "EgQIBBAB", "EgQIBRAB",
                "EgQQARgB", "EgQQARgD", "EgQQARgC", "EgIQAQ%3D%3D", "EgIQAg%3D%3D",
                "EgIQAw%3D%3D", "EgIQBA%3D%3D", "EgJAAQ%3D%3D", "EgJwAQ%3D%3D",
                "EgPIAQE%3D", "CAI%3D", "CAMSAhAB", "CAESAhAB"};
        for (int i = 0; i < options.length; i++) {
            assertEquals("Option " + options[i], params[i], SearchFilterHelper.toParams(options[i]));
        }
    }

    @Test
    public void combinationsKeepEverySelectedField() {
        // Previously absent from the lookup table: explicit Video plus duration silently lost both.
        assertEquals("EgQQARgB", SearchFilterHelper.toParams(TYPE_VIDEO | DURATION_UNDER_4));
        // Live + HDR previously dropped HDR.
        assertEquals("EgVAAcgBAQ%3D%3D",
                SearchFilterHelper.toParams(FEATURE_LIVE | FEATURE_HDR));
        // The old entry incorrectly encoded a long video and omitted the selected week.
        assertEquals("CAISBggDEAEYAQ%3D%3D", SearchFilterHelper.toParams(
                UPLOAD_DATE_THIS_WEEK | DURATION_UNDER_4 | SORT_BY_UPLOAD_DATE));
        // Sort=views; date=week, type=video, duration=long, live, 4K, HDR.
        assertEquals("CAMSDQgDEAEYAkABcAHIAQE%3D", SearchFilterHelper.toParams(
                SORT_BY_VIEW_COUNT | UPLOAD_DATE_THIS_WEEK | TYPE_VIDEO | DURATION_OVER_20
                        | FEATURE_LIVE | FEATURE_4K | FEATURE_HDR));
    }

    @Test
    public void defaultsDoNotSendAnEmptyFilter() {
        assertNull(SearchFilterHelper.toParams(-1));
        assertNull(SearchFilterHelper.toParams(0));
        assertNull(SearchFilterHelper.toParams(SORT_BY_RELEVANCE));
    }
}

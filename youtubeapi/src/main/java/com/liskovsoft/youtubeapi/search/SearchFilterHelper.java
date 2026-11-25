package com.liskovsoft.youtubeapi.search;

import android.util.Base64;

import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;

import java.io.ByteArrayOutputStream;

final class SearchFilterHelper {
    private SearchFilterHelper() {
    }

    public static String toParams(int options) {
        if (options == -1 || options == 0) {
            return null;
        }

        int date = valueFor(options, SearchOptions.UPLOAD_DATE_LAST_HOUR, SearchOptions.UPLOAD_DATE_TODAY,
                SearchOptions.UPLOAD_DATE_THIS_WEEK, SearchOptions.UPLOAD_DATE_THIS_MONTH, SearchOptions.UPLOAD_DATE_THIS_YEAR);
        int duration = valueFor(options, SearchOptions.DURATION_UNDER_4,
                SearchOptions.DURATION_OVER_20, SearchOptions.DURATION_BETWEEN_4_20);
        int type = valueFor(options, SearchOptions.TYPE_VIDEO, SearchOptions.TYPE_CHANNEL,
                SearchOptions.TYPE_PLAYLIST, SearchOptions.TYPE_MOVIE);
        int sort = valueFor(options, SearchOptions.SORT_BY_RATING,
                SearchOptions.SORT_BY_UPLOAD_DATE, SearchOptions.SORT_BY_VIEW_COUNT);
        // These filters imply videos when no explicit content type was selected.
        if (type == 0 && (date != 0 || duration != 0 || sort == 1 || sort == 3)) {
            type = 1;
        }

        // InnerTube search params: field 1 is sorting; field 2 is the nested filter message.
        ByteArrayOutputStream filters = new ByteArrayOutputStream();
        writeField(filters, 1, date);
        writeField(filters, 2, type);
        writeField(filters, 3, duration);
        writeField(filters, 8, (options & SearchOptions.FEATURE_LIVE) != 0 ? 1 : 0);
        writeField(filters, 14, (options & SearchOptions.FEATURE_4K) != 0 ? 1 : 0);
        writeField(filters, 25, (options & SearchOptions.FEATURE_HDR) != 0 ? 1 : 0);

        ByteArrayOutputStream params = new ByteArrayOutputStream();
        writeField(params, 1, sort);
        if (filters.size() > 0) {
            params.write(0x12);
            params.write(filters.size()); // This fixed set of fields is always shorter than 128 bytes.
            byte[] bytes = filters.toByteArray();
            params.write(bytes, 0, bytes.length);
        }
        return params.size() == 0 ? null : Base64.encodeToString(params.toByteArray(),
                Base64.URL_SAFE | Base64.NO_WRAP).replace("=", "%3D");
    }

    private static int valueFor(int options, int... flags) {
        for (int i = 0; i < flags.length; i++) {
            if ((options & flags[i]) != 0) return i + 1;
        }
        return 0;
    }

    private static void writeField(ByteArrayOutputStream output, int field, int value) {
        if (value == 0) return;
        int tag = field << 3;
        while (tag > 0x7f) {
            output.write((tag & 0x7f) | 0x80);
            tag >>>= 7;
        }
        output.write(tag);
        output.write(value); // All supported enum and boolean values fit in one byte.
    }
}

package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSourceCatalog;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.VideoFormat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NEWTUBE(playback-identity): the synthetic one-minute wall for debug and benchmark builds
 * ({@code debug.arc.poison_wall_s}, the app's DebugMediaShaper refuses the bytes). Modelled on the
 * real one as r11 measured it on two walled visitors: it walls the media of the web-session sources
 * (VISIONOS, ANDROID_VR, the Web family: PlayerSource.Identity.WEB_SESSION) for every visitor except
 * one a playback re-roll minted (or kept); TV_TIZEN, IOS and ANDROID_REEL (the app's visitor) and
 * WEB_EMBED (its embed identity) are never walled - TV_TIZEN played 137 s on the very visitor
 * VISIONOS and ANDROID_VR walled. Answers are known by their googlevideo URLs' {@code ei} (one per
 * /player response). Off (the default, and always in release) it records nothing.
 */
public final class DebugPlaybackWall {
    private static volatile boolean sEnabled;
    private static final Set<String> sLifted = Collections.newSetFromMap(bounded(16));
    private static final Set<String> sWalledEi = Collections.newSetFromMap(bounded(64));
    private static final Pattern EI = Pattern.compile("(?:[?&]|%26|%3F)ei(?:=|%3D)([A-Za-z0-9_-]+)");

    private DebugPlaybackWall() {
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    /** A playback re-roll minted {@code visitorData}: its answers are not walled. */
    public static void lift(@Nullable String visitorData) {
        if (sEnabled && visitorData != null) {
            sLifted.add(visitorData);
        }
    }

    /**
     * {@code client}'s /player answer, with the visitor its request carried: walled when a
     * web-session source sent a visitor no re-roll lifted.
     */
    static void noteAnswer(@Nullable AppClient client, @Nullable VideoInfo result) {
        if (!sEnabled || result == null || client == null
                || PlayerSourceCatalog.defaultFor(client).identity != PlayerSource.Identity.WEB_SESSION
                || sLifted.contains(result.getRequestVisitorData())) {
            return;
        }
        // A kept re-rolled identity (restored by a later process) escapes it too.
        String kept = com.liskovsoft.youtubeapi.app.PlaybackIdentity.keptVisitor();
        if (kept != null && kept.equals(result.getRequestVisitorData())) {
            return;
        }
        noteFormats(result.getAdaptiveFormats());
        noteFormats(result.getRegularFormats());
    }

    /** For the app's shaper: the media URL's answer is walled. */
    public static boolean isWalledEi(@Nullable String ei) {
        return sEnabled && ei != null && sWalledEi.contains(ei);
    }

    @Nullable
    static String eiOf(@Nullable String urlOrCipher) {
        if (urlOrCipher == null) {
            return null;
        }
        Matcher matcher = EI.matcher(urlOrCipher);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void noteFormats(@Nullable List<? extends VideoFormat> formats) {
        if (formats == null) {
            return;
        }
        for (VideoFormat format : formats) {
            if (format == null) {
                continue;
            }
            String ei = eiOf(format.getUrl()); // the url a cipher carries too (VideoUrlHolder)
            if (ei != null) {
                sWalledEi.add(ei);
                return; // one ei per answer
            }
        }
    }

    /** Test hook. */
    static void resetForTest() {
        sEnabled = false;
        sLifted.clear();
        sWalledEi.clear();
    }

    private static <K> Map<K, Boolean> bounded(final int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<K, Boolean>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Boolean> eldest) {
                return size() > capacity;
            }
        });
    }
}

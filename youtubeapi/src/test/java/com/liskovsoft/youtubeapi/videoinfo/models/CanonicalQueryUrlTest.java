package com.liskovsoft.youtubeapi.videoinfo.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.sharedutils.querystringparser.UrlQueryString;
import com.liskovsoft.sharedutils.querystringparser.UrlQueryStringFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NEWTUBE(open-cpu): CanonicalQueryUrl may only accept a url whose sharedutils parse prints it
 * back unchanged and answers every lookup like a plain decode - on real /player urls and on a
 * random sweep around every rule of its grammar. And VideoUrlHolder, which uses it, must return
 * what it returned when it always parsed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CanonicalQueryUrlTest {
    private static final String[] FIXTURES = {
            "video_info/player_2026_09/visionos_dQw4w9WgXcQ.json",
            "video_info/player_2026_09/visionos_gFM-BL_0YvI.json",
            "video_info/player_2026_09/visionos_aqz-KE-bpKQ.json",
            "video_info/player_2026_09/tvhtml5_Fo89b8zAIE4.json",
    };
    private static final Pattern URL = Pattern.compile("https?://[^\"\\\\\\s]+");
    private static final String[] PROBE_KEYS = {"n", "xtags", "sig", "lsig", "pot", "cpn", "ei", "expire",
            "sparams", "id", "itag", "mime", "missing", "", "Expire"};

    @Test
    public void realMediaUrlsAreCanonicalAndRoundTrip() throws Exception {
        int canonical = 0;
        int media = 0;
        for (String fixture : FIXTURES) {
            String json = resource(fixture).replace("\\u0026", "&");
            Matcher matcher = URL.matcher(json);
            while (matcher.find()) {
                String url = matcher.group();
                assertAgreesWithParser(url);
                if (url.contains("googlevideo.com/videoplayback?")) {
                    media++;
                    if (CanonicalQueryUrl.INSTANCE.isCanonical(url)) {
                        canonical++;
                    }
                }
            }
        }
        // The fast path must actually be taken by the everyday media urls.
        assertTrue("media urls " + media, media > 50);
        assertEquals(media, canonical);
    }

    @Test
    public void randomUrlsAroundEveryRuleNeverDisagree() {
        Random random = new Random(20260925);
        // Index 0 of every pool is canonical, so most urls are near-valid and each rule is
        // broken on its own.
        String[] schemes = {"https://", "http://", "HTTPS://", "ftp://", "https:/", ""};
        String[] hosts = {"rr1---sn-abc.googlevideo.com", "www.youtube.com", "a.b", "a-.b.com", "-a.b.com",
                "a..com", "localhost", "1.2.3.4", "host.1com", "Host.com", "host.com:443", "u@host.com",
                "a_b.com", "x.y.z.example", "host.com."};
        String[] paths = {"/videoplayback", "/api/timedtext", "/a/b/c", "/", "", "/v%20p", "/a.b-c_d",
                "/seg~", "/a/b"};
        String[] keys = {"expire", "n", "sig", "xtags", "a.b", "a-b", "a_b", "k%20", "k+", "", "K", "*",
                "expire"};
        String[] values = {"1790354768", "abc-_.*", "a+b", "%2C", "%2c", "%2B", "%20", "%3D", "%7E", "~",
                "%C3%A9", "%ZZ", "%", "a=b", "", "a;b", "a/b", "a?b", "%2A", "%2D", "%41", "%25", "%0A",
                "acont%3Ddubbed-auto%3Alang%3Des", "x y", "#frag", "\u00e9", "%2F", "%3A", "Zz09"};
        int accepted = 0;
        for (int i = 0; i < 30_000; i++) {
            StringBuilder url = new StringBuilder();
            url.append(pick(random, schemes)).append(pick(random, hosts)).append(pick(random, paths));
            if (random.nextInt(10) > 0) {
                url.append('?');
                int params = 1 + random.nextInt(5);
                for (int p = 0; p < params; p++) {
                    if (p > 0) {
                        url.append(random.nextInt(15) == 0 ? ";" : "&");
                    }
                    url.append(pick(random, keys));
                    if (random.nextInt(12) > 0) {
                        url.append('=');
                        int parts = 1 + random.nextInt(3);
                        for (int v = 0; v < parts; v++) {
                            url.append(pick(random, values));
                        }
                    }
                }
                if (random.nextInt(20) == 0) {
                    url.append('&');
                }
            }
            if (assertAgreesWithParser(url.toString())) {
                accepted++;
            }
        }
        System.out.println("canonical sweep: accepted=" + accepted + " of 30000");
        // Both answers are exercised.
        assertTrue("accepted " + accepted, accepted > 300 && accepted < 29_000);
    }

    @Test
    public void holderAnswersWithoutParsingExactlyAsItDidWithParsing() {
        String url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&n=abc%2Cdef&xtags=acont%3Doriginal%3Alang%3Den-US&sparams=expire%2Cn";

        VideoUrlHolder fast = new VideoUrlHolder(url, null, null);
        assertEquals("abc,def", fast.getNParam());
        assertEquals(url, fast.getUrl()); // unmodified: the raw text, which is also the print
        String language = fast.getLanguage();
        assertTrue(language, language.startsWith("en") && language.endsWith("US (original)"));

        // A modification builds the query and prints it once until the next one.
        fast.setNParam("solved");
        String modified = fast.getUrl();
        assertEquals(UrlQueryStringFactory.parse(url).toString().replace("n=abc%2Cdef", "n=solved"), modified);
        fast.setCpn("cpn1");
        assertTrue(fast.getUrl().endsWith("&cpn=cpn1"));
        fast.setParam("n", null); // no-op, text unchanged
        assertTrue(fast.getUrl().contains("n=solved"));

        // setUrl after a lookup kept printing the first url's query; it still does.
        VideoUrlHolder replaced = new VideoUrlHolder(url, null, null);
        assertEquals("abc,def", replaced.getNParam());
        replaced.setUrl("https://other.example.com/x?y=1");
        assertEquals(url, replaced.getUrl());

        // Without a lookup setUrl simply wins, as before.
        VideoUrlHolder untouched = new VideoUrlHolder(url, null, null);
        untouched.setUrl("https://other.example.com/x?y=1");
        assertEquals("https://other.example.com/x?y=1", untouched.getUrl());

        // A non-canonical url still takes the parser: its print differs from the raw text.
        String odd = "https://host.example.com/videoplayback?a=%2c&b=1";
        VideoUrlHolder slow = new VideoUrlHolder(odd, null, null);
        assertNull(slow.getNParam());
        assertEquals(UrlQueryStringFactory.parse(odd).toString(), slow.getUrl());

        // Ciphered formats: the url comes out of the cipher first.
        String cipher = "s=SIG&sp=sig&url=" + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8);
        VideoUrlHolder ciphered = new VideoUrlHolder(null, null, cipher);
        assertEquals("SIG", ciphered.getSParam());
        assertEquals("abc,def", ciphered.getNParam());
        ciphered.setSignature("GIS");
        assertTrue(ciphered.getUrl().endsWith("&sig=GIS"));
        assertNull(new VideoUrlHolder(null, null, null).getNParam());
        assertNull(new VideoUrlHolder(null, null, null).getLanguage());
    }

    /** @return whether the url was accepted; fails when an accepted url disagrees with the parser */
    private static boolean assertAgreesWithParser(String url) {
        if (!CanonicalQueryUrl.INSTANCE.isCanonical(url)) {
            return false;
        }
        UrlQueryString parsed = UrlQueryStringFactory.parse(url);
        assertEquals("print of " + url, url, parsed.toString());
        Set<String> keys = new LinkedHashSet<>();
        for (String param : url.substring(url.indexOf('?') + 1).split("&")) {
            keys.add(param.substring(0, param.indexOf('=')));
        }
        List<String> probes = new ArrayList<>(keys);
        for (String key : PROBE_KEYS) {
            probes.add(key);
        }
        for (String key : probes) {
            assertEquals("get(" + key + ") of " + url, parsed.get(key), CanonicalQueryUrl.INSTANCE.get(url, key));
        }
        return true;
    }

    /** Mostly a canonical choice, sometimes any: exercises each rule near valid urls. */
    private static String pick(Random random, String[] values) {
        int roll = random.nextInt(10);
        if (roll < 6) {
            return values[0];
        }
        if (roll < 8 && values.length > 2) {
            return values[1 + random.nextInt(2)];
        }
        return values[random.nextInt(values.length)];
    }

    private String resource(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

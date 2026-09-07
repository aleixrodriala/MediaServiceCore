package com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPathObj;
import com.liskovsoft.youtubeapi.videoinfo.models.CaptionTrack;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Offline response conversion only; every synthetic media URL remains unused. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JsonPathTreeReuseTest {
    @Test
    public void realVideoInfoRetainsValuesWhileParsingResponseTextOnlyOnce() {
        String response = denseVideoInfo();
        CountingParser current = new CountingParser(false);
        CountingParser legacy = new CountingParser(true);
        VideoInfo actual = read(VideoInfo.class, response, current);
        VideoInfo previous = read(VideoInfo.class, response, legacy);

        assertNotNull(actual);
        // Compare every mapped field, including inherited format fields, before lazy getters run.
        assertEquals(new Gson().toJson(previous), new Gson().toJson(actual));
        assertEquals("OK", actual.getRawPlayabilityStatus());
        assertFalse(actual.isUnplayable());
        assertEquals(24, actual.getAdaptiveFormats().size());
        assertEquals(137, actual.getAdaptiveFormats().get(0).getITag());
        assertEquals(1920, actual.getAdaptiveFormats().get(0).getWidth());
        assertEquals("0-100", actual.getAdaptiveFormats().get(0).getInit());
        assertEquals("101-200", actual.getAdaptiveFormats().get(0).getIndex());
        assertEquals("https://media.invalid/video/0", actual.getAdaptiveFormats().get(0).getUrl());
        assertEquals("Quoted \"title\" — música", actual.getVideoDetails().getTitle());
        assertEquals("line one\nline two", actual.getVideoDetails().getShortDescription());
        assertEquals("54", actual.getVideoDetails().getLengthSeconds());
        assertEquals(2, actual.getVideoDetails().getThumbnails().size());

        assertEquals(1, current.textParses);
        assertEquals(0, current.streamParses);
        assertTrue("formats and ranges must query existing subtrees", current.objectParses >= 75);
        assertEquals(1 + legacy.objectParses, legacy.textParses);
        assertTrue("legacy path serializes every nested object again", legacy.serializedCharacters > 0);
        current.assertTreeUnchangedAndReused();
        System.out.println("JsonPath work: newTextParses=" + current.textParses
                + " reusedObjectParses=" + current.objectParses
                + " legacyTextParses=" + legacy.textParses
                + " legacySerializedCharacters=" + legacy.serializedCharacters);
    }

    @Test
    public void realCaptionAndTranslationObjectsKeepTheirMappedValues() {
        String response = """
                {"playabilityStatus":{"status":"OK"},
                 "captions":{"playerCaptionsTracklistRenderer":{
                   "captionTracks":[
                     {"baseUrl":"https://captions.invalid/manual", "languageCode":"en",
                      "vssId":"manual-en", "isTranslatable":true,
                      "name":{"simpleText":"English"}},
                     {"baseUrl":"https://captions.invalid/automatic", "languageCode":"es",
                      "vssId":"auto-es", "isTranslatable":true, "kind":"asr",
                      "name":{"runs":[{"text":"Español"}]}}],
                   "translationLanguages":[
                     {"languageCode":"fr", "languageName":{"simpleText":"Français"}},
                     {"languageCode":"de", "languageName":{"runs":[{"text":"Deutsch"}]}}]
                 }}}
                """;
        CountingParser current = new CountingParser(false);
        CountingParser legacy = new CountingParser(true);
        VideoInfo actual = read(VideoInfo.class, response, current);
        VideoInfo previous = read(VideoInfo.class, response, legacy);

        assertNotNull(actual);
        assertEquals(new Gson().toJson(previous), new Gson().toJson(actual));
        assertEquals(2, actual.getTranslationLanguages().size());
        assertEquals("fr", actual.getTranslationLanguages().get(0).getLanguageCode());
        assertEquals("Français", actual.getTranslationLanguages().get(0).getLanguageName());
        assertEquals("de", actual.getTranslationLanguages().get(1).getLanguageCode());
        assertEquals("Deutsch", actual.getTranslationLanguages().get(1).getLanguageName());

        // The real getter appends the two translated choices to the two original tracks.
        List<CaptionTrack> tracks = actual.getCaptionTracks();
        assertEquals(4, tracks.size());
        assertEquals("en", tracks.get(0).getLanguageCode());
        assertEquals("English", tracks.get(0).getName());
        assertEquals("manual-en", tracks.get(0).getVssId());
        assertTrue(tracks.get(0).isTranslatable());
        assertFalse(tracks.get(0).isAutogenerated());
        assertEquals("es", tracks.get(1).getLanguageCode());
        assertEquals("Español*", tracks.get(1).getName());
        assertTrue(tracks.get(1).isAutogenerated());
        assertEquals("fr", tracks.get(2).getLanguageCode());
        assertEquals("de", tracks.get(3).getLanguageCode());

        assertEquals(1, current.textParses);
        assertEquals(8, current.objectParses);
        assertEquals(1 + legacy.objectParses, legacy.textParses);
        current.assertTreeUnchangedAndReused();
    }

    @Test
    public void inheritedPrimitiveAndNestedCollectionFieldsKeepTheirTypes() {
        CountingParser parser = new CountingParser(false);
        Fields result = read(Fields.class, """
                {"parent":"inherited", "name":"root", "enabled":true, "count":7,
                 "ratio":1.25, "numbers":[1,2,3], "labels":["á","quoted \\\"label\\\""],
                 "child":{"name":"nested"}, "items":[{"name":"first"},{"name":"second"}]}
                """, parser);

        assertNotNull(result);
        assertEquals("inherited", result.parent);
        assertEquals("root", result.name);
        assertTrue(result.enabled);
        assertEquals(7, result.count);
        assertEquals(1.25f, result.ratio, 0f);
        assertEquals(Arrays.asList(1, 2, 3), result.numbers);
        assertEquals(Arrays.asList("á", "quoted \"label\""), result.labels);
        assertEquals("nested", result.child.name);
        assertEquals("first", result.items.get(0).name);
        assertEquals("second", result.items.get(1).name);
        assertEquals(1, parser.textParses);
        assertEquals(3, parser.objectParses);
        parser.assertTreeUnchangedAndReused();
    }

    @Test
    public void markerObjectCanQueryAnArrayWithoutConvertingItBackToText() {
        CountingParser parser = new CountingParser(false);
        ArrayHolder result = read(ArrayHolder.class,
                "{\"pair\":[{\"name\":\"left\"},{\"name\":\"right\"}]}", parser);

        assertNotNull(result);
        assertNotNull(result.pair);
        assertEquals("left", result.pair.left);
        assertEquals("right", result.pair.right);
        assertEquals(1, parser.textParses);
        assertEquals(1, parser.objectParses);
        assertTrue(parser.objectRoots.get(0).isJsonArray());
        parser.assertTreeUnchangedAndReused();
    }

    @Test
    public void missingNullAndEmptyValuesRetainExistingFallbackBehavior() {
        CountingParser parser = new CountingParser(false);
        OptionalFields result = read(OptionalFields.class, """
                {"fallback":"used only when missing", "presentNull":null,
                 "empty":[], "items":[null,{}, {"name":"kept"}]}
                """, parser);

        assertNotNull(result);
        assertEquals("used only when missing", result.missingThenFallback);
        assertNull("a present null must not advance to another path", result.nullThenFallback);
        assertNull(result.empty);
        assertNull(result.missingChild);
        assertEquals(42, result.unchangedDefault);
        assertEquals(1, result.items.size());
        assertEquals("kept", result.items.get(0).name);
        assertEquals(1, parser.textParses);
        parser.assertTreeUnchangedAndReused();
        assertNull(read(Leaf.class, "{}", new CountingParser(false)));
    }

    @Test
    public void skippedResponsePrefixStillUsesOneTextParse() {
        CountingParser parser = new CountingParser(false);
        Fields result = new JsonPathSkipTypeAdapter<Fields>(parser.context, Fields.class)
                .read(stream(")]}'\n{\"child\":{\"name\":\"after prefix\"}}"));

        assertNotNull(result);
        assertEquals("after prefix", result.child.name);
        assertEquals(1, parser.textParses);
        assertEquals(1, parser.objectParses);
        parser.assertTreeUnchangedAndReused();
    }

    @Test
    public void responseWithDenialAndMissingMediaPreservesTheVerdict() {
        CountingParser parser = new CountingParser(false);
        VideoInfo result = read(VideoInfo.class, """
                {"playabilityStatus":{"status":"LOGIN_REQUIRED", "reason":"Unavailable"},
                 "streamingData":{"formats":[], "adaptiveFormats":[]}}
                """, parser);

        assertNotNull(result);
        assertEquals("LOGIN_REQUIRED", result.getRawPlayabilityStatus());
        assertTrue(result.isUnplayable());
        assertNull(result.getRegularFormats());
        assertNull(result.getAdaptiveFormats());
        assertEquals(1, parser.textParses);
        assertEquals(0, parser.objectParses);
        parser.assertTreeUnchangedAndReused();
    }

    private static <T> T read(Class<T> type, String response, CountingParser parser) {
        return new JsonPathTypeAdapter<T>(parser.context, type).read(stream(response));
    }

    private static InputStream stream(String response) {
        return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
    }

    private static String denseVideoInfo() {
        JsonObject root = new Gson().fromJson("""
                {"playabilityStatus":{"status":"OK"},
                 "videoDetails":{"videoId":"fixture-id", "title":"Quoted \\\"title\\\" — música",
                   "shortDescription":"line one\\nline two", "lengthSeconds":"54",
                   "thumbnail":{"thumbnails":[
                     {"url":"https://image.invalid/small", "width":120,"height":90},
                     {"url":"https://image.invalid/large", "width":480,"height":360}]}},
                 "streamingData":{"adaptiveFormats":[]}}
                """, JsonObject.class);
        JsonArray formats = root.getAsJsonObject("streamingData").getAsJsonArray("adaptiveFormats");
        for (int i = 0; i < 24; i++) {
            JsonObject format = new Gson().fromJson("""
                    {"itag":137,"mimeType":"video/mp4","width":1920,"height":1080,
                     "bitrate":2000000,"fps":24,"contentLength":"1000000",
                     "initRange":{"start":"0","end":"100"},
                     "indexRange":{"start":"101","end":"200"}}
                    """, JsonObject.class);
            format.addProperty("url", "https://media.invalid/video/" + i);
            formats.add(format);
        }
        return root.toString();
    }

    public static class ParentFields {
        @JsonPath("$.parent") public String parent;
    }

    public static class Leaf {
        @JsonPath("$.name") public String name;
    }

    public static class Fields extends ParentFields {
        @JsonPath("$.name") public String name;
        @JsonPath("$.enabled") public boolean enabled;
        @JsonPath("$.count") public int count;
        @JsonPath("$.ratio") public float ratio;
        @JsonPath("$.numbers[*]") public List<Integer> numbers;
        @JsonPath("$.labels[*]") public List<String> labels;
        @JsonPath("$.child") public Leaf child;
        @JsonPath("$.items[*]") public List<Leaf> items;
    }

    public static class ArrayHolder {
        @JsonPath("$.pair") public Pair pair;
    }

    public static class Pair implements JsonPathObj {
        @JsonPath("$[0].name") public String left;
        @JsonPath("$[1].name") public String right;
    }

    public static class OptionalFields {
        @JsonPath({"$.missing", "$.fallback"}) public String missingThenFallback;
        @JsonPath({"$.presentNull", "$.fallback"}) public String nullThenFallback;
        @JsonPath("$.empty[*]") public List<Leaf> empty;
        @JsonPath("$.missingChild") public Leaf missingChild;
        @JsonPath("$.missingNumber") public int unchangedDefault = 42;
        @JsonPath("$.items[*]") public List<Leaf> items;
    }

    /** Legacy arm models the former recursive toString + parse without a second runtime adapter. */
    private static final class CountingParser {
        final ParseContext context;
        final List<JsonElement> objectRoots = new ArrayList<>();
        final List<JsonElement> retainedObjectRoots = new ArrayList<>();
        int textParses;
        int streamParses;
        int objectParses;
        long serializedCharacters;
        JsonElement root;
        JsonElement original;

        CountingParser(boolean reparseObjects) {
            ParseContext delegate = com.jayway.jsonpath.JsonPath.using(Configuration.builder()
                    .jsonProvider(new GsonJsonProvider()).mappingProvider(new GsonMappingProvider()).build());
            context = (ParseContext) Proxy.newProxyInstance(ParseContext.class.getClassLoader(),
                    new Class<?>[]{ParseContext.class}, (proxy, method, args) -> {
                        Class<?> firstType = method.getParameterTypes().length == 0
                                ? null : method.getParameterTypes()[0];
                        if ("parse".equals(method.getName())) {
                            if (firstType == String.class) textParses++;
                            if (firstType == InputStream.class) streamParses++;
                            if (firstType == Object.class) {
                                objectParses++;
                                objectRoots.add((JsonElement) args[0]);
                                if (reparseObjects) {
                                    String serialized = args[0].toString();
                                    serializedCharacters += serialized.length();
                                    textParses++;
                                    return delegate.parse(serialized);
                                }
                            }
                        }
                        Object result;
                        try {
                            result = method.invoke(delegate, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (firstType == Object.class && result instanceof DocumentContext) {
                            retainedObjectRoots.add(((DocumentContext) result).json());
                        }
                        if (root == null && result instanceof DocumentContext) {
                            root = ((DocumentContext) result).json();
                            original = root.deepCopy();
                        }
                        return result;
                    });
        }

        void assertTreeUnchangedAndReused() {
            assertEquals("queries must not mutate the retained JSON", original, root);
            assertEquals(objectRoots.size(), retainedObjectRoots.size());
            for (int i = 0; i < objectRoots.size(); i++) {
                // JsonPath can copy nodes while assembling query results. The adapter must
                // retain the result it receives, regardless of that provider-level behavior.
                assertSame("nested parsing must retain the query result handed to the adapter",
                        objectRoots.get(i), retainedObjectRoots.get(i));
            }
        }
    }
}

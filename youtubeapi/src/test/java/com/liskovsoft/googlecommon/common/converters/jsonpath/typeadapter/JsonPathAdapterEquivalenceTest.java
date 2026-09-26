package com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.google.gson.GsonBuilder;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPathObj;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoHls;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoReel;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * NEWTUBE(open-cpu): the cached-metadata, direct-walk JsonPathTypeAdapter must map every input
 * exactly like the original (LegacyJsonPathTypeAdapter, a verbatim copy): same objects, same
 * field values, same nulls - including the jayway copy semantics the direct walk reproduces (a
 * JSON null member exists at the root but not inside a nested object).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JsonPathAdapterEquivalenceTest {
    private static final String[] PLAYER_FIXTURES = {
            "video_info/player_2026_09/visionos_dQw4w9WgXcQ.json",
            "video_info/player_2026_09/visionos_gFM-BL_0YvI.json",
            "video_info/player_2026_09/visionos_aqz-KE-bpKQ.json",
            "video_info/player_2026_09/tvhtml5_Fo89b8zAIE4.json",
            "video_info/get_video_info.json",
            "video_info/get_video_info_with_ads.json",
    };

    @Test
    public void realPlayerResponsesMapIdentically() throws Exception {
        for (String fixture : PLAYER_FIXTURES) {
            byte[] json = resource(fixture);
            for (Class<?> type : new Class<?>[]{VideoInfo.class, VideoInfoHls.class, VideoInfoReel.class}) {
                assertSame(fixture + " as " + type.getSimpleName(), type, json);
            }
        }
        // Sanity: the fresh fixtures really exercise formats, captions and translations.
        VideoInfo info = (VideoInfo) readNew(VideoInfo.class, resource(PLAYER_FIXTURES[0]));
        assertNotNull(info);
        assertTrue(info.getAdaptiveFormats().size() > 20);
        assertTrue(info.getTranslationLanguages().size() > 100);
        assertEquals("OK", info.getRawPlayabilityStatus());
    }

    @Test
    public void nullMembersKeepTheirRootVersusNestedMeaning() {
        String json = "{\"presentNull\":null, \"fallback\":\"root-fallback\", \"nested\":{\"presentNull\":null,"
                + " \"fallback\":\"nested-fallback\", \"deep\":{\"presentNull\":null}}, \"items\":[null,"
                + " {\"presentNull\":null, \"fallback\":\"item\"}], \"nullObject\":null, \"nullArray\":null}";
        assertSame("nulls", Nulls.class, json.getBytes(StandardCharsets.UTF_8));
        Nulls nulls = (Nulls) readNew(Nulls.class, json.getBytes(StandardCharsets.UTF_8));
        assertEquals(null, nulls.rootNullThenFallback); // present null stops the walk at the root
        assertEquals("nested-fallback", nulls.nested.nullThenFallback); // but not in a copy
        assertEquals(1, nulls.items.size());
        assertEquals("item", nulls.items.get(0).nullThenFallback);
    }

    @Test
    public void numbersBooleansAndStringsConvertIdentically() {
        String json = "{\"i\":42, \"neg\":-7, \"zero\":-0, \"big\":12345678901, \"huge\":123456789012345678901234,"
                + " \"dec\":-23.5, \"exp\":1e5, \"expDec\":1.5e-3, \"tiny\":1.5e-400, \"large\":1.5e400,"
                + " \"t\":true, \"s\":\"text\", \"numText\":\"12\", \"ints\":[1,-2,3.5,1e2,12345678901],"
                + " \"strings\":[\"a\",\"b\"], \"bools\":[true,false], \"mixed\":[1,\"x\",true,null,{\"i\":3}],"
                + " \"obj\":{\"i\":9, \"dec\":0.1, \"t\":false}}";
        assertSame("numbers", Numbers.class, json.getBytes(StandardCharsets.UTF_8));
        Numbers numbers = (Numbers) readNew(Numbers.class, json.getBytes(StandardCharsets.UTF_8));
        assertEquals(42, numbers.i);
        assertEquals(-23.5f, numbers.dec, 0f);
        assertEquals(9, numbers.obj.i);
    }

    @Test
    public void indexesWildcardsAndMarkerObjectsResolveIdentically() {
        String json = "{\"runs\":[{\"text\":\"a\"},null,{\"text\":\"c\"},\"plain\"], \"object\":{\"k1\":{\"text\":\"x\"},"
                + " \"k2\":null, \"k3\":5}, \"prim\":\"p\", \"nullValue\":null, \"pair\":[{\"text\":\"l\"},{\"text\":\"r\"}],"
                + " \"nested\":[[1,2],[3]], \"rows\":[{\"cells\":[{\"text\":\"c0\"}]},{\"cells\":null}],"
                + " \"emptyRuns\":[], \"numbers\":[10,20]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertSame("paths", Paths.class, bytes);
        assertSame("root array", RootArray.class,
                "[{\"text\":\"a\"},{\"text\":\"b\"}]".getBytes(StandardCharsets.UTF_8));
        assertSame("root primitive", Paths.class, "\"just text\"".getBytes(StandardCharsets.UTF_8));
        assertSame("root null", Paths.class, "null".getBytes(StandardCharsets.UTF_8));
        assertSame("empty", Paths.class, "".getBytes(StandardCharsets.UTF_8));
        assertSame("broken", Paths.class, "{\"runs\":[".getBytes(StandardCharsets.UTF_8));
        Paths paths = (Paths) readNew(Paths.class, bytes);
        assertEquals("a", paths.first);
        assertEquals("c", paths.third);
        assertEquals("l", paths.pair.left);
        assertEquals("r", paths.pair.right);
    }

    @Test
    public void unusualModelsFailTheSameWay() {
        byte[] json = "{\"name\":\"n\", \"list\":[1,2], \"child\":{\"name\":\"c\"}, \"number\":\"text\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertSame("raw list", RawList.class, json);
        assertSame("no default ctor", NoDefaultConstructor.class, json);
        assertSame("wrong type", WrongType.class, json);
        assertSame("inherited", Child.class, json);
        assertSame("filters and scans", Exotic.class,
                "{\"a\":{\"b\":{\"name\":\"deep\"}}, \"list\":[{\"id\":1,\"name\":\"one\"},{\"id\":2,\"name\":\"two\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Opt-in exhaustive sweep (NEWTUBE_JSONPATH_SWEEP=1): every JsonPath model class in src/main
     * against every JSON resource up to 1.5 MB.
     */
    @Test
    public void everyModelAgainstEveryResource() throws Exception {
        Assume.assumeTrue(System.getenv("NEWTUBE_JSONPATH_SWEEP") != null);
        List<Class<?>> models = modelClasses(new File("src/main/java"));
        List<File> fixtures;
        try (Stream<java.nio.file.Path> files = Files.walk(new File("src/test/resources").toPath())) {
            fixtures = files.map(java.nio.file.Path::toFile)
                    .filter(f -> f.getName().endsWith(".json") && f.length() < 1_500_000)
                    .sorted().collect(Collectors.toList());
        }
        int pairs = 0;
        int mapped = 0;
        for (File fixture : fixtures) {
            byte[] json = Files.readAllBytes(fixture.toPath());
            for (Class<?> model : models) {
                String legacy = serialize(readLegacy(model, json));
                String current = serialize(readNew(model, json));
                assertEquals(fixture + " as " + model.getName(), legacy, current);
                pairs++;
                if (!"null".equals(current)) {
                    mapped++;
                }
            }
        }
        System.out.println("jsonpath sweep: models=" + models.size() + " fixtures=" + fixtures.size()
                + " pairs=" + pairs + " nonNull=" + mapped);
    }

    private static List<Class<?>> modelClasses(File root) throws Exception {
        List<Class<?>> result = new ArrayList<>();
        try (Stream<java.nio.file.Path> files = Files.walk(root.toPath())) {
            for (java.nio.file.Path path : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                if (!source.contains("@JsonPath(")) {
                    continue;
                }
                String name = root.toPath().relativize(path).toString().replace(File.separatorChar, '.')
                        .replaceAll("\\.java$", "");
                Class<?> type;
                try {
                    type = Class.forName(name);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                addWithNested(type, result);
            }
        }
        return result;
    }

    private static void addWithNested(Class<?> type, List<Class<?>> out) {
        boolean annotated = false;
        for (Field field : type.getDeclaredFields()) {
            annotated |= field.isAnnotationPresent(JsonPath.class);
        }
        if (annotated && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            out.add(type);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            addWithNested(nested, out);
        }
    }

    private static void assertSame(String label, Class<?> type, byte[] json) {
        String legacy = serialize(readLegacy(type, json));
        String current = serialize(readNew(type, json));
        assertEquals(label, legacy, current);
    }

    private static ParseContext parser() {
        return com.jayway.jsonpath.JsonPath.using(Configuration.builder()
                .jsonProvider(new GsonJsonProvider()).mappingProvider(new GsonMappingProvider()).build());
    }

    static Object readNew(Class<?> type, byte[] json) {
        try {
            return new JsonPathTypeAdapter<Object>(parser(), type).read(new ByteArrayInputStream(json));
        } catch (RuntimeException e) {
            return "EXCEPTION " + e.getClass().getName();
        }
    }

    static Object readLegacy(Class<?> type, byte[] json) {
        try {
            return new LegacyJsonPathTypeAdapter<Object>(parser(), type).read(new ByteArrayInputStream(json));
        } catch (RuntimeException e) {
            return "EXCEPTION " + e.getClass().getName();
        }
    }

    static String serialize(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return value.getClass().getName() + " "
                    + new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().create().toJson(value);
        } catch (RuntimeException e) {
            return value.getClass().getName() + " unserializable " + e.getClass().getName();
        }
    }

    private byte[] resource(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            return in.readAllBytes();
        }
    }

    public static class NullHolder {
        @JsonPath({"$.presentNull", "$.fallback"}) public String nullThenFallback;
        @JsonPath("$.presentNull") public String presentNull;
        @JsonPath("$.deep.presentNull") public String deepNull;
        @JsonPath("$.deep") public NullHolder deep;
    }

    public static class Nulls {
        @JsonPath({"$.presentNull", "$.fallback"}) public String rootNullThenFallback;
        @JsonPath({"$.missing", "$.fallback"}) public String missingThenFallback;
        @JsonPath("$.nested") public NullHolder nested;
        @JsonPath("$.items[*]") public List<NullHolder> items;
        @JsonPath({"$.nullObject.child", "$.fallback"}) public String throughNull;
        @JsonPath({"$.nullArray[*]", "$.items[*]"}) public List<NullHolder> nullArrayThenItems;
        @JsonPath({"$.nullArray[0]", "$.fallback"}) public String nullArrayIndex;
        @JsonPath("$.nullObject") public NullHolder nullObject;
        @JsonPath("$.presentNull") public int untouchedDefault = 5;
    }

    public static class Numbers {
        @JsonPath("$.i") public int i;
        @JsonPath("$.neg") public int neg;
        @JsonPath("$.zero") public int zero;
        @JsonPath("$.big") public int big;
        @JsonPath("$.huge") public int huge;
        @JsonPath("$.dec") public float dec;
        @JsonPath("$.exp") public int exp;
        @JsonPath("$.expDec") public float expDec;
        @JsonPath("$.tiny") public float tiny;
        @JsonPath("$.large") public int large;
        @JsonPath("$.large") public float largeAsFloat;
        @JsonPath("$.t") public boolean t;
        @JsonPath("$.s") public String s;
        @JsonPath("$.numText") public String numText;
        @JsonPath("$.i") public String intAsString;
        @JsonPath("$.ints[*]") public List<Object> ints;
        @JsonPath("$.ints") public List<Object> intsWithoutWildcard;
        @JsonPath("$.ints[2]") public float thirdInt;
        @JsonPath("$.ints[4]") public int fifthInt;
        @JsonPath("$.strings[*]") public List<String> strings;
        @JsonPath("$.bools[*]") public List<Boolean> bools;
        @JsonPath("$.mixed[*]") public List<Numbers> mixed;
        @JsonPath("$.obj") public Numbers obj;
    }

    public static class Text {
        @JsonPath("$.text") public String text;
    }

    public static class Pair implements JsonPathObj {
        @JsonPath("$[0].text") public String left;
        @JsonPath("$[1].text") public String right;
    }

    public static class RootArray {
        @JsonPath("$[0].text") public String first;
        @JsonPath("$[*]") public List<Text> all;
        @JsonPath("$[5].text") public String missing;
    }

    public static class Paths {
        @JsonPath("$.runs[0].text") public String first;
        @JsonPath("$.runs[1].text") public String nullElement;
        @JsonPath({"$.runs[1].text", "$.runs[2].text"}) public String third;
        @JsonPath("$.runs[3].text") public String primitiveElement;
        @JsonPath("$.runs[9].text") public String outOfRange;
        @JsonPath("$.runs[3]") public String plainElement;
        @JsonPath("$.runs[1]") public String nullElementLeaf;
        @JsonPath("$.runs[*]") public List<Text> runs;
        @JsonPath("$.runs") public List<Text> runsNoWildcard;
        @JsonPath("$.object[*]") public List<Text> objectWildcard;
        @JsonPath("$.prim[*]") public List<Text> primitiveWildcard;
        @JsonPath("$.nullValue[*]") public List<Text> nullWildcard;
        @JsonPath({"$.missing[*]", "$.runs[*]"}) public List<Text> missingWildcard;
        @JsonPath("$.emptyRuns[*]") public List<Text> emptyRuns;
        @JsonPath("$.pair") public Pair pair;
        @JsonPath("$.runs") public Pair runsAsPair;
        @JsonPath("$.object.k1") public Text objectMember;
        @JsonPath("$.object.k1.text") public String objectMemberText;
        @JsonPath("$.prim.text") public String throughPrimitive;
        @JsonPath("$.runs.text") public String propertyOfArray;
        @JsonPath("$.object[0]") public String indexOfObject;
        @JsonPath("$.nested[*]") public List<Object> nested;
        @JsonPath("$.nested[0][1]") public int nestedIndex;
        @JsonPath("$.rows[*]") public List<Row> rows;
        @JsonPath("$.numbers[*]") public List<Integer> numbers;
        @JsonPath("$") public Text wholeRoot;
        @JsonPath("$..text") public String deepScan;
    }

    public static class Row {
        @JsonPath("$.cells[0].text") public String firstCell;
        @JsonPath("$.cells[*]") public List<Text> cells;
    }

    public static class RawList {
        @SuppressWarnings("rawtypes")
        @JsonPath("$.list") public List list;
        @JsonPath("$.name") public String name;
    }

    public static class NoDefaultConstructor {
        @JsonPath("$.name") public String name;

        public NoDefaultConstructor(String name) {
            this.name = name;
        }
    }

    public static class WrongType {
        @JsonPath("$.name") public int name;
        @JsonPath("$.child") public String child;
        @JsonPath("$.number") public int number;
        @JsonPath("$.list") public Text list;
        @JsonPath("$.name") public String ok;
    }

    public static class Parent {
        @JsonPath("$.name") public String parentName;
    }

    public static class Child extends Parent {
        @JsonPath("$.child.name") public String childName;
        @JsonPath("$.child") public Parent child;
    }

    public static class Exotic {
        @JsonPath("$..name") public String anyName;
        @JsonPath("$.list[?(@.id == 2)].name") public List<String> filtered;
        @JsonPath("$.list[0:1]") public List<Text> sliced;
        @JsonPath("$['a']['b'].name") public String bracketed;
        @JsonPath("$.list[-1].name") public String negative;
        @JsonPath("$.list[*].name") public List<String> names;
        @JsonPath("$.list.length()") public int length;
        @JsonPath("$.a.b") public Text viaSimple;
    }
}

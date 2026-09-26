package com.liskovsoft.googlecommon.common.converters.jsonpath.typeadapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPathObj;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;
import com.liskovsoft.googlecommon.common.helpers.ReflectionHelper;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NEWTUBE(open-cpu): maps a response onto a model through its {@link JsonPath} field annotations.
 * <p>
 * Same results as the original implementation (kept verbatim as the test oracle
 * LegacyJsonPathTypeAdapter), measured cheaper on every /player open:
 * <ul>
 *   <li>Class metadata - public constructor, annotated fields, their paths and kinds - is
 *       reflected once per class instead of once per mapped object. A /player response maps
 *       ~250 objects (formats, ranges, caption tracks, ~150 translation languages and their name
 *       runs), and on ART getDeclaredFields/getAnnotations allocate fresh Field and annotation
 *       proxy objects on every call.</li>
 *   <li>The paths the models actually use - {@code $.a.b}, {@code $.a[0].b}, {@code $.a[*]} -
 *       are walked directly on the Gson tree instead of through a jayway evaluation, which
 *       compiles-or-looks-up the path in a global LRU (a lock plus a linear deque scan), builds
 *       an evaluation context, and hands every hit out through {@code Gson.toJsonTree}: a DEEP
 *       copy of every object/array (each format object was copied once for the list and again
 *       per nested range) plus a second tree write for the result path string. Anything else
 *       (filters, slices, deep scans, bracket names, root paths) still goes through jayway.</li>
 * </ul>
 * The one jayway behaviour a direct walk must reproduce on purpose is that copy: the default
 * {@link GsonJsonProvider} copies with a default {@code Gson}, which DROPS object members whose
 * value is JSON null (array elements survive). So a nested object never "has" a null member,
 * while the root tree (parsed from text, never copied) does - e.g. a present {@code "x": null}
 * stops an {@code @JsonPath({"$.x", "$.y"})} fallback at the root but not inside a nested
 * object. {@link Scope#copied} carries exactly that, and {@link Tree#of} verifies the
 * provider really copies that way before the direct walk is used at all; any other provider,
 * option set or listener keeps the original per-field jayway evaluation.
 */
public class JsonPathTypeAdapter<T> {
    private static final String TAG = JsonPathTypeAdapter.class.getSimpleName();
    private static final String[] NO_PATH = new String[0];
    /** Class-level {@link JsonPath} per class ({@link #NO_PATH} when absent). */
    private static final Map<Class<?>, String[]> sClassPaths = new ConcurrentHashMap<>();
    private final ParseContext mParser;
    private final Class<?> mType;

    public JsonPathTypeAdapter(ParseContext parser, Class<?> type) {
        mParser = parser;
        mType = type;
    }

    public JsonPathTypeAdapter(ParseContext parser, Type type) {
        mParser = parser;
        mType = (Class<?>) type;
    }

    @SuppressWarnings("unchecked")
    public final T read(InputStream is) {
        is = process(is);

        Object jsonContent = null;

        String[] jsonPath = getClassJsonPath(getGenericType());

        if (jsonPath != null) { // annotation on the same collection class
            DocumentContext parser = mParser.parse(is);
            for (String path : jsonPath) {
                try {
                    jsonContent = parser.read(path);
                    break;
                } catch (PathNotFoundException e) {
                    Log.e(TAG, e.getMessage());
                }
            }
        } else { // annotation on field
            jsonContent = Helpers.toString(is);
        }

        T result = (T) readType(getGenericType(), jsonContent, false, null);

        //if (result == null) {
        //    // Dump root object
        //    ReflectionHelper.dumpDebugInfo(getGenericType(), jsonContent);
        //}

        return result;
    }

    /**
     * Enable additional processing like skipping first line etc
     */
    protected InputStream process(InputStream is) {
        return is;
    }

    private Class<?> getGenericType() {
        return mType;
    }

    /**
     * @param copied whether {@code jsonContent} stands for the provider's deep copy of itself
     *               (see the class doc) rather than for a tree jayway handed out or parsed
     * @param tree   the provider behaviour of the parse this node belongs to; null when not known
     *               yet (the root, or a node that came straight from a jayway read)
     */
    private Object readType(Class<?> type, Object jsonContent, boolean copied, Tree tree) {
        if (type == null || jsonContent == null) {
            return null;
        }

        Object obj = null;
        boolean done = false;

        try {
            TypeMeta meta = TypeMeta.of(type);
            obj = meta.constructor.newInstance();

            // Parse response text once, then query the existing Gson subtrees. Serializing each
            // format/caption/related item and parsing it again duplicates work before playback.
            // The String overload is still needed for the unannotated root response.
            DocumentContext context = null;
            Object node = jsonContent;

            if (jsonContent instanceof String) {
                context = mParser.parse((String) jsonContent);
                node = context.json();
                copied = false;
                tree = Tree.of(context.configuration());
            } else if (tree == null) {
                context = mParser.parse(jsonContent);
                tree = Tree.of(context.configuration());
            }

            if (tree != null && node instanceof JsonElement) {
                Scope scope = new Scope(tree, (JsonElement) node, copied, context);
                for (FieldMeta field : meta.fields) {
                    // At least one field is set
                    if (processField(field, obj, scope)) {
                        done = true;
                    }
                }
            } else {
                // Not a Gson tree of a verified default provider: the original evaluation.
                if (context == null) {
                    context = mParser.parse(jsonContent);
                }
                for (FieldMeta field : meta.fields) {
                    if (processFieldWithJayway(field, obj, context)) {
                        done = true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return done ? obj : null;
    }

    private boolean processField(FieldMeta meta, Object obj, Scope scope) {
        Object jsonVal = null;
        boolean copied = false;

        for (int i = 0; i < meta.paths.length; i++) {
            SimplePath simple = meta.simplePaths[i];
            if (simple != null) {
                JsonElement found = simple.lookup(scope);
                if (found == null) {
                    continue; // jayway: PathNotFoundException
                }
                if (found != SimplePath.FALLBACK) {
                    // Every container a lookup returns stands for the provider's copy of it.
                    jsonVal = found;
                    copied = true;
                    break;
                }
            }
            try {
                jsonVal = scope.context().read(meta.paths[i]);
                copied = false; // a real jayway result: already a copy
                break;
            } catch (PathNotFoundException e) {
                // NEWTUBE(request-hygiene): expected on every absent OPTIONAL field - a single
                // player/next response has hundreds, and the logger has no level gate, so the old
                // per-miss Log.d flooded logcat (~6.4k lines / 2.5min) in RELEASE builds too.
            }
        }

        if (jsonVal == null) {
            return false;
        }

        return setField(meta, obj, jsonVal, copied, scope.tree);
    }

    /** The original per-field evaluation, for a context whose provider was not verified. */
    private boolean processFieldWithJayway(FieldMeta meta, Object obj, DocumentContext parser) {
        Object jsonVal = null;

        for (String path : meta.paths) {
            try {
                jsonVal = parser.read(path);
                break;
            } catch (PathNotFoundException e) {
                // See processField.
            }
        }

        if (jsonVal == null) {
            return false;
        }

        return setField(meta, obj, jsonVal, false, null);
    }

    private boolean setField(FieldMeta meta, Object obj, Object jsonVal, boolean copied, Tree tree) {
        boolean done = false;
        Field field = meta.field;

        try {
            if (meta.isJsonPathObj) {
                Object val = readType(meta.type, jsonVal, copied, tree);
                field.set(obj, val);
            } else if (jsonVal instanceof JsonArray) {
                List<Object> list = null;
                Class<?> myType = meta.genericParamType();

                if (myType == null) {
                    Log.e(TAG, "Please, supply generic field for the list type: " + field);
                    return false;
                }

                for (Object jsonObj : (JsonArray) jsonVal) {
                    Object item;

                    if (jsonObj instanceof JsonPrimitive) {
                        item = parsePrimitive((JsonPrimitive) jsonObj);
                    } else {
                        item = readType(myType, jsonObj, copied, tree);
                    }

                    if (item != null) {
                        if (list == null) {
                            list = new ArrayList<>();
                        }

                        list.add(item);
                    }
                }

                field.set(obj, list);
            } else if (jsonVal instanceof JsonPrimitive) {
                Object val = parsePrimitive((JsonPrimitive) jsonVal);

                field.set(obj, val);
            } else if (jsonVal instanceof JsonObject) {
                Object val = readType(meta.type, jsonVal, copied, tree);
                field.set(obj, val);
            }

            done = field.get(obj) != null; // field is set
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "%s: Incompatible json value found %s. Same path on different types?", field.getType().getSimpleName(), jsonVal);
        } catch (IllegalAccessException e) {
            Log.d(TAG, "%s: Illegal access with value %s.", field.getType().getSimpleName(), jsonVal);
        }

        return done;
    }

    private static String[] getClassJsonPath(Class<?> type) {
        if (type == null) {
            // Same NullPointerException the uncached type.getAnnotations() threw.
            return getJsonPath(type.getAnnotations());
        }

        String[] paths = sClassPaths.get(type);

        if (paths == null) {
            paths = getJsonPath(type.getAnnotations());
            sClassPaths.put(type, paths != null ? paths : NO_PATH);
        }

        return paths == NO_PATH ? null : paths;
    }

    private static String[] getJsonPath(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof JsonPath) {
                return ((JsonPath) annotation).value();
            }
        }

        return null;
    }

    private Object parsePrimitive(JsonPrimitive jsonVal) {
        Object val;

        if (jsonVal.isNumber()) {
            // NEWTUBE(open-cpu): JsonPrimitive.toString() of a number is JsonWriter.value(Number),
            // which appends exactly Number.toString() (after a regex validation pass for parsed
            // numbers, which can only hold valid JSON number text) - so test that string directly
            // instead of allocating a writer and running the regex per number.
            if (jsonVal.getAsNumber().toString().contains(".")) {
                val = jsonVal.getAsFloat(); // Float
            } else {
                val = jsonVal.getAsInt(); // Integer
            }
        } else if (jsonVal.isBoolean()) {
            val = jsonVal.getAsBoolean(); // Boolean
        } else {
            val = jsonVal.getAsString(); // String
        }

        return val;
    }

    /** One object being mapped: its node, whether it stands for a copy, and a lazy jayway view. */
    private final class Scope {
        final Tree tree;
        final JsonElement node;
        final boolean copied;
        private DocumentContext mContext;

        Scope(Tree tree, JsonElement node, boolean copied, DocumentContext context) {
            this.tree = tree;
            this.node = node;
            this.copied = copied;
            mContext = context;
        }

        /** What jayway would have evaluated a non-simple path against. */
        DocumentContext context() {
            if (mContext == null) {
                mContext = mParser.parse(copied ? tree.copy(node) : node);
            }
            return mContext;
        }
    }

    /**
     * How one {@link GsonJsonProvider} hands values out: {@code setArrayIndex} into the result
     * array wraps every hit in {@code gson.toJsonTree(value)}, after {@code unwrap} for property
     * hits. Verified once per provider instance (see {@link #probe}); null when the direct walk
     * must not be used.
     */
    private static final class Tree {
        /** Weak keys: some callers build a fresh factory (and provider) per request. */
        private static final Map<JsonProvider, Boolean> sVerified = new WeakHashMap<>();
        final JsonProvider provider;

        private Tree(JsonProvider provider) {
            this.provider = provider;
        }

        static Tree of(Configuration configuration) {
            JsonProvider provider = configuration.jsonProvider();
            if (provider == null || provider.getClass() != GsonJsonProvider.class
                    || !configuration.getOptions().isEmpty()
                    || !configuration.getEvaluationListeners().isEmpty()) {
                return null;
            }

            Tree tree = new Tree(provider);
            Boolean verified;
            synchronized (sVerified) {
                verified = sVerified.get(provider);
            }
            if (verified == null) {
                verified = tree.probe();
                synchronized (sVerified) {
                    sVerified.put(provider, verified);
                }
            }
            return verified ? tree : null;
        }

        /** Exactly the element jayway's result array would hold for {@code value}. */
        JsonElement copy(Object value) {
            JsonArray holder = new JsonArray();
            provider.setArrayIndex(holder, 0, value);
            return holder.get(0);
        }

        /**
         * A property hit is unwrapped before the copy, which turns a parsed number into
         * Integer/Long/BigDecimal/Double. Plain int literals - nearly every number in a response -
         * take the shortcut: whatever boxed type the copy picks for them, the adapter only ever
         * reads their text (no '.') and int value, both identical.
         */
        JsonPrimitive rewrapNumber(JsonPrimitive number) {
            Number value = number.getAsNumber();
            if (value instanceof LazilyParsedNumber) {
                String text = value.toString();
                if (isSmallInt(text)) {
                    return new JsonPrimitive(Integer.parseInt(text));
                }
            }
            return (JsonPrimitive) copy(provider.unwrap(number));
        }

        private static boolean isSmallInt(String text) {
            int length = text.length();
            int start = length > 0 && text.charAt(0) == '-' ? 1 : 0;
            if (length - start < 1 || length - start > 9) {
                return false;
            }
            for (int i = start; i < length; i++) {
                char c = text.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            return true;
        }

        /** The copy semantics the direct walk relies on, checked against the real provider. */
        private boolean probe() {
            try {
                JsonObject inner = new JsonObject();
                inner.add("gone", JsonNull.INSTANCE);
                inner.addProperty("kept", "x");
                JsonArray array = new JsonArray();
                array.add(JsonNull.INSTANCE);
                array.add(inner);
                array.add(new JsonPrimitive(new LazilyParsedNumber("1.50")));
                JsonObject outer = new JsonObject();
                outer.add("gone", JsonNull.INSTANCE);
                outer.add("array", array);
                outer.add("inner", inner);

                JsonElement copy = copy(outer);
                if (!(copy instanceof JsonObject) || copy == outer) {
                    return false;
                }
                JsonObject copied = (JsonObject) copy;
                JsonElement copiedArray = copied.get("array");
                JsonElement copiedInner = copied.get("inner");
                if (copied.has("gone") || copied.size() != 2 || !(copiedArray instanceof JsonArray)
                        || !(copiedInner instanceof JsonObject)) {
                    return false;
                }
                JsonArray a = (JsonArray) copiedArray;
                if (a.size() != 3 || !a.get(0).isJsonNull() || !(a.get(1) instanceof JsonObject)
                        || a.get(1).getAsJsonObject().has("gone")
                        || !"x".equals(a.get(1).getAsJsonObject().get("kept").getAsString())
                        || ((JsonObject) copiedInner).has("gone")
                        || !(a.get(2) instanceof JsonPrimitive)
                        || !(a.get(2).getAsJsonPrimitive().getAsNumber() instanceof LazilyParsedNumber)
                        || !"1.50".equals(a.get(2).getAsJsonPrimitive().getAsNumber().toString())) {
                    return false;
                }

                JsonElement text = copy(provider.unwrap(new JsonPrimitive("t")));
                JsonElement flag = copy(provider.unwrap(new JsonPrimitive(true)));
                JsonElement number = copy(provider.unwrap(new JsonPrimitive(new LazilyParsedNumber("7"))));
                JsonElement nothing = copy(provider.unwrap(JsonNull.INSTANCE));
                return text instanceof JsonPrimitive && "t".equals(text.getAsString())
                        && flag instanceof JsonPrimitive && flag.getAsJsonPrimitive().isBoolean() && flag.getAsBoolean()
                        && number instanceof JsonPrimitive && number.getAsJsonPrimitive().isNumber()
                        && "7".equals(number.getAsNumber().toString())
                        && nothing == JsonNull.INSTANCE;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /**
     * A path made only of {@code .name}, {@code [n]} and a final {@code [*]} after {@code $},
     * evaluated with the outcomes of jayway 3's evaluation under default options (every token
     * before the last is definite, so a miss anywhere is PathNotFoundException).
     */
    static final class SimplePath {
        /** Returned when jayway itself must evaluate: a wildcard over an object's members. */
        static final JsonElement FALLBACK = new JsonPrimitive("jsonpath-fallback");
        private static final byte PROPERTY = 0;
        private static final byte INDEX = 1;
        private static final byte WILDCARD = 2;
        private final byte[] mKinds;
        private final String[] mNames;
        private final int[] mIndexes;

        private SimplePath(byte[] kinds, String[] names, int[] indexes) {
            mKinds = kinds;
            mNames = names;
            mIndexes = indexes;
        }

        /** Null when {@code path} is outside the simple subset. */
        static SimplePath compile(String path) {
            if (path == null || path.length() < 2 || path.charAt(0) != '$') {
                return null;
            }

            List<Byte> kinds = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<Integer> indexes = new ArrayList<>();
            int length = path.length();
            int i = 1;

            while (i < length) {
                if (!kinds.isEmpty() && kinds.get(kinds.size() - 1) == WILDCARD) {
                    return null; // [*] only as the last token
                }
                char c = path.charAt(i);
                if (c == '.') {
                    int start = ++i;
                    while (i < length && isNameChar(path.charAt(i))) {
                        i++;
                    }
                    if (i == start || (i < length && path.charAt(i) != '.' && path.charAt(i) != '[')) {
                        return null;
                    }
                    kinds.add(PROPERTY);
                    names.add(path.substring(start, i));
                    indexes.add(-1);
                } else if (c == '[') {
                    int close = path.indexOf(']', i);
                    if (close < 0) {
                        return null;
                    }
                    String inside = path.substring(i + 1, close);
                    if ("*".equals(inside)) {
                        kinds.add(WILDCARD);
                        names.add(null);
                        indexes.add(-1);
                    } else if (isIndex(inside)) {
                        kinds.add(INDEX);
                        names.add(null);
                        indexes.add(Integer.parseInt(inside));
                    } else {
                        return null;
                    }
                    i = close + 1;
                } else {
                    return null;
                }
            }

            if (kinds.isEmpty()) {
                return null;
            }

            byte[] k = new byte[kinds.size()];
            String[] n = new String[kinds.size()];
            int[] x = new int[kinds.size()];
            for (int j = 0; j < k.length; j++) {
                k[j] = kinds.get(j);
                n[j] = names.get(j);
                x[j] = indexes.get(j);
            }
            return new SimplePath(k, n, x);
        }

        private static boolean isNameChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
        }

        private static boolean isIndex(String text) {
            int length = text.length();
            if (length < 1 || length > 9 || (length > 1 && text.charAt(0) == '0')) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                char c = text.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            return true;
        }

        /**
         * The value jayway's {@code read(path)} would return (a container returned here stands
         * for its copy), null for PathNotFoundException, or {@link #FALLBACK}.
         */
        JsonElement lookup(JsonPathTypeAdapter<?>.Scope scope) {
            JsonElement current = scope.node;
            boolean javaNull = false; // stepped through a JSON null member (never in a copy)
            int last = mKinds.length - 1;

            for (int i = 0; i <= last; i++) {
                switch (mKinds[i]) {
                    case PROPERTY: {
                        if (javaNull || !(current instanceof JsonObject)) {
                            return null; // not a map: "Expected to find an object with property"
                        }
                        JsonElement value = ((JsonObject) current).get(mNames[i]);
                        if (value == null) {
                            return null; // missing property / no results
                        }
                        if (value.isJsonNull()) {
                            if (scope.copied) {
                                return null; // the copy has no such member
                            }
                            if (i == last) {
                                return JsonNull.INSTANCE; // toJsonTree(null)
                            }
                            javaNull = true; // unwrap(JsonNull) == null flows into the next token
                            current = null;
                            break;
                        }
                        if (i == last) {
                            if (value.isJsonPrimitive()) {
                                JsonPrimitive primitive = (JsonPrimitive) value;
                                // unwrap + toJsonTree: strings and booleans come back equal,
                                // numbers change boxed type.
                                return primitive.isNumber() ? scope.tree.rewrapNumber(primitive) : primitive;
                            }
                            return value;
                        }
                        current = value;
                        break;
                    }
                    case INDEX: {
                        if (javaNull || !(current instanceof JsonArray)) {
                            return null; // checkArrayModel
                        }
                        JsonArray array = (JsonArray) current;
                        int index = mIndexes[i];
                        if (index >= array.size()) {
                            return null; // IndexOutOfBounds swallowed, then no results
                        }
                        JsonElement element = array.get(index);
                        if (i == last) {
                            return element; // raw element (no unwrap): copy keeps its type
                        }
                        current = element;
                        break;
                    }
                    default: { // WILDCARD, always last
                        if (!javaNull && current instanceof JsonArray) {
                            return current; // one copied element per index, in order
                        }
                        if (!javaNull && current instanceof JsonObject) {
                            return FALLBACK; // member values go through unwrap: leave to jayway
                        }
                        return new JsonArray(); // neither map nor array: indefinite, empty
                    }
                }
            }

            return null;
        }
    }

    /** Reflection done once per model class. */
    private static final class TypeMeta {
        private static final Map<Class<?>, TypeMeta> sTypes = new ConcurrentHashMap<>();
        final Constructor<?> constructor;
        final FieldMeta[] fields;

        private TypeMeta(Constructor<?> constructor, FieldMeta[] fields) {
            this.constructor = constructor;
            this.fields = fields;
        }

        static TypeMeta of(Class<?> type) throws NoSuchMethodException {
            TypeMeta meta = sTypes.get(type);

            if (meta == null) {
                Constructor<?> constructor = type.getConstructor();
                List<FieldMeta> fields = new ArrayList<>();
                for (Field field : ReflectionHelper.getAllFields(type)) {
                    String[] paths = getJsonPath(field.getAnnotations());
                    if (paths != null) {
                        field.setAccessible(true);
                        fields.add(new FieldMeta(field, paths));
                    }
                }
                meta = new TypeMeta(constructor, fields.toArray(new FieldMeta[0]));
                sTypes.put(type, meta);
            }

            return meta;
        }
    }

    private static final class FieldMeta {
        final Field field;
        final Class<?> type;
        final boolean isJsonPathObj;
        final String[] paths;
        final SimplePath[] simplePaths;
        private volatile Class<?> mGenericParamType;
        private volatile boolean mGenericParamResolved;

        FieldMeta(Field field, String[] paths) {
            this.field = field;
            this.type = field.getType();
            this.isJsonPathObj = JsonPathObj.class.isAssignableFrom(type);
            this.paths = paths;
            this.simplePaths = new SimplePath[paths.length];
            for (int i = 0; i < paths.length; i++) {
                simplePaths[i] = SimplePath.compile(paths[i]);
            }
        }

        /** Resolved on first use like before, so an unusable signature still fails at that use. */
        Class<?> genericParamType() {
            if (!mGenericParamResolved) {
                mGenericParamType = ReflectionHelper.getGenericParamType(field);
                mGenericParamResolved = true;
            }
            return mGenericParamType;
        }
    }
}

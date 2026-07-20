package com.liskovsoft.youtubeapi.lounge.sender;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Parses the browserchannel framing used by the Lounge bind endpoint:<br/>
 * an endless sequence of {@code <decimal byte length>\n<JSON array>} chunks, where each
 * array contains {@code [index, ["<eventName>", <payload?>]]} messages.<br/>
 * The framing is honored properly (length-prefix loop) instead of line scanning.
 */
public class LoungeStreamParser {
    private static final String TAG = LoungeStreamParser.class.getSimpleName();
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    // A real length prefix is a handful of digits. Anything longer is junk.
    private static final int MAX_LENGTH_LINE = 1_024;

    public interface MessageListener {
        void onMessage(LoungeMessage message);
    }

    private LoungeStreamParser() {
    }

    /**
     * Reads framed chunks until EOF (normal long-poll termination) or stream error.<br/>
     * Blocking. EOF returns normally; IO problems (incl. cancellation) throw.
     */
    public static void readStream(InputStream in, MessageListener listener) throws IOException {
        while (true) {
            int length = readChunkLength(in);

            if (length < 0) { // EOF
                return;
            }

            byte[] chunk = readFully(in, length);

            if (chunk == null) { // EOF in the middle of a chunk
                Log.e(TAG, "Truncated chunk. Expected %s bytes.", length);
                return;
            }

            parseChunk(new String(chunk, UTF_8), listener);
        }
    }

    /**
     * Parses one JSON array chunk into messages. Malformed messages are skipped with a log.
     */
    public static void parseChunk(String json, MessageListener listener) {
        JsonElement root;

        try {
            root = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "Malformed chunk: %s", e.getMessage());
            return;
        }

        if (!root.isJsonArray()) {
            Log.e(TAG, "Chunk is not an array: %s", json);
            return;
        }

        for (JsonElement item : root.getAsJsonArray()) {
            LoungeMessage message = toMessage(item);

            if (message != null) {
                listener.onMessage(message);
            }
        }
    }

    @Nullable
    private static LoungeMessage toMessage(JsonElement item) {
        try {
            JsonArray msg = item.getAsJsonArray();
            int index = msg.get(0).getAsInt();
            JsonArray body = msg.get(1).getAsJsonArray();
            String type = body.get(0).getAsString();

            JsonObject payload = null;
            String stringArg = null;

            if (body.size() > 1) {
                JsonElement arg = body.get(1);

                if (arg.isJsonObject()) {
                    payload = arg.getAsJsonObject();
                } else if (arg.isJsonPrimitive()) {
                    stringArg = arg.getAsString();
                }
            }

            return new LoungeMessage(index, type, payload, stringArg);
        } catch (RuntimeException e) { // IllegalStateException, IndexOutOfBounds, NumberFormat...
            Log.e(TAG, "Skipping malformed message: %s", item);
            return null;
        }
    }

    /**
     * Reads the decimal length line. Skips empty lines and non-numeric junk
     * (there may be leading bytes before the first chunk).
     *
     * @return chunk byte length or -1 on EOF
     */
    private static int readChunkLength(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;

        while ((b = in.read()) != -1) {
            if (b == '\n') {
                String candidate = line.toString().trim();
                line.setLength(0);

                if (candidate.isEmpty()) {
                    continue;
                }

                if (isDigits(candidate)) {
                    try {
                        return Integer.parseInt(candidate);
                    } catch (NumberFormatException e) {
                        // fall through (absurdly long digit run)
                    }
                }

                Log.d(TAG, "Skipping junk before chunk length: %s", candidate);
            } else if (b != '\r') {
                if (line.length() < MAX_LENGTH_LINE) {
                    line.append((char) b);
                } else {
                    line.setLength(0); // runaway junk
                }
            }
        }

        return -1;
    }

    private static boolean isDigits(String candidate) {
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }

        return true;
    }

    @Nullable
    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;

        while (offset < length) {
            int read = in.read(result, offset, length - offset);

            if (read == -1) {
                return null;
            }

            offset += read;
        }

        return result;
    }
}

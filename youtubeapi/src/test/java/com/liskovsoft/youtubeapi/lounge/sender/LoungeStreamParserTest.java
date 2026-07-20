package com.liskovsoft.youtubeapi.lounge.sender;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Chunked browserchannel framing: {@code <byte length>\n<json array>} sequences.
 * Canned strings, no network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34) // newest SDK that runs on Java 17
public class LoungeStreamParserTest {
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    // Verbatim shape from the initial-bind response (ytcast test suite)
    private static final String BIND_CHUNK_JSON = "[[0,[\"c\",\"sid-foo-bar-baz\",\"\",8]]\n" +
            ",[1,[\"S\",\"gsessionid-foo-bar-baz\"]]\n" +
            ",[2,[\"loungeStatus\",{}]]\n" +
            ",[3,[\"playlistModified\",{}]]\n" +
            ",[4,[\"onAutoplayModeChanged\",{\"autoplayMode\":\"UNSUPPORTED\"}]]\n" +
            ",[5,[\"onPlaylistModeChanged\",{\"shuffleEnabled\":\"false\",\"loopEnabled\":\"false\"}]]\n" +
            "]";

    private List<LoungeMessage> mMessages;

    @Before
    public void setUp() {
        ShadowLog.stream = System.out; // catch Log class output

        mMessages = new ArrayList<>();
    }

    @Test
    public void testThatBindChunkIsParsed() throws IOException {
        readAll(frame(BIND_CHUNK_JSON));

        assertEquals("All messages parsed", 6, mMessages.size());

        LoungeMessage sid = mMessages.get(0);
        assertEquals("SID message type", "c", sid.getType());
        assertEquals("SID index", 0, sid.getIndex());
        assertEquals("SID value", "sid-foo-bar-baz", sid.getStringArg());
        assertNull("SID has no object payload", sid.getPayload());

        LoungeMessage gsession = mMessages.get(1);
        assertEquals("gsessionid message type", "S", gsession.getType());
        assertEquals("gsessionid value", "gsessionid-foo-bar-baz", gsession.getStringArg());

        LoungeMessage playlistMode = mMessages.get(5);
        assertEquals("Event type", "onPlaylistModeChanged", playlistMode.getType());
        assertEquals("Event index", 5, playlistMode.getIndex());
        assertNotNull("Event payload present", playlistMode.getPayload());
        assertEquals("Payload values are strings", "false",
                playlistMode.getPayload().get("shuffleEnabled").getAsString());
    }

    @Test
    public void testThatMultipleChunksAreParsed() throws IOException {
        String stream = frame(BIND_CHUNK_JSON)
                + frame("[[6,[\"noop\"]]]")
                + frame("[[7,[\"onStateChange\",{\"currentTime\":\"123.456\",\"duration\":\"300\",\"state\":\"1\"}]]]");

        readAll(stream);

        assertEquals("All chunks parsed", 8, mMessages.size());
        assertEquals("noop parsed", "noop", mMessages.get(6).getType());
        assertEquals("onStateChange parsed", "onStateChange", mMessages.get(7).getType());
        assertEquals("Time travels as seconds string", "123.456",
                mMessages.get(7).getPayload().get("currentTime").getAsString());
    }

    @Test
    public void testThatLeadingJunkIsSkipped() throws IOException {
        // There may be leading bytes/blank lines before the first chunk
        String stream = "\n\r\n)]}'\n" + frame("[[0,[\"noop\"]]]");

        readAll(stream);

        assertEquals("Message parsed despite junk", 1, mMessages.size());
        assertEquals("noop", mMessages.get(0).getType());
    }

    @Test
    public void testThatMalformedMessagesAreSkipped() throws IOException {
        // A bogus element must not kill the surrounding chunk
        String stream = frame("[[0,[\"noop\"]],\"bogus\",[2,[\"onVolumeChanged\",{\"volume\":\"85\",\"muted\":\"false\"}]]]");

        readAll(stream);

        assertEquals("Good messages survive a bad sibling", 2, mMessages.size());
        assertEquals("onVolumeChanged", mMessages.get(1).getType());
    }

    @Test
    public void testThatTruncatedChunkStopsCleanly() throws IOException {
        String json = "[[0,[\"noop\"]]]";
        // Announce more bytes than provided: EOF mid-chunk must return, not throw/hang
        String stream = (utf8Length(json) + 100) + "\n" + json;

        readAll(stream);

        assertEquals("Nothing delivered from truncated chunk", 0, mMessages.size());
    }

    @Test
    public void testThatLengthPrefixIsByteBased() throws IOException {
        // Multi-byte utf-8 in the payload: length prefix counts BYTES, not chars
        String json = "[[0,[\"nowPlaying\",{\"videoId\":\"тест-відео\"}]]]";

        readAll(frame(json));

        assertEquals(1, mMessages.size());
        assertEquals("тест-відео", mMessages.get(0).getPayload().get("videoId").getAsString());
    }

    private void readAll(String stream) throws IOException {
        LoungeStreamParser.readStream(
                new ByteArrayInputStream(stream.getBytes(UTF_8)),
                message -> mMessages.add(message));
    }

    private static String frame(String json) {
        return utf8Length(json) + "\n" + json;
    }

    private static int utf8Length(String json) {
        return json.getBytes(UTF_8).length;
    }
}

package com.liskovsoft.googlecommon.common.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * NEWTUBE(visitor): the NetPath `visitor=` fingerprint must follow the IDENTITY (visitorData
 * field 1), not the whole string - youtube.com/tv re-mints the string with a new timestamp and YNID
 * blob on every refresh while keeping the id. Synthetic visitorData only; no real identity here.
 */
public class VisitorFingerprintTest {
    private static final String ID = "AbC-12_xyZ9";

    @Test
    public void sameIdentityReMintedKeepsItsFingerprint() {
        String first = visitorData(ID, 1790342232L, "22.YT=first-ynid");
        String refreshed = visitorData(ID, 1790378232L, "22.YT=second-ynid");

        assertNotEquals("precondition: the strings differ, as they do across a refresh", first, refreshed);
        assertEquals(VisitorFingerprint.of(first), VisitorFingerprint.of(refreshed));
        assertEquals(ID, VisitorFingerprint.visitorId(first));
    }

    @Test
    public void differentIdentityChangesTheFingerprint() {
        long ts = 1790342232L;
        assertNotEquals(VisitorFingerprint.of(visitorData(ID, ts, "y")),
                VisitorFingerprint.of(visitorData("ZZZZZZZZZZZ", ts, "y")));
    }

    @Test
    public void urlEncodedCopyMatchesThePlainOne() {
        String plain = visitorData(ID, 1790342232L, "y");
        assertTrue("precondition: this sample carries padding", plain.endsWith("="));
        String encoded = plain.replace("=", "%3D");

        assertEquals(VisitorFingerprint.of(plain), VisitorFingerprint.of(encoded));
    }

    @Test
    public void fingerprintIsShortHexAndNeverTheRawId() {
        String fingerprint = VisitorFingerprint.of(visitorData(ID, 1790342232L, "y"));

        assertTrue(fingerprint, fingerprint.matches("[0-9a-f]{10}"));
        assertFalse(fingerprint.contains(ID));
    }

    @Test
    public void nonVisitorValuesFallBackToHashingTheWholeString() {
        assertEquals("none", VisitorFingerprint.of(null));
        assertEquals("none", VisitorFingerprint.of(""));
        assertNull(VisitorFingerprint.visitorId("not base64 !!"));
        assertNull("wrong leading field",
                VisitorFingerprint.visitorId(Base64.getUrlEncoder().encodeToString(new byte[]{0x12, 1, 'a'})));
        String fallback = VisitorFingerprint.of("not base64 !!");
        assertTrue(fallback, fallback.matches("[0-9a-f]{10}"));
        assertEquals(fallback, VisitorFingerprint.of("not base64 !!"));
    }

    /** field 1 = id, field 5 = varint timestamp, field 12 = YNID-like blob; URL-safe base64. */
    private static String visitorData(String id, long timestamp, String ynid) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
        out.write(0x0A);
        out.write(idBytes.length);
        out.write(idBytes, 0, idBytes.length);
        out.write(0x28);
        long value = timestamp;
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
        byte[] ynidBytes = ynid.getBytes(StandardCharsets.US_ASCII);
        out.write(0x62);
        out.write(ynidBytes.length);
        out.write(ynidBytes, 0, ynidBytes.length);
        return Base64.getUrlEncoder().encodeToString(out.toByteArray());
    }
}

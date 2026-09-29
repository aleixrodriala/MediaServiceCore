package com.liskovsoft.youtubeapi.common.helpers;

import static org.junit.Assert.assertEquals;

import org.chromium.net.CronetException;
import org.chromium.net.RequestFinishedInfo;
import org.chromium.net.UrlResponseInfo;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** NEWTUBE(warm-metrics): the Cronet breakdown appended to the {@code warm} NetPath line. */
public class MediaHostPreconnectMetricsTest {
    private static final long T0 = 1_800_000_000_000L;

    @Test
    public void aColdWarmShowsEachPhase() {
        Metrics metrics = new Metrics();
        metrics.dnsStart = at(0);
        metrics.dnsEnd = at(12);
        metrics.connectStart = at(12);
        metrics.connectEnd = at(610);
        metrics.sslStart = at(300);
        metrics.sslEnd = at(610);
        metrics.sendingEnd = at(611);
        metrics.responseStart = at(660);

        assertEquals(" dns=12 connect=598 ssl=310 wait=49 reused=n proto=h2",
                MediaHostPreconnect.describeMetrics(new Info(metrics, "h2")));
    }

    @Test
    public void aReusedSocketHasNoSetupPhases() {
        Metrics metrics = new Metrics();
        metrics.socketReused = true;
        metrics.sendingEnd = at(1);
        metrics.responseStart = at(40);

        assertEquals(" dns=-1 connect=-1 ssl=-1 wait=39 reused=y proto=h3",
                MediaHostPreconnect.describeMetrics(new Info(metrics, "h3")));
    }

    @Test
    public void missingOrOddValuesStayOneToken() {
        assertEquals(" metrics=none proto=?", MediaHostPreconnect.describeMetrics(new Info(null, null)));
        Metrics backwards = new Metrics();
        backwards.dnsStart = at(10);
        backwards.dnsEnd = at(5);
        assertEquals(" dns=-1 connect=-1 ssl=-1 wait=-1 reused=n proto=?",
                MediaHostPreconnect.describeMetrics(new Info(backwards, "")));
        assertEquals("quic_1+spdy_3", MediaHostPreconnect.protocolToken("quic 1+spdy 3"));
        assertEquals("aaaaaaaaaaaaaaaa", MediaHostPreconnect.protocolToken("aaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    private static Date at(long ms) {
        return new Date(T0 + ms);
    }

    private static final class Metrics extends RequestFinishedInfo.Metrics {
        Date dnsStart, dnsEnd, connectStart, connectEnd, sslStart, sslEnd, sendingEnd, responseStart;
        boolean socketReused;

        @Override public Date getRequestStart() { return null; }
        @Override public Date getDnsStart() { return dnsStart; }
        @Override public Date getDnsEnd() { return dnsEnd; }
        @Override public Date getConnectStart() { return connectStart; }
        @Override public Date getConnectEnd() { return connectEnd; }
        @Override public Date getSslStart() { return sslStart; }
        @Override public Date getSslEnd() { return sslEnd; }
        @Override public Date getSendingStart() { return null; }
        @Override public Date getSendingEnd() { return sendingEnd; }
        @Override public Date getPushStart() { return null; }
        @Override public Date getPushEnd() { return null; }
        @Override public Date getResponseStart() { return responseStart; }
        @Override public Date getRequestEnd() { return null; }
        @Override public boolean getSocketReused() { return socketReused; }
        @Override public Long getTtfbMs() { return null; }
        @Override public Long getTotalTimeMs() { return null; }
        @Override public Long getSentByteCount() { return null; }
        @Override public Long getReceivedByteCount() { return null; }
    }

    private static final class Info extends RequestFinishedInfo {
        private final Metrics mMetrics;
        private final String mProtocol;

        Info(Metrics metrics, String protocol) {
            mMetrics = metrics;
            mProtocol = protocol;
        }

        @Override public String getUrl() { return "https://rr1---sn-x.googlevideo.com/generate_204"; }
        @Override public Collection<Object> getAnnotations() { return Collections.emptyList(); }
        @Override public RequestFinishedInfo.Metrics getMetrics() { return mMetrics; }
        @Override public int getFinishedReason() { return SUCCEEDED; }
        @Override public CronetException getException() { return null; }

        @Override
        public UrlResponseInfo getResponseInfo() {
            return mProtocol == null ? null : new Response(mProtocol);
        }
    }

    private static final class Response extends UrlResponseInfo {
        private final String mProtocol;

        Response(String protocol) {
            mProtocol = protocol;
        }

        @Override public String getUrl() { return ""; }
        @Override public List<String> getUrlChain() { return Collections.emptyList(); }
        @Override public int getHttpStatusCode() { return 204; }
        @Override public String getHttpStatusText() { return ""; }
        @Override public List<Map.Entry<String, String>> getAllHeadersAsList() { return Collections.emptyList(); }
        @Override public Map<String, List<String>> getAllHeaders() { return Collections.emptyMap(); }
        @Override public boolean wasCached() { return false; }
        @Override public String getNegotiatedProtocol() { return mProtocol; }
        @Override public String getProxyServer() { return ""; }
        @Override public long getReceivedByteCount() { return 0; }
    }
}

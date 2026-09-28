package com.liskovsoft.googlecommon.common.helpers;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;

import okhttp3.Request;
import okio.Timeout;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * NEWTUBE(net): the /player walk's variant reports a refused connection as a network condition,
 * where the shared getter answers null (netbench audit C-11).
 */
public class RetrofitHelperConnectTest {
    @Test
    public void aRefusedConnectionIsANetworkCondition() {
        try {
            RetrofitHelper.getOrThrowOnConnect(new ThrowingCall(new ConnectException("refused")), true);
            fail("expected the network condition");
        } catch (IllegalStateException e) {
            assertTrue(e.getCause() instanceof ConnectException);
        }
        // The shared getter keeps upstream's answer.
        assertNull(RetrofitHelper.get(new ThrowingCall(new ConnectException("refused")), true));
    }

    @Test
    public void anAnswerIsReturnedAsBefore() {
        String body = "ok";
        assertSame(body, RetrofitHelper.getOrThrowOnConnect(new AnsweringCall(body), true));
    }

    private static final class ThrowingCall implements Call<String> {
        private final IOException mError;

        ThrowingCall(IOException error) {
            mError = error;
        }

        @Override public Response<String> execute() throws IOException { throw mError; }
        @Override public void enqueue(Callback<String> callback) { }
        @Override public boolean isExecuted() { return false; }
        @Override public void cancel() { }
        @Override public boolean isCanceled() { return false; }
        @Override public Call<String> clone() { return this; }
        @Override public Request request() { return new Request.Builder().url("https://youtubei.invalid/player").build(); }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }

    private static final class AnsweringCall implements Call<String> {
        private final String mBody;

        AnsweringCall(String body) {
            mBody = body;
        }

        @Override public Response<String> execute() { return Response.success(mBody); }
        @Override public void enqueue(Callback<String> callback) { }
        @Override public boolean isExecuted() { return false; }
        @Override public void cancel() { }
        @Override public boolean isCanceled() { return false; }
        @Override public Call<String> clone() { return this; }
        @Override public Request request() { return new Request.Builder().url("https://youtubei.invalid/player").build(); }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }
}

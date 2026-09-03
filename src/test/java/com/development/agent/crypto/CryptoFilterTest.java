package com.development.agent.crypto;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the SSE status stream. The stream endpoint is exempt from
 * application-layer crypto (an open-ended push channel cannot be buffered into a single
 * encrypted JSON envelope), so it must reach the JWT auth filter and be served as a real
 * {@code text/event-stream} instead of being rejected with {@code NO_CRYPTO_SESSION}.
 */
class CryptoFilterTest {

    private CryptoKeyStore keyStore;
    private CryptoFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        keyStore = mock(CryptoKeyStore.class);
        filter = new CryptoFilter(keyStore);
        when(keyStore.keyFor(any())).thenReturn(Optional.empty());
        // The filter is constructed directly (no Spring), so inject the enabled flag
        // that would normally come from app.crypto.enabled.
        var field = CryptoFilter.class.getDeclaredField("cryptoEnabled");
        field.setAccessible(true);
        field.setBoolean(filter, true);
    }

    @Test
    void streamPathWithoutCryptoSessionIsPassedThrough() throws Exception {
        HttpServletRequest req = request("/api/agent/jobs/abc-123/stream", DispatcherType.REQUEST, "GET");
        HttpServletResponse resp = response();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        // The chain must be invoked (the request reaches the JWT filter), not rejected.
        verify(chain).doFilter(req, resp);
        verify(resp, never()).setStatus(400);
    }

    @Test
    void nonStreamPathWithoutCryptoSessionIsRejected() throws Exception {
        HttpServletRequest req = request("/api/agent/chat", DispatcherType.REQUEST, "POST");
        HttpServletResponse resp = response();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        // Normal endpoints still require a valid crypto session.
        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(400);
    }

    @Test
    void asyncDispatchBypassesCryptoWrapping() throws Exception {
        HttpServletRequest req = request("/api/agent/jobs/abc-123/stream", DispatcherType.ASYNC, "GET");
        HttpServletResponse resp = response();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        // The async re-dispatch (which drives SSE completion) must never be re-encrypted.
        verify(chain).doFilter(req, resp);
        verify(resp, never()).setStatus(400);
    }

    @Test
    void errorDispatchBypassesCryptoWrapping() throws Exception {
        HttpServletRequest req = request("/api/agent/chat", DispatcherType.ERROR, "POST");
        HttpServletResponse resp = response();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        // Error dispatches (e.g. an SseEmitter timeout reaching the error dispatch)
        // must be served plaintext via the exception handler, never re-encrypted.
        verify(chain).doFilter(req, resp);
        verify(resp, never()).setStatus(400);
    }

    @Test
    void disabledCryptoPassesEverythingThrough() throws Exception {
        // Feature flag off: encryption is bypassed entirely, even for non-stream paths
        // and even without a crypto session (traffic secured by TLS/JWT instead).
        HttpServletRequest req = request("/api/agent/chat", DispatcherType.REQUEST, "POST");
        HttpServletResponse resp = response();
        FilterChain chain = mock(FilterChain.class);

        var field = CryptoFilter.class.getDeclaredField("cryptoEnabled");
        field.setAccessible(true);
        field.setBoolean(filter, false);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(resp, never()).setStatus(400);
    }

    private HttpServletRequest request(String path, DispatcherType dispatcherType, String method) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getServletPath()).thenReturn(path);
        when(req.getDispatcherType()).thenReturn(dispatcherType);
        when(req.getMethod()).thenReturn(method);
        return req;
    }

    private HttpServletResponse response() throws IOException {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int b) {
            }
        });
        return resp;
    }
}

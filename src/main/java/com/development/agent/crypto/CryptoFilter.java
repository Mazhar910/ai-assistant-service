package com.development.agent.crypto;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.development.agent.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Application-layer transport encryption. Runs before Spring Security so the JWT
 * filter and controllers see decrypted data, while everything the browser sends or
 * receives (bodies and the auth header) is AES-GCM ciphertext — keeping it hidden
 * from casual DevTools inspection.
 *
 * Plaintext-exempt paths: CORS preflight, the handshake endpoints (/api/crypto/**)
 * and the H2 console.
 */
@Component
@Order(-200)
public class CryptoFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(CryptoFilter.class);
    private static final String SESSION_HEADER = "X-Session-Id";
    private static final String AUTH_ENC_HEADER = "X-Auth-Enc";
    private static final String AUTH_HEADER = "Authorization";
    private static final String ENCRYPTED_FIELD = "data";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CryptoKeyStore keyStore;

    public CryptoFilter(CryptoKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request,
                         jakarta.servlet.ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        // Application-layer crypto only applies to the initial REQUEST. Async/error
        // re-dispatches (e.g. the async dispatch that drives SSE completion, or the
        // error dispatch) must never be re-encrypted or re-validated.
        if (httpReq.getDispatcherType() != jakarta.servlet.DispatcherType.REQUEST) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpReq.getServletPath();
        // Only the SSE job-status stream is exempt: an open-ended push channel that
        // cannot be buffered into a single encrypted JSON envelope. Match the exact
        // endpoint shape (/api/agent/jobs/{jobId}/stream) rather than any path suffix so
        // a future endpoint ending in "/stream" does not silently bypass crypto.
        boolean streamPath = path != null && path.matches("/api/agent/jobs/[^/]+/stream");
        if (HttpMethod.OPTIONS.matches(httpReq.getMethod())
                || path == null
                || path.startsWith("/api/crypto")
                || path.startsWith("/h2-console")
                || streamPath) {
            // SSE streams are open-ended push channels: they cannot be buffered into a
            // single encrypted JSON envelope (the response must stream incrementally with
            // a text/event-stream content type), so they are exempt from this filter and
            // are authenticated via the standard JWT Authorization header (JwtAuthFilter).
            chain.doFilter(request, response);
            return;
        }

        String sessionId = httpReq.getHeader(SESSION_HEADER);
        SecretKey key = keyStore.keyFor(sessionId).orElse(null);
        if (key == null) {
            log.warn("Request without a valid crypto session on {}", path);
            writePlainError(httpResp, 400, "NO_CRYPTO_SESSION",
                    "No active encryption session. Refresh the page to re-establish.");
            return;
        }

        try {
            // Decrypt the auth token header and expose it as the standard Authorization header.
            DecryptedRequestWrapper wrappedReq = new DecryptedRequestWrapper(httpReq);
            String authEnc = httpReq.getHeader(AUTH_ENC_HEADER);
            if (authEnc != null && !authEnc.isBlank()) {
                String token = CryptoKeyStore.aesDecrypt(key, authEnc);
                wrappedReq.setAuthToken(token);
            }

            // Decrypt the request body (POST/PUT/DELETE) into the plaintext for controllers.
            if (httpReq.getContentLength() != -1
                    && httpReq.getHeader("Content-Type") != null
                    && httpReq.getHeader("Content-Type").toLowerCase(java.util.Locale.ROOT).contains("json")) {
                String encryptedBody = readBody(httpReq, key);
                if (encryptedBody != null) {
                    wrappedReq.setPlainBody(encryptedBody);
                }
            }

            EncryptedResponseWrapper wrappedResp = new EncryptedResponseWrapper(httpResp, key);
            chain.doFilter(wrappedReq, wrappedResp);
            wrappedResp.encryptAndFlush();
        } catch (Exception e) {
            log.error("Crypto processing failed for {}: {}", path, e.getMessage());
            writePlainError(httpResp, 400, "CRYPTO_ERROR", "Failed to decrypt request.");
        }
    }

    private String readBody(HttpServletRequest req, SecretKey key) throws Exception {
        byte[] raw = req.getInputStream().readAllBytes();
        if (raw.length == 0) {
            return null;
        }
        String jsonBody = new String(raw, StandardCharsets.UTF_8);
        // The body is a JSON envelope {"data":"<iv>:<cipher>"}; extract the encrypted payload.
        String encrypted = jsonBody;
        try {
            JsonNode node = MAPPER.readTree(jsonBody);
            if (node.has(ENCRYPTED_FIELD)) {
                encrypted = node.get(ENCRYPTED_FIELD).asText();
            }
        } catch (Exception ignored) {
            // not a JSON envelope; fall through and treat the raw body as the payload
        }
        return CryptoKeyStore.aesDecrypt(key, encrypted);
    }

    private void writePlainError(HttpServletResponse resp, int status, String code, String message) throws IOException {
        // Unified ErrorResponse shape ({code, message, timestamp}) shared by all
        // filter-level failures (JwtAuthFilter, RateLimitFilter, security entry point).
        String body = MAPPER.writeValueAsString(new ErrorResponse(code, message, System.currentTimeMillis()));
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        resp.flushBuffer();
    }

    // ------------------------------------------------------------------ wrappers

    /** Provides decrypted body + Authorization header to downstream filters/controllers. */
    private static final class DecryptedRequestWrapper extends HttpServletRequestWrapper {
        private byte[] plainBody;
        private String authToken;

        DecryptedRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        void setPlainBody(String plainBody) {
            this.plainBody = plainBody.getBytes(StandardCharsets.UTF_8);
        }

        void setAuthToken(String token) {
            this.authToken = token;
        }

        @Override
        public String getHeader(String name) {
            if (AUTH_HEADER.equalsIgnoreCase(name) && authToken != null) {
                return "Bearer " + authToken;
            }
            return super.getHeader(name);
        }

        @Override
        public ServletInputStream getInputStream() {
            if (plainBody != null) {
                ByteArrayInputStream in = new ByteArrayInputStream(plainBody);
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return in.read();
                    }

                    @Override
                    public boolean isFinished() {
                        return in.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener readListener) {
                    }
                };
            }
            try {
                return super.getInputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Buffers the controller's response and encrypts it on completion. */
    private static final class EncryptedResponseWrapper extends HttpServletResponseWrapper {
        private final SecretKey key;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private ServletOutputStream output;
        private int status = 200;

        EncryptedResponseWrapper(HttpServletResponse response, SecretKey key) {
            super(response);
            this.key = key;
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
            super.setStatus(sc);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (output == null) {
                output = new ServletOutputStream() {
                    @Override
                    public void write(int b) {
                        buffer.write(b);
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                    }
                };
            }
            return output;
        }

        @Override
        public java.io.PrintWriter getWriter() {
            return new java.io.PrintWriter(new java.io.OutputStreamWriter(getOutputStream(), StandardCharsets.UTF_8));
        }

        void encryptAndFlush() throws Exception {
            String plain = buffer.toString(StandardCharsets.UTF_8);
            String encrypted = CryptoKeyStore.aesEncrypt(key, plain);
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put(ENCRYPTED_FIELD, encrypted);
            String outJson = envelope.toString();
            HttpServletResponse response = (HttpServletResponse) getResponse();
            response.setStatus(status);
            response.setContentType("application/json");
            byte[] outBytes = outJson.getBytes(StandardCharsets.UTF_8);
            response.setContentLength(outBytes.length);
            response.getOutputStream().write(outBytes);
            response.flushBuffer();
        }
    }
}

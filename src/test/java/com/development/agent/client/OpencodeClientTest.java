package com.development.agent.client;

import com.development.agent.exception.AiAgentException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the wire contract of {@link OpencodeClient} against a mock opencode
 * HTTP server: the request body must match the opencode server's expected schema.
 */
class OpencodeClientTest {

    private MockWebServer server;
    private OpencodeClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OpencodeClient();
        // Inject the mock server URL; in production this is set from ai.opencode.url.
        ReflectionTestUtils.setField(client, "baseUrl", server.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendMessageMustSendModelAsStructuredObjectNotString() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"info\":{\"id\":\"msg_1\"},\"parts\":[{\"type\":\"text\",\"text\":\"hello there\"}]}"));

        String reply = client.sendMessage("ses_x", "sys", "hi", "opencode/big-pickle");

        assertEquals("hello there", reply);

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/session/ses_x/message", request.getPath());

        String body = request.getBody().readUtf8();
        // The model must be the structured {providerID, modelID} object the opencode
        // API accepts; a flat string causes an HTTP 400 InvalidRequestError.
        assertTrue(body.contains("\"model\":{\"providerID\":\"opencode\",\"modelID\":\"big-pickle\"}"),
                "model was not serialized as a structured object: " + body);
        // sanity: still a text part + system
        assertTrue(body.contains("\"type\":\"text\""));
        assertTrue(body.contains("\"system\":\"sys\""));
    }

    @Test
    void sendMessageWithoutModelOmitsModelField() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"info\":{\"id\":\"msg_1\"},\"parts\":[{\"type\":\"text\",\"text\":\"ok\"}]}"));

        client.sendMessage("ses_x", "sys", "hi");

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertFalse(body.contains("model"), "model field should be omitted when not provided: " + body);
    }

    @Test
    void upstreamNonSuccessThrowsAiAgentExceptionWithStatusCode() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"name\":\"InvalidRequestError\"}"));

        AiAgentException ex = assertThrows(AiAgentException.class,
                () -> client.sendMessage("ses_x", "sys", "hi", "opencode/big-pickle"));

        assertEquals(400, ex.getStatus());
        assertEquals("UPSTREAM_ERROR", ex.getCode());
    }

    @Test
    void createSessionParsesSessionIdFromResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"ses_new123\"}"));

        String id = client.createSession();

        assertEquals("ses_new123", id);
        RecordedRequest request = server.takeRequest();
        assertEquals("/session", request.getPath());
    }
}

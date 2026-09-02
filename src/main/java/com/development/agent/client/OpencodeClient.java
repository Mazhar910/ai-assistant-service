package com.development.agent.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.development.agent.exception.AiAgentException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class OpencodeClient {

    private static final Logger log = LoggerFactory.getLogger(OpencodeClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.opencode.url}")
    private String baseUrl;

    public OpencodeClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String createSession() {
        String url = baseUrl + "/session";
        log.info("Creating opencode session at {}", url);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("{}", JSON))
                .build();
        String body = execute(request, "create session");
        try {
            JsonNode json = objectMapper.readTree(body);
            String sessionId = json.path("id").asText();
            log.info("Opencode session created successfully: {}", sessionId);
            return sessionId;
        } catch (IOException e) {
            throw new AiAgentException("Could not parse opencode session response: " + e.getMessage(), "UPSTREAM_ERROR");
        }
    }

    public String sendMessage(String sessionId, String systemPrompt, String userText) {
        return sendMessage(sessionId, systemPrompt, userText, null);
    }

    /**
     * Sends a message to an opencode session. When {@code model} is non-null it is
     * passed in the request body so the opencode server routes the turn to that
     * specific model instead of falling back to its default. Used by the model
     * fallback chain in {@code AiAgentService}.
     */
    public String sendMessage(String sessionId, String systemPrompt, String userText, String model) {
        String url = baseUrl + "/session/" + sessionId + "/message";
        log.info("Sending message to opencode session {} (model={}): {}", sessionId, model, userText);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parts", List.of(Map.of("type", "text", "text", userText)));
        payload.put("system", systemPrompt);
        if (model != null && !model.isBlank()) {
            payload.put("model", model);
        }

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new AiAgentException("Could not build opencode request payload: " + e.getMessage(), "INTERNAL_ERROR");
        }
        log.debug("Opencode message request body: {}", jsonPayload);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonPayload, JSON))
                .build();
        String body = execute(request, "send message to session " + sessionId);
        log.debug("Opencode message response body: {}", body);

        return extractAssistantText(body, sessionId);
    }

    /**
     * Ingests an existing conversation transcript into an OpenCode session without
     * generating a new assistant response ({@code noReply=true}). Used to restore
     * model context in a freshly created session after the original session was lost
     * or became unusable. The transcript is derived from the application's own
     * persisted history so that model context is not lost with the upstream session.
     */
    public void replayHistory(String sessionId, String systemPrompt, List<MapMessage> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        StringBuilder transcript = new StringBuilder();
        for (MapMessage m : history) {
            transcript.append(roleLabel(m.role())).append(": ").append(m.content()).append(System.lineSeparator());
        }
        String prompt = "Continuing an existing conversation. This is the conversation history so far:\n"
                + transcript
                + "\nDo not respond to or repeat this history. Only retain it as context for the next message.";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parts", List.of(Map.of("type", "text", "text", prompt)));
        payload.put("system", systemPrompt);
        payload.put("noReply", true);

        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            log.info("Replaying {} prior messages into opencode session {} (noReply)",
                    history.size(), sessionId);
            log.debug("Opencode replay request body: {}", jsonPayload);
            Request request = new Request.Builder()
                    .url(baseUrl + "/session/" + sessionId + "/message")
                    .post(RequestBody.create(jsonPayload, JSON))
                    .build();
            execute(request, "replay history into session " + sessionId);
        } catch (JsonProcessingException e) {
            throw new AiAgentException("Could not build opencode replay payload: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private static String roleLabel(String role) {
        String r = role == null ? "" : role.trim().toLowerCase();
        if ("assistant".equals(r) || "ai".equals(r) || "model".equals(r)) {
            return "ASSISTANT";
        }
        return "USER";
    }

    public List<MapMessage> getMessages(String sessionId) {
        String url = baseUrl + "/session/" + sessionId + "/message";
        log.info("Fetching messages for opencode session {}", sessionId);
        Request request = new Request.Builder().url(url).get().build();
        String body = execute(request, "fetch messages of session " + sessionId);
        try {
            JsonNode root = objectMapper.readTree(body);
            List<MapMessage> messages = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    String role = "assistant".equals(item.path("info").path("role").asText("assistant")) ? "assistant" : "user";
                    String content = extractTextFromParts(item.path("parts"));
                    if (!content.isEmpty()) {
                        messages.add(new MapMessage(role, content));
                    }
                }
            }
            log.info("Retrieved {} messages from session {}", messages.size(), sessionId);
            return messages;
        } catch (IOException e) {
            throw new AiAgentException("Could not parse opencode history response: " + e.getMessage(), "UPSTREAM_ERROR");
        }
    }

    public boolean deleteSession(String sessionId) {
        String url = baseUrl + "/session/" + sessionId;
        log.info("Deleting opencode session {}", sessionId);
        Request request = new Request.Builder().url(url).delete().build();
        String body = execute(request, "delete session " + sessionId);
        return parseBoolean(body);
    }

    private String execute(Request request, String action) {
        long start = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            log.debug("{}: status={}, time={} ms, body={}",
                    action, response.code(), System.currentTimeMillis() - start, body);
            if (!response.isSuccessful()) {
                throw new AiAgentException("Opencode failed to " + action + " (HTTP "
                        + response.code() + "): " + body, "UPSTREAM_ERROR", response.code());
            }
            return body;
        } catch (AiAgentException e) {
            throw e;
        } catch (IOException e) {
            log.error("Network error while trying to {}: {}", action, e.getMessage(), e);
            throw new AiAgentException("Failed to reach opencode server: " + e.getMessage(), "UPSTREAM_ERROR");
        }
    }

    private String extractAssistantText(String responseJson, String sessionId) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String text = extractTextFromParts(root.path("parts"));
            log.info("Received {} from opencode session {}", text, sessionId);
            return text;
        } catch (IOException e) {
            throw new AiAgentException("Could not parse opencode response: " + e.getMessage(), "UPSTREAM_ERROR");
        }
    }

    private String extractTextFromParts(JsonNode parts) {
        StringBuilder sb = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text".equals(part.path("type").asText())) {
                    sb.append(part.path("text").asText());
                    sb.append(System.lineSeparator());
                }
            }
        }
        while (sb.length() > 0
                && (sb.charAt(sb.length() - 1) == '\n' || sb.charAt(sb.length() - 1) == '\r')) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private boolean parseBoolean(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.isBoolean()) {
                return node.asBoolean();
            }
        } catch (IOException ignored) {
            // fall through to string parsing
        }
        return Boolean.parseBoolean(body.trim());
    }

    public record MapMessage(String role, String content) {
    }
}
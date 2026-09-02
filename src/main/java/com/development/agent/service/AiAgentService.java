package com.development.agent.service;

import com.development.agent.client.OpencodeClient;
import com.development.agent.exception.AiAgentException;
import com.development.agent.entity.ChatMessageEntity;
import com.development.agent.entity.Conversation;
import com.development.agent.entity.TokenUsage;
import com.development.agent.entity.User;
import com.development.agent.model.ChatMessage;
import com.development.agent.model.ChatRequest;
import com.development.agent.model.ChatResponse;
import com.development.agent.repository.ChatMessageRepository;
import com.development.agent.repository.ConversationRepository;
import com.development.agent.repository.TokenUsageRepository;
import com.development.agent.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);

    private static final int SUGGESTIONS_PER_VIEW = 4;
    private static final int SUGGESTIONS_BATCH_SIZE = 16;

    private static final String SYSTEM_PROMPT =
            "You are a general-purpose AI assistant that answers questions on any topic accurately and helpfully. " +
                    "Use the conversation history to understand context. Interpret short contextual responses " +
                    "such as 'yes', 'no', 'sure', 'okay', 'do it', 'continue', or 'the second one' as follow-ups " +
                    "to the previous turns rather than standalone queries. " +
                    "If context genuinely cannot determine the user's intent, ask a brief clarifying question; " +
                    "otherwise answer directly and reasonably. " +
                    "Never fabricate missing context. Adapt length to the request: be concise for simple questions, " +
                    "and structured/detailed for complex requests.";
    private final OpencodeClient opencodeClient;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<CachedSuggestions> suggestionsCache = new AtomicReference<>(null);

    @Value("${app.suggestions-cache-ttl-seconds:600}")
    private long suggestionsCacheTtlSeconds;

    /**
     * Comma-separated ordered list of model ids to try for each chat turn, most
     * capable first (e.g. {@code opencode/big-pickle,opencode/ling-3.0-flash-fin-free}).
     * If a model replies with a rate-limit (HTTP 429) error we shift automatically
     * to the next entry in the chain. Configured via {@code ai.opencode.models}.
     */
    @Value("${ai.opencode.models:}")
    private String modelChainConfig;

    /** Cached parsed model chain (see {@link #resolveModelChain()}). */
    private volatile List<String> modelChain;

    /**
     * Rate-limit fallback cooldown (seconds). When a model returns HTTP 429 we remember
     * not to try it (or anything more capable) again for this window, so limit-shift is
     * sticky across turns instead of re-trying the exhausted model on every message.
     */
    @Value("${ai.opencode.rate-limit-cooldown-seconds:300}")
    private long rateLimitCooldownSeconds;

    /** Index (in the model chain) below which we skip while inside the cooldown window. */
    private final AtomicReference<ModelFloor> rateLimitFloor = new AtomicReference<>(null);

    /** The lowest (least capable) model index that recently hit a rate limit. */
    private record ModelFloor(int index, long expiresAt) {
    }

    public AiAgentService(OpencodeClient opencodeClient,
                          ConversationRepository conversationRepository,
                          ChatMessageRepository chatMessageRepository,
                          TokenUsageRepository tokenUsageRepository,
                          UserRepository userRepository,
                          PlatformTransactionManager transactionManager) {
        this.opencodeClient = opencodeClient;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Synchronous chat path (default UX). Runs the whole turn on the request thread.
     * For high-concurrency scale deployments, use the async job path instead
     * (see ChatJobService) so request threads are not blocked on the upstream AI.
     */
    @Transactional
    public ChatResponse chat(ChatRequest request, User user) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }
        long start = System.currentTimeMillis();
        String sessionId = resolveOrCreateConversation(request, user);
        String reply = sendToOpencode(sessionId, request.getMessage());
        int inputTokens = estimateTokens(request.getMessage());
        int outputTokens = estimateTokens(reply);
        persistTurn(user, sessionId, request.getMessage(), reply, inputTokens, outputTokens, start);
        return new ChatResponse(sessionId, reply, LocalDateTime.now());
    }

    /**
     * Async job path. Called on a worker thread. Persists a chat turn and returns the
     * assistant reply; on failure the job is marked FAILED with a retryable code.
     * The upstream network call runs outside any DB transaction; only short, scoped
     * persistence operations are transactional (avoids holding DB connections for the
     * duration of AI inference).
     */
    public String executeChatJob(Long userId, String conversationId, String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }
        long start = System.currentTimeMillis();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        String resolvedId = conversationId;
        if (resolvedId == null || resolvedId.isBlank()) {
            resolvedId = createNewConversation(message, user);
        } else {
            ensureOwnership(resolvedId, user);
        }
        // Network call outside a transaction (avoids holding a DB connection during inference)
        String reply = sendToOpencode(resolvedId, message);
        int inputTokens = estimateTokens(message);
        int outputTokens = estimateTokens(reply);
        // Short, scoped transaction for persistence
        persistTurn(user, resolvedId, message, reply, inputTokens, outputTokens, start);
        return reply;
    }

    /**
     * AI-generated welcome suggestions. A batch of suggestions is generated in a single
     * upstream call approx. once per cache window; on each request a different rotating
     * subset of {@value #SUGGESTIONS_PER_VIEW} is returned. This keeps reloads fresh while
     * only paying for one OpenCode call per window instead of one per reload.
     */
    public List<String> generateSuggestions(User user) {
        return nextSuggestions();
    }

    private List<String> nextSuggestions() {
        CachedSuggestions cached = suggestionsCache.get();
        if (cached == null || cached.expiresAt() <= System.currentTimeMillis()) {
            List<String> batch = generateSuggestionsBatch();
            if (batch == null || batch.size() < SUGGESTIONS_PER_VIEW) {
                batch = defaultBatch();
            }
            long ttlMs = Math.max(1, suggestionsCacheTtlSeconds) * 1000L;
            suggestionsCache.set(new CachedSuggestions(batch, System.currentTimeMillis() + ttlMs));
            cached = suggestionsCache.get();
        }

        List<String> batch = cached.suggestions();
        int start = cached.nextStart();
        List<String> view = pick(batch, start, SUGGESTIONS_PER_VIEW);

        // Advance the rotation pointer so the next request returns a different subset.
        CachedSuggestions existing = suggestionsCache.get();
        if (existing != null) {
            int next = (start + SUGGESTIONS_PER_VIEW) % Math.max(1, batch.size());
            suggestionsCache.set(new CachedSuggestions(batch, existing.expiresAt(), next));
        }
        return view;
    }

    private List<String> pick(List<String> batch, int start, int count) {
        List<String> out = new ArrayList<>();
        int n = batch.size();
        for (int i = 0; i < count && i < n; i++) {
            out.add(batch.get((start + i) % n));
        }
        return out;
    }

    private List<String> generateSuggestionsBatch() {
        String sessionId = opencodeClient.createSession();
        try {
            String prompt = "Generate exactly " + SUGGESTIONS_BATCH_SIZE +
                    " short, varied, one-line prompts a user could click to start a chat with an AI assistant. " +
                    "Cover lots of different themes: coding, debugging, explanation, writing, planning, everyday help, " +
                    "creative tasks, learning, and productivity. Each should be concrete and useful. " +
                    "Respond with ONLY a JSON array of " + SUGGESTIONS_BATCH_SIZE +
                    " strings, e.g. [\"...\",\"...\"] and nothing else.";
            String reply = opencodeClient.sendMessage(sessionId, SYSTEM_PROMPT, prompt);
            List<String> parsed = parseSuggestions(reply);
            if (parsed != null && parsed.size() >= SUGGESTIONS_PER_VIEW) {
                return parsed;
            }
            log.warn("Could not parse AI suggestions, using fallback; raw reply: {}", reply);
        } finally {
            try {
                opencodeClient.deleteSession(sessionId);
            } catch (Exception e) {
                log.debug("Could not delete Suggestion session: {}", e.getMessage());
            }
        }
        return null;
    }

    private List<String> parseSuggestions(String reply) {
        if (reply == null) {
            return null;
        }
        int start = reply.indexOf('[');
        int end = reply.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = objectMapper.readTree(reply.substring(start, end + 1));
                List<String> out = new ArrayList<>();
                for (JsonNode item : node) {
                    if (item.isTextual()) {
                        out.add(item.asText().trim());
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (JsonProcessingException e) {
                log.debug("Could not parse suggestions JSON: {}", e.getMessage());
            }
        }
        // Fallback: split on newlines, strip numbering
        List<String> out = new ArrayList<>();
        for (String line : reply.split("\n")) {
            String item = line.replaceAll("^[\\\\d\\\\.\\-\\s]+", "").replaceAll("^[\\\"']|[\\\"']$", "").trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private List<String> defaultBatch() {
        return List.of(
                "Write a Python function to reverse a string",
                "Explain quantum computing in simple terms",
                "Draft a professional email requesting a day off",
                "Suggest a healthy weekly meal plan",
                "Explain the difference between TCP and UDP",
                "Write a SQL query to find duplicate rows",
                "Summarize the main ideas of the last decade of AI research",
                "Help me plan a focused study schedule for an exam",
                "Generate a creative short story opening about time travel",
                "Refactor this code snippet to be more readable",
                "Explain how a database index works under the hood",
                "Draft a polite follow-up email for an unread message",
                "What are some good habits for productive deep work?",
                "Write a Dockerfile for a small Node.js app",
                "Explain recursion with a simple, concrete example",
                "Suggest a budget-friendly weekly grocery list"
        );
    }

    private record CachedSuggestions(List<String> suggestions, long expiresAt, int nextStart) {

        CachedSuggestions(List<String> suggestions, long expiresAt) {
            this(suggestions, expiresAt, 0);
        }
    }

    private String resolveOrCreateConversation(ChatRequest request, User user) {
        String sessionId = request.getConversationId();
        if (sessionId == null || sessionId.isBlank()) {
            return createNewConversation(request.getMessage(), user);
        }
        ensureOwnership(sessionId, user);
        return sessionId;
    }

    private String createNewConversation(String firstMessage, User user) {
        String opencodeSessionId = opencodeClient.createSession();
        String sessionId = UUID.randomUUID().toString();
        String title = truncateTitle(firstMessage);
        Conversation conversation = new Conversation(sessionId, user, title);
        conversation.setOpencodeSessionId(opencodeSessionId);
        conversationRepository.save(conversation);
        log.info("Created conversation {} with opencode session {} for user {}",
                sessionId, opencodeSessionId, user.getUsername());
        return sessionId;
    }

    private void ensureOwnership(String sessionId, User user) {
        Conversation conversation = conversationRepository.findByExternalId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Access denied to this conversation");
        }
    }

    private String sendToOpencode(String sessionId, String message) {
        Conversation conversation = conversationRepository.findByExternalId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
        String opencodeSessionId = conversation.getOpencodeSessionId();
        int turn = countUserTurns(conversation) + 1;
        log.info("Chat turn {} for conversation {} using opencode session {}",
                turn, sessionId, opencodeSessionId);

        try {
            return sendWithModelChain(opencodeSessionId, message,
                    "Chat turn " + turn + " for conversation " + sessionId);
        } catch (AiAgentException e) {
            // The OpenCode session is unreachable or unusable (e.g. wedged, timed out, or
            // the server was restarted). Recover by replaying the application's persisted
            // history into a fresh OpenCode session so model context is preserved, then retry.
            log.warn("Opencode session {} failed for conversation {} ({}). Recovering by " +
                            "replaying {} persisted messages into a fresh session.",
                    opencodeSessionId, sessionId, e.getCode(), countUserTurns(conversation), e);
            return recoverSession(conversation, message, turn);
        }
    }

    /**
     * Sends a turn across the ordered model chain, most capable first. Starting with
     * the primary model, if it returns a rate-limit error (HTTP 429) we log it and
     * automatically shift to the next, lower-capability model in the chain. The first
     * model that responds without a rate-limit error wins.
     *
     * <p>The shift is sticky: models at or above the rate-limited index are skipped for
     * {@code ai.opencode.rate-limit-cooldown-seconds} so subsequent turns don't re-hit the
     * exhausted model on every message. If every model is rate-limited a {@code 429}
     * {@link AiAgentException} is rethrown so the caller can surface it to the user.
     */
    private String sendWithModelChain(String opencodeSessionId, String message, String actionLabel) {
        List<String> models = resolveModelChain();

        if (models.isEmpty()) {
            // No chain configured: rely on the opencode server's default model.
            log.info("{}: no model chain configured, using opencode default", actionLabel);
            return opencodeClient.sendMessage(opencodeSessionId, SYSTEM_PROMPT, message);
        }

        int startIndex = currentStartIndex(models.size());

        for (int i = startIndex; i < models.size(); i++) {
            String model = models.get(i);
            try {
                String reply = opencodeClient.sendMessage(opencodeSessionId, SYSTEM_PROMPT, message, model);
                log.info("{} completed via model {}", actionLabel, model);
                return reply;
            } catch (AiAgentException e) {
                boolean isRateLimit = e.getStatus() == 429;
                log.warn("{} failed with model {}: HTTP {} ({}). {}",
                        actionLabel, model, e.getStatus(), e.getCode(),
                        isRateLimit ? "Shifting to the next available model." : "");
                if (!isRateLimit) {
                    throw e;
                }
                markRateLimited(i);
            }
        }
        throw new AiAgentException("All available models are rate-limited. Please try again later.",
                "RATE_LIMITED", 429);
    }

    /**
     * Returns where the chain should start for this turn. If the highest rate-limited
     * model index is still inside its cooldown window, we start below (less capable than)
     * it; otherwise we start at the most capable model.
     */
    private int currentStartIndex(int modelCount) {
        ModelFloor floor = rateLimitFloor.get();
        long now = System.currentTimeMillis();
        if (floor == null) {
            return 0;
        }
        if (floor.expiresAt() <= now) {
            rateLimitFloor.compareAndSet(floor, null);
            return 0;
        }
        // Skip the rate-limited model and everything more capable than it.
        return Math.min(floor.index() + (floor.index() < modelCount - 1 ? 1 : 0), modelCount);
    }

    /**
     * Records that the model at {@code index} hit a rate limit, establishing (or
     * extending) the sticky cooldown floor. A lower (later) model overrides a higher one
     * so we never step back up to a rate-limited model while its limit persists.
     */
    private void markRateLimited(int index) {
        long expiresAt = System.currentTimeMillis() + Math.max(1, rateLimitCooldownSeconds) * 1000L;
        ModelFloor candidate = new ModelFloor(index, expiresAt);
        ModelFloor current = rateLimitFloor.get();
        if (current == null || index >= current.index()) {
            rateLimitFloor.set(candidate);
        } else if (current.expiresAt() <= System.currentTimeMillis()) {
            rateLimitFloor.set(candidate);
        }
    }

    /**
     * Parses and caches the ordered model chain from {@code ai.opencode.models}
     * (comma-separated). Blank entries are dropped.
     */
    private List<String> resolveModelChain() {
        List<String> chain = modelChain;
        if (chain == null) {
            List<String> parsed = new ArrayList<>();
            if (modelChainConfig != null) {
                for (String item : modelChainConfig.split(",")) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        parsed.add(trimmed);
                    }
                }
            }
            chain = List.copyOf(parsed);
            modelChain = chain;
        }
        return chain;
    }

    /**
     * Recovers from an unusable OpenCode session: creates a fresh OpenCode session,
     * replays the persisted application history into it, updates the mapping, and
     * resends the current user message. Application history is never modified/corrupted.
     */
    private String recoverSession(Conversation conversation, String message, int turn) {
        List<OpencodeClient.MapMessage> history = loadHistory(conversation);
        String freshOpencodeSessionId = opencodeClient.createSession();
        try {
            opencodeClient.replayHistory(freshOpencodeSessionId, SYSTEM_PROMPT, history);
            String reply = sendWithModelChain(freshOpencodeSessionId, message,
                    "Recovered turn " + turn + " for conversation " + conversation.getExternalId());
            conversation.setOpencodeSessionId(freshOpencodeSessionId);
            conversationRepository.save(conversation);
            log.info("Recovered conversation {} onto fresh opencode session {} (replayed {} messages) " +
                            "and completed turn {}",
                    conversation.getExternalId(), freshOpencodeSessionId, history.size(), turn);
            return reply;
        } catch (RuntimeException e2) {
            try {
                opencodeClient.deleteSession(freshOpencodeSessionId);
            } catch (Exception ignored) {
                log.debug("Could not clean up recovered opencode session: {}", ignored.getMessage());
            }
            throw e2;
        }
    }

    private int countUserTurns(Conversation conversation) {
        return (int) chatMessageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .filter(m -> "user".equalsIgnoreCase(m.getRole()))
                .count();
    }

    private List<OpencodeClient.MapMessage> loadHistory(Conversation conversation) {
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .map(m -> new OpencodeClient.MapMessage(m.getRole(), m.getContent()))
                .toList();
    }

    private void persistTurn(User user, String sessionId, String message, String reply,
                             int inputTokens, int outputTokens, long start) {
        // TransactionTemplate: safe to call from both the sync @Transactional path
        // (joins the existing tx) and the async worker path (starts its own short tx).
        transactionTemplate.executeWithoutResult(status -> {
            Conversation conversation = conversationRepository.findByExternalId(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));

            ChatMessageEntity userMsg = new ChatMessageEntity(conversation, "user", message);
            chatMessageRepository.save(userMsg);

            String modelName = "opencode-default";
            ChatMessageEntity assistantMsg = new ChatMessageEntity(conversation, "assistant", reply);
            assistantMsg.setTokensUsed(outputTokens);
            assistantMsg.setModelName(modelName);
            chatMessageRepository.save(assistantMsg);

            TokenUsage tokenUsage = new TokenUsage(user, inputTokens, outputTokens, modelName);
            tokenUsageRepository.save(tokenUsage);

            // Use the first user message as the conversation title if it is still a placeholder.
            if ("New chat".equals(conversation.getTitle())) {
                conversation.setTitle(truncateTitle(message));
            }

            conversation.touch();
            conversationRepository.save(conversation);
        });

        log.info("Chat turn persisted for conversation {} in {} ms (tokens: ~{}/{})",
                sessionId, System.currentTimeMillis() - start, inputTokens, outputTokens);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getConversation(String conversationId, User user) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Conversation id must not be empty");
        }

        Conversation conversation = conversationRepository.findByExternalId(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));

        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Access denied to this conversation");
        }

        log.info("Fetching conversation history for session {}", conversationId);
        return chatMessageRepository.findByConversationExternalIdOrderByCreatedAtAsc(conversationId)
                .stream()
                .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                .toList();
    }

    @Transactional
    public boolean clearConversation(String conversationId, User user) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Conversation id must not be empty");
        }

        Conversation conversation = conversationRepository.findByExternalId(conversationId)
                .orElse(null);

        if (conversation == null || !conversation.getUser().getId().equals(user.getId())) {
            return false;
        }

        // Delete from OpenCode
        boolean cleared = false;
        try {
            cleared = opencodeClient.deleteSession(conversation.getOpencodeSessionId());
        } catch (Exception e) {
            log.warn("Failed to delete OpenCode session: {}", e.getMessage());
        }

        // Delete from database
        chatMessageRepository.deleteByConversationExternalId(conversationId);
        conversationRepository.deleteByExternalId(conversationId);

        log.info("Cleared conversation for session {}: cleared={}", conversationId, cleared);
        return true;
    }

    @Transactional
    public boolean deleteMessage(String conversationId, Long messageId, User user) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Conversation id must not be empty");
        }
        if (messageId == null) {
            throw new IllegalArgumentException("Message id must not be empty");
        }

        Conversation conversation = conversationRepository.findByExternalId(conversationId)
                .orElse(null);

        if (conversation == null || !conversation.getUser().getId().equals(user.getId())) {
            return false;
        }

        ChatMessageEntity message = chatMessageRepository.findById(messageId).orElse(null);

        if (message == null || !message.getConversation().getId().equals(conversation.getId())) {
            return false;
        }

        chatMessageRepository.delete(message);

        log.info("Deleted message {} from conversation {}", messageId, conversationId);
        return true;
    }

    @Transactional
    public String newConversation(User user) {
        String opencodeSessionId = opencodeClient.createSession();
        String externalId = UUID.randomUUID().toString();
        Conversation conversation = new Conversation(externalId, user, "New chat");
        conversation.setOpencodeSessionId(opencodeSessionId);
        conversationRepository.save(conversation);
        log.info("Started new conversation {} with opencode session {} for user {}",
                externalId, opencodeSessionId, user.getUsername());
        return externalId;
    }

    @Transactional(readOnly = true)
    public List<ChatSessionInfo> getUserSessions(User user) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId())
                .stream()
                .map(c -> new ChatSessionInfo(c.getExternalId(), c.getTitle(), c.getUpdatedAt()))
                .toList();
    }

    private String truncateTitle(String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > 40 ? clean.substring(0, 40) + "..." : clean;
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        return Math.max(1, text.length() / 4);
    }

    public record ChatSessionInfo(String conversationId, String title, LocalDateTime updatedAt) {
    }
}

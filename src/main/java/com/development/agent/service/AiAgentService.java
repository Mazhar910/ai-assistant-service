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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);

    private static final int SUGGESTIONS_PER_VIEW = 4;
    private static final int SUGGESTIONS_BATCH_SIZE = 16;
    /** Upper bound on messages served by the conversation history endpoint. */
    private static final int RECENT_CONVERSATION_MESSAGES = 500;

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
    private final AtomicBoolean suggestionsRefreshInFlight = new AtomicBoolean(false);

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
     * The upstream AI call deliberately happens OUTSIDE any DB transaction: persistence
     * is scoped to {@link #persistTurn} via TransactionTemplate so no DB connection is
     * held open during AI inference. For high-concurrency scale deployments, use the
     * async job path instead (see ChatJobService) so request threads are not blocked.
     */
    public ChatResponse chat(ChatRequest request, User user) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }
        long start = System.currentTimeMillis();
        String sessionId = resolveOrCreateConversation(request, user);
        ModelReply reply = sendToOpencode(sessionId, request.getMessage());
        int inputTokens = estimateTokens(request.getMessage());
        int outputTokens = estimateTokens(reply.reply());
        persistTurn(user, sessionId, request.getMessage(), reply.reply(), reply.model(),
                inputTokens, outputTokens, start);
        return new ChatResponse(sessionId, reply.reply(), LocalDateTime.now(), reply.model());
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
        String resolvedId = resolveConversationForJob(user, conversationId, message);
        // Network call outside a transaction (avoids holding a DB connection during inference)
        ModelReply reply = sendToOpencode(resolvedId, message);
        int inputTokens = estimateTokens(message);
        int outputTokens = estimateTokens(reply.reply());
        // Short, scoped transaction for persistence
        persistTurn(user, resolvedId, message, reply.reply(), reply.model(), inputTokens, outputTokens, start);
        return reply.reply();
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
        CachedSuggestions usable = cached;
        if (cached == null || cached.expiresAt() <= System.currentTimeMillis()) {
            usable = refreshSuggestions(cached);
        }

        List<String> view = pick(usable.suggestions(), usable.nextStart(), SUGGESTIONS_PER_VIEW);

        // Advance the rotation pointer atomically so concurrent requests don't clobber
        // each other's updates (CAS loop).
        while (true) {
            CachedSuggestions existing = suggestionsCache.get();
            if (existing == null) {
                break;
            }
            int next = (existing.nextStart() + SUGGESTIONS_PER_VIEW) % Math.max(1, existing.suggestions().size());
            CachedSuggestions updatedCache = new CachedSuggestions(existing.suggestions(), existing.expiresAt(), next);
            if (suggestionsCache.compareAndSet(existing, updatedCache)) {
                break;
            }
        }
        return view;
    }

    /**
     * Coalesced cache refresh. Only one thread calls the upstream batch generation;
     * concurrent waiters reuse the stale cache (or a default batch) without blocking.
     * Never throws: any generation failure falls back to {@link #defaultBatch()}.
     */
    private CachedSuggestions refreshSuggestions(CachedSuggestions existing) {
        long ttlMs = Math.max(1, suggestionsCacheTtlSeconds) * 1000L;
        boolean won = suggestionsRefreshInFlight.compareAndSet(false, true);
        try {
            if (won) {
                List<String> batch;
                try {
                    batch = generateSuggestionsBatch();
                    if (batch == null || batch.size() < SUGGESTIONS_PER_VIEW) {
                        batch = defaultBatch();
                    }
                } catch (AiAgentException e) {
                    log.warn("AI suggestions generation failed (HTTP {} {}): using fallback batch",
                            e.getStatus(), e.getCode());
                    batch = defaultBatch();
                } catch (Exception e) {
                    log.warn("AI suggestions generation failed: {}", e.getMessage());
                    batch = defaultBatch();
                }
                CachedSuggestions fresh = new CachedSuggestions(batch, System.currentTimeMillis() + ttlMs);
                suggestionsCache.set(fresh);
                return fresh;
            }
            // Another thread is refreshing: serve stale suggestions (or a default) now.
            if (existing != null) {
                return existing;
            }
            return new CachedSuggestions(defaultBatch(), System.currentTimeMillis() + ttlMs);
        } finally {
            suggestionsRefreshInFlight.set(false);
        }
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
            ModelReply modelReply = sendWithModelChain(sessionId, prompt, "Suggestion batch");
            List<String> parsed = parseSuggestions(modelReply.reply());
            if (parsed != null && parsed.size() >= SUGGESTIONS_PER_VIEW) {
                return parsed;
            }
            log.warn("Could not parse AI suggestions, using fallback; raw reply: {}", modelReply.reply());
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
        return resolveConversationForJob(user, request.getConversationId(), request.getMessage());
    }

    /**
     * Resolves the conversation for a chat turn (sync or async/retry). A blank
     * {@code conversationId} creates a new conversation; otherwise ownership is
     * verified and the id is returned. Called BEFORE a job is submitted so every
     * retry attempt reuses exactly one conversation instead of creating orphans.
     */
    public String resolveConversationForJob(User user, String conversationId, String firstMessage) {
        if (conversationId == null || conversationId.isBlank()) {
            return createNewConversation(firstMessage, user);
        }
        ensureOwnership(conversationId, user);
        return conversationId;
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
        // Ownership check via a narrow EXISTS query that touches no lazy-loaded
        // associations, so it stays safe outside an open session (OSIV off / async).
        if (!conversationRepository.existsByExternalIdAndUserId(sessionId, user.getId())) {
            throw new IllegalArgumentException("Conversation not found or access denied");
        }
    }

    private ModelReply sendToOpencode(String sessionId, String message) {
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
            // All models in the chain were rate-limited: session recovery won't help a
            // quota issue, surface the 429 to the caller so they can retry later.
            if (e.getStatus() == 429) {
                throw e;
            }
            // The OpenCode session is unreachable or unusable (e.g. wedged, timed out, or
            // the server was restarted). Recover by replaying the application's persisted
            // history into a fresh OpenCode session so model context is preserved, then retry.
            log.warn("Opencode session {} failed for conversation {} ({}). Recovering by " +
                            "replaying {} persisted messages into a fresh session.",
                    opencodeSessionId, sessionId, e.getCode(), turn - 1, e);
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
    private ModelReply sendWithModelChain(String opencodeSessionId, String message, String actionLabel) {
        List<String> models = resolveModelChain();

        if (models.isEmpty()) {
            // No chain configured: rely on the opencode server's default model.
            log.info("{}: no model chain configured, using opencode default", actionLabel);
            return new ModelReply(opencodeClient.sendMessage(opencodeSessionId, SYSTEM_PROMPT, message), null);
        }

        int startIndex = currentStartIndex(models.size());

        for (int i = startIndex; i < models.size(); i++) {
            String model = models.get(i);
            try {
                String reply = opencodeClient.sendMessage(opencodeSessionId, SYSTEM_PROMPT, message, model);
                log.info("{} completed via model {}", actionLabel, model);
                return new ModelReply(reply, model);
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
    private ModelReply recoverSession(Conversation conversation, String message, int turn) {
        List<OpencodeClient.MapMessage> history = loadHistory(conversation);
        String freshOpencodeSessionId = opencodeClient.createSession();
        try {
            opencodeClient.replayHistory(freshOpencodeSessionId, SYSTEM_PROMPT, history);
            ModelReply reply = sendWithModelChain(freshOpencodeSessionId, message,
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
                .countByConversationIdAndRole(conversation.getId(), "user");
    }

    private List<OpencodeClient.MapMessage> loadHistory(Conversation conversation) {
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .map(m -> new OpencodeClient.MapMessage(m.getRole(), m.getContent()))
                .toList();
    }

    private void persistTurn(User user, String sessionId, String message, String reply,
                             String model, int inputTokens, int outputTokens, long start) {
        // TransactionTemplate: safe to call from both the sync path (no open transaction,
        // starts its own short tx) and the async worker path (starts its own short tx).
        transactionTemplate.executeWithoutResult(status -> {
            Conversation conversation = conversationRepository.findByExternalId(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));

            ChatMessageEntity userMsg = new ChatMessageEntity(conversation, "user", message);
            chatMessageRepository.save(userMsg);

            String modelName = (model == null || model.isBlank()) ? "opencode-default" : model;
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
        // Return the most recent window (newest-to-oldest then reversed to chronological),
        // bounding the response for very long conversations while keeping the List contract.
        return chatMessageRepository
                .findByConversationExternalIdOrderByCreatedAtDesc(conversationId,
                        PageRequest.of(0, RECENT_CONVERSATION_MESSAGES))
                .stream()
                .sorted(Comparator.comparing(ChatMessageEntity::getCreatedAt))
                .map(m -> new ChatMessage(m.getRole(), m.getContent()))
                .toList();
    }

    /**
     * Clears a conversation. The upstream OpenCode session deletion (blocking network
     * I/O) deliberately happens OUTSIDE any DB transaction; only the short persistence
     * step is transactional. A missing or non-owned conversation is a 404 so the caller
     * learns nothing about other users' conversation ids.
     */
    public void clearConversation(String conversationId, User user) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Conversation id must not be empty");
        }

        Conversation conversation = conversationRepository.findByExternalId(conversationId)
                .orElse(null);

        if (conversation == null || !conversation.getUser().getId().equals(user.getId())) {
            throw new AiAgentException("Conversation not found", "NOT_FOUND", 404);
        }

        // Best-effort upstream cleanup outside any transaction (no DB connection is
        // held across network I/O, and an upstream failure here must not roll back the
        // conversation deletion).
        try {
            opencodeClient.deleteSession(conversation.getOpencodeSessionId());
        } catch (Exception e) {
            log.warn("Failed to delete OpenCode session {}: {}",
                    conversation.getOpencodeSessionId(), e.getMessage());
        }

        transactionTemplate.executeWithoutResult(status -> {
            chatMessageRepository.deleteByConversationExternalId(conversationId);
            conversationRepository.deleteByExternalId(conversationId);
        });

        log.info("Cleared conversation for session {}", conversationId);
    }

    /**
     * Deletes a single message. A missing or non-owned conversation/message surfaces
     * as a 404 so the caller learns nothing about other users' conversation ids.
     */
    public void deleteMessage(String conversationId, Long messageId, User user) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Conversation id must not be empty");
        }
        if (messageId == null) {
            throw new IllegalArgumentException("Message id must not be empty");
        }

        Conversation conversation = conversationRepository.findByExternalId(conversationId)
                .orElse(null);

        if (conversation == null || !conversation.getUser().getId().equals(user.getId())) {
            throw new AiAgentException("Conversation not found", "NOT_FOUND", 404);
        }

        ChatMessageEntity message = chatMessageRepository.findById(messageId).orElse(null);

        if (message == null || !message.getConversation().getId().equals(conversation.getId())) {
            throw new AiAgentException("Message not found", "NOT_FOUND", 404);
        }

        chatMessageRepository.delete(message);

        log.info("Deleted message {} from conversation {}", messageId, conversationId);
    }

    /**
     * Starts a new conversation. The upstream OpenCode session is created BEFORE any
     * persistence and outside a transaction, so no DB connection is held during the
     * blocking network call; the row is saved by the repository's own transaction.
     */
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
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId(), PageRequest.of(0, 200))
                .stream()
                .map(c -> new ChatSessionInfo(c.getExternalId(), c.getTitle(), c.getUpdatedAt()))
                .toList();
    }

    private String truncateTitle(String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > 40 ? clean.substring(0, 40) + "..." : clean;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long cjk = text.codePoints().filter(AiAgentService::isCjk).count();
        long latin = text.codePoints().count() - cjk;
        // CJK characters are roughly one token each; Latin text ~4 chars per token.
        return (int) Math.max(1L, (latin / 4) + cjk);
    }

    /** True for CJK ideographs/kana/hangul (and CJK punctuation): ~1 token per char. */
    private static boolean isCjk(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF)      // Hiragana + Katakana
                || (cp >= 0x3400 && cp <= 0x4DBF)  // CJK Ext A
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // CJK Unified Ideographs
                || (cp >= 0xAC00 && cp <= 0xD7AF)  // Hangul
                || (cp >= 0x3000 && cp <= 0x303F)  // CJK punctuation
                || (cp >= 0xFF00 && cp <= 0xFFEF); // full-width forms
    }

    public record ChatSessionInfo(String conversationId, String title, LocalDateTime updatedAt) {
    }

    /**
     * Result of a chat turn: the assistant reply plus the model that produced it
     * (null when the server default is used, i.e. no model chain configured).
     */
    private record ModelReply(String reply, String model) {
    }
}

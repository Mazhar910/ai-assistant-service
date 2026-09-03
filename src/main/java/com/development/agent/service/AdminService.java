package com.development.agent.service;

import com.development.agent.cache.UserCache;
import com.development.agent.client.OpencodeClient;
import com.development.agent.entity.Conversation;
import com.development.agent.entity.Role;
import com.development.agent.entity.User;
import com.development.agent.repository.ChatMessageRepository;
import com.development.agent.repository.ConversationRepository;
import com.development.agent.repository.TokenUsageRepository;
import com.development.agent.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class AdminService {

    /** Upper bound for the backward-compatible (non-paginated) user list endpoint. */
    private static final int MAX_PLAIN_USER_LIST = 200;

    /** How long user deletion waits for best-effort upstream session cleanup to drain. */
    private static final long DELETE_CLEANUP_TIMEOUT_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final UserCache userCache;
    private final OpencodeClient opencodeClient;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService cleanupExecutor;
    private final AtomicBoolean cleanupExecutorShutdown = new AtomicBoolean(false);

    public AdminService(UserRepository userRepository,
                        ConversationRepository conversationRepository,
                        ChatMessageRepository chatMessageRepository,
                        TokenUsageRepository tokenUsageRepository,
                        UserCache userCache,
                        OpencodeClient opencodeClient,
                        PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.userCache = userCache;
        this.opencodeClient = opencodeClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Dedicated, bounded pool for user-deletion cleanup so blocking upstream calls
        // never contend with the JVM common pool (used by repository aggregate queries).
        this.cleanupExecutor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "user-delete-cleanup-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdownCleanupExecutor() {
        if (cleanupExecutorShutdown.compareAndSet(false, true)) {
            cleanupExecutor.shutdownNow();
        }
    }

    /** Backward-compatible plain list, bounded to avoid unbounded responses. */
    public List<Map<String, Object>> getAllUsers() {
        return toUserViews(userRepository.findAll(PageRequest.of(0, MAX_PLAIN_USER_LIST, Sort.by("id"))).getContent());
    }

    /** Paginated user list with batched token/conversation aggregation (no N+1). */
    public Map<String, Object> getAllUsers(Pageable pageable) {
        Page<User> page = userRepository.findAll(pageable);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", toUserViews(page.getContent()));
        result.put("page", page.getNumber());
        result.put("size", page.getSize());
        result.put("totalElements", page.getTotalElements());
        result.put("totalPages", page.getTotalPages());
        return result;
    }

    private List<Map<String, Object>> toUserViews(List<User> users) {
        List<Long> ids = users.stream().map(User::getId).collect(Collectors.toList());

        Map<Long, Long> tokensByUser = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : tokenUsageRepository.sumTokensByUserIds(ids)) {
                tokensByUser.put((Long) row[0], (Long) row[1]);
            }
        }
        Map<Long, Long> convosByUser = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : conversationRepository.countByUserIds(ids)) {
                convosByUser.put((Long) row[0], (Long) row[1]);
            }
        }

        return users.stream().map(user -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", user.getId());
            map.put("username", user.getUsername());
            map.put("email", user.getEmail());
            map.put("role", user.getRole().name());
            map.put("enabled", user.isEnabled());
            map.put("createdAt", user.getCreatedAt());
            map.put("updatedAt", user.getUpdatedAt());
            map.put("totalTokens", tokensByUser.getOrDefault(user.getId(), 0L));
            map.put("conversationCount", convosByUser.getOrDefault(user.getId(), 0L));
            return map;
        }).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> toggleUserAccess(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        long adminCount = userRepository.countByRole(Role.ADMIN);
        if (user.getRole() == Role.ADMIN && user.isEnabled() && adminCount <= 1) {
            throw new IllegalArgumentException("Cannot disable the last remaining admin");
        }

        user.setEnabled(!user.isEnabled());
        if (!user.isEnabled()) {
            user.setActiveToken(null);
        }
        userRepository.save(user);
        userCache.invalidate(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("enabled", user.isEnabled());
        result.put("message", user.isEnabled() ? "User access restored" : "User access revoked");
        return result;
    }

    @Transactional
    public Map<String, Object> changeUserRole(Long userId, String role) {
        Role newRole;
        try {
            newRole = Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Role must be ADMIN or USER");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRole() == Role.ADMIN && newRole != Role.ADMIN) {
            long adminCount = userRepository.countByRole(Role.ADMIN);
            if (adminCount <= 1) {
                throw new IllegalArgumentException("Cannot demote the last remaining admin");
            }
        }

        user.setRole(newRole);
        user.setActiveToken(null);
        userRepository.save(user);
        userCache.invalidate(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole().name());
        result.put("message", "Role updated to " + newRole.name());
        return result;
    }

    /**
     * Deletes a user plus all associated data. The blocking upstream opencode session
     * deletions happen OUTSIDE any DB transaction; all database deletes run in a single
     * short transaction as bulk operations (no per-row loops / N+1 writes).
     */
    public Map<String, Object> deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRole() == Role.ADMIN) {
            long adminCount = userRepository.countByRole(Role.ADMIN);
            if (adminCount <= 1) {
                throw new IllegalArgumentException("Cannot delete the last remaining admin");
            }
        }

        // One read collects the user's conversations (ids for bulk delete + opencode
        // session ids for best-effort upstream cleanup).
        List<Conversation> convos = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);

        // Best-effort upstream cleanup outside any transaction: no DB connection is held
        // across network I/O, and an upstream failure here must not roll back the deletion.
        // The calls are independent blocking I/O, so run them concurrently on a dedicated
        // bounded pool (not the common pool) to avoid slowing user deletion for many sessions.
        List<CompletableFuture<Void>> cleanups = new ArrayList<>();
        for (Conversation c : convos) {
            String ocSession = c.getOpencodeSessionId();
            if (ocSession == null || ocSession.isBlank()) {
                continue;
            }
            cleanups.add(CompletableFuture.runAsync(() -> {
                try {
                    opencodeClient.deleteSession(ocSession);
                } catch (Exception e) {
                    log.warn("Could not delete opencode session {} while deleting user {}: {}",
                            ocSession, userId, e.getMessage());
                }
            }, cleanupExecutor));
        }
        if (!cleanups.isEmpty()) {
            try {
                CompletableFuture.allOf(cleanups.toArray(new CompletableFuture[0]))
                        .get(DELETE_CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Timed out waiting for upstream opencode session cleanup for user {}: {}",
                        userId, e.getMessage());
            }
        }

        // All DB deletes in one transaction, FK-safe order (messages -> conversations ->
        // token usage -> user), executed as bulk statements rather than per-row loops.
        List<Long> conversationIds = convos.stream().map(Conversation::getId).toList();
        transactionTemplate.executeWithoutResult(status -> {
            chatMessageRepository.bulkDeleteByConversationIds(conversationIds);
            conversationRepository.deleteByUserId(userId);
            tokenUsageRepository.deleteByUserId(userId);
            userRepository.deleteById(userId);
        });
        userCache.invalidate(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "User '" + user.getUsername() + "' and all associated data deleted");
        return result;
    }

    public Map<String, Object> getOverallStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("activeUsers", userRepository.countByEnabled(true));
        stats.put("totalTokens", tokenUsageRepository.sumAllTokens());
        stats.put("totalConversations", conversationRepository.count());

        List<Object[]> byModel = tokenUsageRepository.sumTokensByModel();
        List<Map<String, Object>> modelBreakdown = byModel.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", row[0]);
            m.put("tokens", row[1]);
            return m;
        }).collect(Collectors.toList());
        stats.put("tokensByModel", modelBreakdown);

        List<Map<String, Object>> dailyUsage = aggregateDailyUsage(
                tokenUsageRepository.sumTokensPerDaySince(LocalDateTime.now().minusDays(30)));
        stats.put("dailyUsage", dailyUsage);

        return stats;
    }

    public Map<String, Object> getUserStats(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("userId", user.getId());
        stats.put("username", user.getUsername());
        stats.put("totalTokens", tokenUsageRepository.sumTokensByUserId(userId));
        stats.put("conversationCount", conversationRepository.countByUserId(userId));

        List<Object[]> byModel = tokenUsageRepository.sumTokensByModelForUser(userId);
        List<Map<String, Object>> modelBreakdown = byModel.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", row[0]);
            m.put("tokens", row[1]);
            return m;
        }).collect(Collectors.toList());
        stats.put("tokensByModel", modelBreakdown);

        List<Map<String, Object>> dailyUsage = aggregateDailyUsage(
                tokenUsageRepository.sumTokensPerDaySinceForUser(userId, LocalDateTime.now().minusDays(30)));
        stats.put("dailyUsage", dailyUsage);

        return stats;
    }

    /**
     * Aggregates daily usage from {@code [day, totalTokens]} rows returned by the DB.
     */
    private List<Map<String, Object>> aggregateDailyUsage(List<Object[]> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", row[0].toString());
            m.put("tokens", ((Number) row[1]).longValue());
            result.add(m);
        }
        return result;
    }
}

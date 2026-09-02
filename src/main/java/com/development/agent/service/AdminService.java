package com.development.agent.service;

import com.development.agent.cache.UserCache;
import com.development.agent.entity.Conversation;
import com.development.agent.entity.TokenUsage;
import com.development.agent.entity.User;
import com.development.agent.repository.ChatMessageRepository;
import com.development.agent.repository.ConversationRepository;
import com.development.agent.repository.TokenUsageRepository;
import com.development.agent.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final UserCache userCache;

    public AdminService(UserRepository userRepository,
                        ConversationRepository conversationRepository,
                        ChatMessageRepository chatMessageRepository,
                        TokenUsageRepository tokenUsageRepository,
                        UserCache userCache) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.userCache = userCache;
    }

    public List<Map<String, Object>> getAllUsers() {
        return toUserViews(userRepository.findAll());
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
            map.put("role", user.getRole());
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

        long adminCount = userRepository.countByRole("ADMIN");
        if (user.getRole().equals("ADMIN") && user.isEnabled() && adminCount <= 1) {
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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRole().equals("ADMIN") && !role.equals("ADMIN")) {
            long adminCount = userRepository.countByRole("ADMIN");
            if (adminCount <= 1) {
                throw new IllegalArgumentException("Cannot demote the last remaining admin");
            }
        }

        user.setRole(role);
        user.setActiveToken(null);
        userRepository.save(user);
        userCache.invalidate(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        result.put("message", "Role updated to " + role);
        return result;
    }

    @Transactional
    public Map<String, Object> deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRole().equals("ADMIN")) {
            long adminCount = userRepository.countByRole("ADMIN");
            if (adminCount <= 1) {
                throw new IllegalArgumentException("Cannot delete the last remaining admin");
            }
        }

        // Delete dependent rows in FK-safe order:
        // 1. token usage records referencing the user
        tokenUsageRepository.deleteByUserId(userId);
        // 2. chat messages + conversations referencing the user
        List<Conversation> convos = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        for (Conversation c : convos) {
            chatMessageRepository.deleteByConversationExternalId(c.getExternalId());
            conversationRepository.deleteByExternalId(c.getExternalId());
        }
        // 3. the user
        userRepository.deleteById(userId);
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

        List<Map<String, Object>> dailyUsage = aggregateDailyUsage(tokenUsageRepository.findByCreatedAtGreaterThanEqual(LocalDateTime.now().minusDays(30)));
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

        List<Map<String, Object>> dailyUsage = aggregateDailyUsage(tokenUsageRepository.findByUserIdAndCreatedAtGreaterThanEqual(userId, LocalDateTime.now().minusDays(30)));
        stats.put("dailyUsage", dailyUsage);

        return stats;
    }

    private List<Map<String, Object>> aggregateDailyUsage(List<com.development.agent.entity.TokenUsage> records) {
        TreeMap<String, Long> byDay = new TreeMap<>();
        for (com.development.agent.entity.TokenUsage t : records) {
            String day = t.getCreatedAt().toLocalDate().toString();
            byDay.merge(day, t.getTokensInput() + t.getTokensOutput(), Long::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Long> e : byDay.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey());
            m.put("tokens", e.getValue());
            result.add(m);
        }
        return result;
    }
}

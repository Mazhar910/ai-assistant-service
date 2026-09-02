package com.development.agent.cache;

import com.development.agent.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-memory user cache with TTL expiry. Self-contained (no external
 * cache library) so it works out of the box; swap for a Redis-backed UserCache
 * implementation for multi-node scale.
 *
 * Thread safety: ConcurrentHashMap + volatile timestamps; duplicate loads after TTL
 * expiry are acceptable (a single extra DB read).
 */
@Component
public class InMemoryUserCache implements UserCache {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserCache.class);

    private record Entry(User user, long expiresAt) {}

    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    @Value("${app.security.user-cache-ttl-seconds:60}")
    private long ttlSeconds;

    @Override
    public Optional<User> get(Long userId) {
        Entry entry = entries.get(userId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            entries.remove(userId);
            return Optional.empty();
        }
        return Optional.of(entry.user());
    }

    @Override
    public void put(Long userId, User user) {
        long ttlMs = Math.max(1, ttlSeconds) * 1000L;
        entries.put(userId, new Entry(user, System.currentTimeMillis() + ttlMs));
    }

    @Override
    public void invalidate(Long userId) {
        entries.remove(userId);
    }
}

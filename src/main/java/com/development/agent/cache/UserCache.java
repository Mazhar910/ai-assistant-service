package com.development.agent.cache;

import com.development.agent.entity.User;

import java.util.Optional;

/**
 * Cache for user lookups used by authentication. Read-heavy authenticated requests
 * avoid a DB hit per request. Implementations must guarantee that entries are
 * invalidated (or expire) whenever a user's token, role, or enabled state changes,
 * so security decisions are never based on stale data.
 *
 * The in-memory implementation is used locally; a distributed implementation
 * (e.g. Redis) can be swapped in for multi-node deployments without changing callers.
 */
public interface UserCache {

    /** Return a cached user if present and not expired, otherwise empty. */
    Optional<User> get(Long userId);

    /** Store (or refresh) the cached user. */
    void put(Long userId, User user);

    /** Remove a single entry (call on every user mutation). */
    void invalidate(Long userId);
}

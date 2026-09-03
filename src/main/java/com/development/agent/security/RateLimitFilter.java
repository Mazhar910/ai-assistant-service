package com.development.agent.security;

import com.development.agent.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-key rate limiter using a sliding-window counter. Keyed by the
 * authenticated user id when a valid Bearer token is present, otherwise by the
 * client IP (covers login/register endpoints which are unauthenticated).
 *
 * The sliding window tracks the timestamps of recent requests per key, pruning
 * those outside the window on each call, so bursts at a window boundary are
 * smoothed out (a fixed-window counter would allow 2x the limit in a 2-second
 * span). Bounded memory: a janitor thread drops keys whose window fully elapsed.
 *
 * Protects the API from abuse and bursty traffic when scaling. In-memory for a
 * single node; a distributed limiter (Redis) can replace it for multi-node scale.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Value("${app.security.rate-limit-per-minute:0}")
    private int rateLimitPerMinute;

    /** key -> deque of recent request timestamps (within the current window, sorted ascending) */
    private final ConcurrentHashMap<String, Deque<Long>> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor;

    private static final long WINDOW_MS = 60_000L;

    public RateLimitFilter(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        // Periodically drop buckets whose window has fully elapsed so the map doesn't
        // accumulate an entry per historical user/IP forever.
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-janitor");
            t.setDaemon(true);
            return t;
        });
        this.janitor.scheduleWithFixedDelay(this::sweepStaleBuckets, WINDOW_MS, WINDOW_MS, TimeUnit.MILLISECONDS);
    }

    private void sweepStaleBuckets() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Deque<Long>> entry : buckets.entrySet()) {
            Deque<Long> timestamps = entry.getValue();
            Long oldest = timestamps.peekFirst();
            if (oldest != null && now - oldest >= WINDOW_MS) {
                // The whole window has elapsed, so the key can be dropped.
                if (buckets.remove(entry.getKey(), timestamps)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.debug("Evicted {} stale rate-limit buckets", removed);
        }
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return request.getMethod().equals("OPTIONS") || (path != null && path.startsWith("/h2-console"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (rateLimitPerMinute <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);
        if (!allow(key)) {
            log.debug("Rate limit exceeded for key {}", key);
            ErrorResponse error = new ErrorResponse("RATE_LIMITED",
                    "Too many requests. Please slow down and try again later.", System.currentTimeMillis());
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(error));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                if (jwtUtil.validateToken(header.substring(7))) {
                    return "u:" + jwtUtil.getUserId(header.substring(7));
                }
            } catch (Exception ignored) {
                // fall through to IP-based keying
            }
        }
        String ip = request.getRemoteAddr();
        return "ip:" + (ip == null ? "unknown" : ip);
    }

    /**
     * Sliding-window counter: prunes timestamps older than the window, then allows
     * the request if the count within the window is below the limit. Each key is
     * guarded by {@code compute} so requests for the same key are serialized while
     * different keys never contend on a shared lock.
     */
    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        boolean[] allowed = {false};
        buckets.compute(key, (k, existing) -> {
            Deque<Long> deque = (existing == null) ? new ArrayDeque<>() : existing;
            // Drop timestamps that have left the sliding window.
            while (!deque.isEmpty() && now - deque.peekFirst() >= WINDOW_MS) {
                deque.pollFirst();
            }
            if (deque.size() < rateLimitPerMinute) {
                deque.addLast(now);
                allowed[0] = true;
            }
            return deque;
        });
        return allowed[0];
    }
}

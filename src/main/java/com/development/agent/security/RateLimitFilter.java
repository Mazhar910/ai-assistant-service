package com.development.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key rate limiter using a fixed-window sliding counter. Keyed by the
 * authenticated user id when a valid Bearer token is present, otherwise by the
 * client IP (covers login/register endpoints which are unauthenticated).
 *
 * Protects the API from abuse and bursty traffic when scaling. In-memory for a
 * single node; a distributed limiter (Redis) can replace it for multi-node scale.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final JwtUtil jwtUtil;

    @Value("${app.security.rate-limit-per-minute:0}")
    private int rateLimitPerMinute;

    /** key -> long[]{windowStartMillis, count} */
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    private static final long WINDOW_MS = 60_000L;

    public RateLimitFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
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
            log.warn("Rate limit exceeded for key {}", key);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter()
                    .write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please slow down and try again later.\"}");
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

    private synchronized boolean allow(String key) {
        long now = System.currentTimeMillis();
        long[] bucket = buckets.get(key);
        if (bucket == null || now - bucket[0] >= WINDOW_MS) {
            buckets.put(key, new long[]{now, 1});
            return true;
        }
        int count = (int) bucket[1];
        if (count >= rateLimitPerMinute) {
            return false;
        }
        bucket[1] = count + 1;
        return true;
    }
}

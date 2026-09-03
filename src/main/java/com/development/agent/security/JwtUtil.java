package com.development.agent.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs;
    private final long refreshExpirationMs;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expiration-ms}") long expirationMs,
                   @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret is not configured. Set the JWT_SECRET environment variable to a " +
                            "random Base64 value (e.g. `openssl rand -base64 48`). Refusing to start with " +
                            "a missing or default signing key.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.jwt.secret is not valid Base64. Configure JWT_SECRET as Base64-encoded random bytes.", e);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must decode to at least 32 bytes (256-bit HMAC key). " +
                            "Configure a longer JWT_SECRET.");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    private static final String TYPE_CLAIM = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    /** Short-lived access token used in the Authorization header for authenticated requests. */
    public String generateToken(Long userId, String username, String role) {
        return build(userId, username, role, TYPE_ACCESS, expirationMs);
    }

    /** Longer-lived refresh token, used only at POST /api/auth/refresh to rotate the session. */
    public String generateRefreshToken(Long userId, String username, String role) {
        return build(userId, username, role, TYPE_REFRESH, refreshExpirationMs);
    }

    private String build(Long userId, String username, String role, String type, long ttlMs) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .claim(TYPE_CLAIM, type)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** True if the token is a syntactically valid REFRESH token (correct type claim, not expired). */
    public boolean validateRefreshToken(String token) {
        return validate(token, TYPE_REFRESH);
    }

    /** True if the token is a syntactically valid ACCESS token (correct type claim, not expired). */
    public boolean validateAccessToken(String token) {
        return validate(token, TYPE_ACCESS);
    }

    /** Backwards-compatible: accepts any well-formed non-expired token (used by older callers). */
    public boolean validateToken(String token) {
        return validate(token, null);
    }

    private boolean validate(String token, String expectedType) {
        try {
            Claims claims = parseToken(token);
            return expectedType == null || expectedType.equals(claims.get(TYPE_CLAIM, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }
}

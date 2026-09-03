package com.development.agent.security;

import com.development.agent.cache.UserCache;
import com.development.agent.entity.User;
import com.development.agent.exception.ErrorResponse;
import com.development.agent.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final UserCache userCache;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtUtil jwtUtil, UserRepository userRepository, UserCache userCache,
                         ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.userCache = userCache;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                if (jwtUtil.validateAccessToken(token)) {
                    Long userId = jwtUtil.getUserId(token);

                    // Cache-first lookup: read-heavy requests avoid a DB hit per request.
                    // The cache is invalidated on every user mutation, so security
                    // decisions (enabled state, activeToken, role) never go stale.
                    User user = loadUser(userId);
                    if (user != null) {

                        if (!user.isEnabled()) {
                            log.warn("Disabled user attempted access: {}", user.getUsername());
                            writeError(response, HttpServletResponse.SC_FORBIDDEN, "ACCOUNT_DISABLED",
                                    "Account has been disabled by admin");
                            return;
                        }

                        if (!token.equals(user.getActiveToken())) {
                            log.warn("Invalid token for user {} (token mismatch - possibly logged in elsewhere)", user.getUsername());
                            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "TOKEN_INVALIDATED",
                                    "Session invalidated. You have logged in from another location.");
                            return;
                        }

                        // Use the role from the database (so admin demotions/promotions take effect immediately)
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                        );
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                }
            } catch (Exception e) {
                log.debug("JWT validation failed: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        ErrorResponse error = new ErrorResponse(code, message, System.currentTimeMillis());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    private User loadUser(Long userId) {
        Optional<User> cached = userCache.get(userId);
        if (cached.isPresent()) {
            return cached.get();
        }
        return userRepository.findById(userId)
                .map(user -> {
                    userCache.put(userId, user);
                    return user;
                })
                .orElse(null);
    }
}

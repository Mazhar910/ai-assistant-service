package com.development.agent.service;

import com.development.agent.cache.UserCache;
import com.development.agent.dto.AuthResponse;
import com.development.agent.dto.LoginRequest;
import com.development.agent.dto.RegisterRequest;
import com.development.agent.entity.User;
import com.development.agent.exception.AiAgentException;
import com.development.agent.repository.UserRepository;
import com.development.agent.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final UserCache userCache;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil, UserCache userCache) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.userCache = userCache;
    }

    /**
     * Registers a regular user. Admins are ONLY provisioned through the explicit
     * {@code ADMIN_PASSWORD} bootstrap in {@code DataInitializer} - the public
     * registration endpoint never grants the ADMIN role.
     *
     * @Transactional: the existence checks, user create and token-issue/active-token
     * update commit as one unit. A concurrent duplicate register races to the unique
     * constraints instead, surfacing as a {@code DataIntegrityViolationException}
     * (mapped to HTTP 409 by {@code GlobalExceptionHandler}).
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new AiAgentException("Username already taken", "USERNAME_TAKEN", 409);
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AiAgentException("Email already registered", "EMAIL_TAKEN", 409);
        }

        User user = new User(
                request.getUsername(),
                request.getEmail(),
                passwordEncoder.encode(request.getPassword())
        );

        // Persist first so the user has a real ID, then issue the token,
        // then save again to store the activeToken. Generating the token before
        // save produced a "null" subject (user.getId() was null) and broke the
        // first authenticated request after signup.
        user = userRepository.save(user);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole().name());
        user.setActiveToken(token);
        userRepository.save(user);
        userCache.invalidate(user.getId());

        log.info("User '{}' registered successfully with role '{}'", user.getUsername(), user.getRole());
        return AuthResponse.success(token, user.getUsername(), user.getRole().name());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new AiAgentException("Invalid username or password", "INVALID_CREDENTIALS", 401);
        }

        if (!user.isEnabled()) {
            throw new AiAgentException("Account has been disabled by admin", "ACCOUNT_DISABLED", 403);
        }

        // Single session: invalidate previous token
        String newToken = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole().name());
        user.setActiveToken(newToken);
        userRepository.save(user);
        // Refresh cached user so the new activeToken is honored immediately
        userCache.put(user.getId(), user);

        log.info("User '{}' logged in successfully (single session enforced)", user.getUsername());
        return AuthResponse.success(newToken, user.getUsername(), user.getRole().name());
    }

    public void logout(User user) {
        user.setActiveToken(null);
        userRepository.save(user);
        userCache.invalidate(user.getId());
        log.info("User '{}' logged out", user.getUsername());
    }
}

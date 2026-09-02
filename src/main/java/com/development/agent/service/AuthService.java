package com.development.agent.service;

import com.development.agent.cache.UserCache;
import com.development.agent.dto.AuthResponse;
import com.development.agent.dto.LoginRequest;
import com.development.agent.dto.RegisterRequest;
import com.development.agent.entity.User;
import com.development.agent.repository.UserRepository;
import com.development.agent.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            return AuthResponse.error("Username already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            return AuthResponse.error("Email already registered");
        }

        User user = new User(
                request.getUsername(),
                request.getEmail(),
                passwordEncoder.encode(request.getPassword())
        );

        // First user becomes admin
        if (userRepository.count() == 0) {
            user.setRole("ADMIN");
            log.info("First user '{}' registered as ADMIN", request.getUsername());
        }

        user = userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        user.setActiveToken(token);
        userRepository.save(user);
        userCache.invalidate(user.getId());

        log.info("User '{}' registered successfully with role '{}'", user.getUsername(), user.getRole());
        return AuthResponse.success(token, user.getUsername(), user.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return AuthResponse.error("Invalid username or password");
        }

        if (!user.isEnabled()) {
            return AuthResponse.error("Account has been disabled by admin");
        }

        // Single session: invalidate previous token
        String newToken = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        user.setActiveToken(newToken);
        userRepository.save(user);
        // Refresh cached user so the new activeToken is honored immediately
        userCache.put(user.getId(), user);

        log.info("User '{}' logged in successfully (single session enforced)", user.getUsername());
        return AuthResponse.success(newToken, user.getUsername(), user.getRole());
    }

    public void logout(User user) {
        user.setActiveToken(null);
        userRepository.save(user);
        userCache.invalidate(user.getId());
        log.info("User '{}' logged out", user.getUsername());
    }
}

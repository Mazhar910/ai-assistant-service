package com.development.agent.controller;

import com.development.agent.dto.AuthResponse;
import com.development.agent.dto.LoginRequest;
import com.development.agent.dto.RegisterRequest;
import com.development.agent.entity.User;
import com.development.agent.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Registration and login always return 200 on success (body: AuthResponse).
     * Failures (duplicate username/email, invalid credentials, disabled account)
     * are thrown from AuthService as AiAgentException and mapped by
     * GlobalExceptionHandler to the unified ErrorResponse shape with the proper
     * status codes (409 conflict, 401 unauthorized, 403 forbidden).
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@AuthenticationPrincipal User user) {
        authService.logout(user);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal User user) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("role", user.getRole().name());
        data.put("enabled", user.isEnabled());
        return ResponseEntity.ok(data);
    }
}

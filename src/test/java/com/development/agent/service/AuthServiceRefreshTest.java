package com.development.agent.service;

import com.development.agent.cache.UserCache;
import com.development.agent.dto.AuthResponse;
import com.development.agent.entity.User;
import com.development.agent.exception.AiAgentException;
import com.development.agent.repository.UserRepository;
import com.development.agent.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the JWT refresh-token flow ({@code AuthService.refresh}):
 * only a matching, unexpired refresh token may rotate a session, and a stale or
 * replayed token rejects the whole exchange (single active session). {@code JwtUtil}
 * is mocked so the token-comparison branches are exercised deterministically.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRefreshTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserCache userCache;
    @Mock
    private JwtUtil jwtUtil;

    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, passwordEncoder, jwtUtil, userCache);
        user = new User("alice", "alice@example.com", "pw");
        user.setId(1L);
        lenient().when(jwtUtil.validateRefreshToken(anyString())).thenReturn(true);
        lenient().when(jwtUtil.getUserId(anyString())).thenReturn(1L);
    }

    @Test
    void validRefreshTokenRotatesPairAndReturnsNewCredentials() {
        user.setRefreshToken("presented-token");
        user.setActiveToken("old-access");
        when(jwtUtil.generateToken(anyLong(), anyString(), anyString())).thenReturn("new-access");
        when(jwtUtil.generateRefreshToken(anyLong(), anyString(), anyString())).thenReturn("new-refresh");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AuthResponse response = service.refresh("presented-token");

        assertNotNull(response.getToken());
        assertNotNull(response.getRefreshToken());
        assertEquals("new-access", response.getToken());
        assertEquals("new-refresh", response.getRefreshToken());
        assertNotEquals("old-access", response.getToken());
        // The rotated refresh token is persisted for the user.
        assertEquals("new-refresh", user.getRefreshToken());
        verify(userCache).invalidate(1L);
    }

    @Test
    void mismatchedOrReplayedRefreshTokenIsRejected() {
        user.setRefreshToken("stored-token");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // Present a token that does not match the one currently stored for the user.
        AiAgentException ex = assertThrows(AiAgentException.class, () -> service.refresh("other-token"));
        assertEquals(401, ex.getStatus());
        assertEquals("INVALID_REFRESH_TOKEN", ex.getCode());
        // The stored token/session is untouched by the rejected exchange.
        assertEquals("stored-token", user.getRefreshToken());
    }

    @Test
    void malformedOrExpiredRefreshTokenIsRejected() {
        when(jwtUtil.validateRefreshToken("bad-token")).thenReturn(false);

        AiAgentException ex = assertThrows(AiAgentException.class, () -> service.refresh("bad-token"));
        assertEquals(401, ex.getStatus());
        assertEquals("INVALID_REFRESH_TOKEN", ex.getCode());
    }

    @Test
    void unknownUserForRefreshTokenIsRejected() {
        user.setRefreshToken("presented-token");
        when(jwtUtil.getUserId("presented-token")).thenReturn(99L);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        AiAgentException ex = assertThrows(AiAgentException.class, () -> service.refresh("presented-token"));
        assertEquals(401, ex.getStatus());
    }

    @Test
    void refreshTokenForDisabledUserIsRejected() {
        user.setRefreshToken("presented-token");
        user.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AiAgentException ex = assertThrows(AiAgentException.class, () -> service.refresh("presented-token"));
        assertEquals(401, ex.getStatus());
    }

    @Test
    void replayOfOldTokenRejectedAfterRotation() {
        // First use of the token rotates the session to a new stored refresh token.
        when(jwtUtil.generateToken(anyLong(), anyString(), anyString())).thenReturn("new-access");
        when(jwtUtil.generateRefreshToken(anyLong(), anyString(), anyString())).thenReturn("new-refresh");
        user.setRefreshToken("old-token");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AuthResponse first = service.refresh("old-token");
        assertEquals("new-refresh", first.getRefreshToken());

        // Replaying the now-rotated old token no longer matches the stored value.
        AiAgentException ex = assertThrows(AiAgentException.class, () -> service.refresh("old-token"));
        assertEquals(401, ex.getStatus());
        assertEquals("INVALID_REFRESH_TOKEN", ex.getCode());
    }
}
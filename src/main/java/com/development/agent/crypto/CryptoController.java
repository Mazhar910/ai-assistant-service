package com.development.agent.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Establishes a per-session AES key. These two endpoints are the only plaintext ones:
 * the client fetches the RSA public key here, then posts its AES key (RSA-encrypted)
 * in the handshake. Every other request/response is encrypted with that AES key.
 */
@RestController
@RequestMapping("/api/crypto")
public class CryptoController {

    private static final Logger log = LoggerFactory.getLogger(CryptoController.class);

    private final CryptoKeyStore keyStore;

    public CryptoController(CryptoKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> publicKey() {
        return ResponseEntity.ok(Map.of("publicKey", keyStore.publicKeyPem()));
    }

    @PostMapping("/handshake")
    public ResponseEntity<?> handshake(@RequestBody Map<String, String> body) {
        String encryptedKey = body.get("encryptedKey");
        if (encryptedKey == null || encryptedKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "MISSING_KEY", "message", "encryptedKey is required"));
        }
        try {
            String sessionId = keyStore.registerSession(encryptedKey);
            log.info("Handshake completed, issued session {}", sessionId);
            return ResponseEntity.ok(Map.of("sessionId", sessionId));
        } catch (Exception e) {
            log.warn("Handshake failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "HANDSHAKE_FAILED", "message", "Could not establish session"));
        }
    }
}

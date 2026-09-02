package com.development.agent.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the server RSA keypair and the per-session AES keys established during the
 * handshake. RSA-OAEP protects the AES key on its way from the client; AES-256-GCM
 * then encrypts request/response payloads and the auth header for that session.
 *
 * For single-node deployments this is in-memory. For multi-node scale, the session
 * key store would move to a shared cache (Redis) so any node can decrypt any session.
 */
@Component
public class CryptoKeyStore {

    private static final Logger log = LoggerFactory.getLogger(CryptoKeyStore.class);
    private static final String RSA_ALGO = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    /**
     * OAEP parameters. WebCrypto's RSA-OAEP uses the digest hash for BOTH the OAEP
     * digest and MGF1, so we must use SHA-256 for MGF1 as well — Java's default is
     * MGF1-SHA-1, which would break browser interoperability.
     */
    private static final OAEPParameterSpec OAEP_PARAMS = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final String AES = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private final Map<String, SecKey> sessions = new ConcurrentHashMap<>();

    @Value("${app.crypto.session-ttl-seconds:3600}")
    private long sessionTtlSeconds;

    public CryptoKeyStore() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
            log.info("CryptoKeyStore initialized with RSA-2048 keypair");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize RSA keypair", e);
        }
    }

    public String publicKeyPem() {
        String b64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return b64;
    }

    /** Decrypt the client AES key (encrypted with RSA public key) and store it for a session. */
    public String registerSession(String encryptedKeyB64) throws Exception {
        byte[] aesKeyBytes = decryptRsa(Base64.getDecoder().decode(encryptedKeyB64));
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, AES);
        String sessionId = UUID.randomUUID().toString();
        long ttlMs = Math.max(1, sessionTtlSeconds) * 1000L;
        sessions.put(sessionId, new SecKey(aesKey, System.currentTimeMillis() + ttlMs));
        log.info("Registered new crypto session {}", sessionId);
        return sessionId;
    }

    public Optional<SecretKey> keyFor(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        SecKey sec = sessions.get(sessionId);
        if (sec == null) {
            return Optional.empty();
        }
        if (sec.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(sec.key());
    }

    /**
     * AES-GCM encrypt arbitrary plaintext, returning "base64(iv):base64(ciphertext)".
     */
    public static String aesEncrypt(SecretKey key, String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(AES_GCM);
        byte[] iv = newIv();
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ciphertext);
    }

    /**
     * Decrypt "base64(iv):base64(ciphertext)" produced by {@link #aesEncrypt}.
     */
    public static String aesDecrypt(SecretKey key, String token) throws Exception {
        String[] parts = token.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed encrypted payload");
        }
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = cipher.doFinal(ciphertext);
        return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] decryptRsa(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_PARAMS);
        return cipher.doFinal(data);
    }

    private static byte[] newIv() {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        return iv;
    }

    private record SecKey(SecretKey key, long expiresAt) {
    }
}

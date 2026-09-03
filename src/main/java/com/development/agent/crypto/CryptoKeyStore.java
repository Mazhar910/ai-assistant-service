package com.development.agent.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    private ScheduledExecutorService janitor;

    @Value("${app.crypto.session-ttl-seconds:3600}")
    private long sessionTtlSeconds;

    @Value("${app.crypto.keypair-path:}")
    private String keypairPath;

    /**
     * Loads an existing RSA keypair from {@code app.crypto.keypair-path} when configured,
     * otherwise generates a fresh one. Persisting the keypair lets the server restart
     * without invalidating established sessions (the disruptive default behaviour is to
     * regenerate on every boot).
     */
    public CryptoKeyStore() {
        try {
            KeyPair pair = loadOrCreateKeyPair();
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
            log.info("CryptoKeyStore initialized with RSA-2048 keypair ({})",
                    keypairPath == null || keypairPath.isBlank() ? "in-memory, regenerated on restart" : keypairPath);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize RSA keypair", e);
        }
    }

    private KeyPair loadOrCreateKeyPair() throws Exception {
        if (keypairPath == null || keypairPath.isBlank()) {
            return generateKeyPair();
        }
        Path privFile = Paths.get(keypairPath + ".pkcs8");
        Path pubFile = Paths.get(keypairPath + ".pub.der");
        if (Files.exists(privFile) && Files.exists(pubFile)) {
            byte[] privEnc = Files.readAllBytes(privFile);
            byte[] pubEnc = Files.readAllBytes(pubFile);
            PrivateKey priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(privEnc));
            PublicKey pub = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(pubEnc));
            return new KeyPair(pub, priv);
        }
        KeyPair generated = generateKeyPair();
        Path parent = privFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(privFile, generated.getPrivate().getEncoded());
        Files.write(pubFile, generated.getPublic().getEncoded());
        log.info("Persisted generated RSA keypair to {}", keypairPath);
        return generated;
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    public String publicKeyPem() {
        String b64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return b64;
    }

    /**
     * Periodically evicts expired session keys so the {@code sessions} map does not
     * grow unboundedly on long-lived deployments.
     */
    @PostConstruct
    void startJanitor() {
        long ttlMs = Math.max(1, sessionTtlSeconds) * 1000L;
        long sweepMs = Math.max(15_000L, ttlMs / 4);
        janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crypto-key-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleWithFixedDelay(this::sweepExpiredSessions, sweepMs, sweepMs, TimeUnit.MILLISECONDS);
        log.info("CryptoKeyStore janitor started (sweeps expired sessions every {} ms)", sweepMs);
    }

    private void sweepExpiredSessions() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, SecKey> entry : sessions.entrySet()) {
            if (entry.getValue().expiresAt() < now) {
                sessions.remove(entry.getKey(), entry.getValue());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Evicted {} expired crypto sessions", removed);
        }
    }

    @PreDestroy
    void shutdown() {
        if (janitor != null) {
            janitor.shutdownNow();
        }
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

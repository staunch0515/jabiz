package com.jabiz.security;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Encrypts TOTP secrets at rest (docs/design/10-security.md section 9; decision D28 item 1): AES-256-GCM with the
 * user's id as additional data, so that a stored secret decrypts only for the user it belongs to. The AES key and a
 * short key identifier are derived from the configured key material by HMAC-SHA256; the identifier is stored with each
 * secret so that a secret sealed under another key is recognised as such instead of failing obscurely.
 *
 * <p>Stored form: {@code v1:<key id>:<Base64 IV>:<Base64 ciphertext and tag>}. The caller supplies the random IV
 * (random numbers may block, which the runtime keeps off its event loops).
 */
public final class MfaSecretCipher {

    /** Minimum key material, as for the other platform keys. */
    public static final int MIN_KEY_BYTES = 32;
    public static final int IV_BYTES = 12;

    private static final String VERSION = "v1";
    private static final int TAG_BITS = 128;

    /** The stored secret was sealed under another key, or altered. */
    public static final class UnreadableSecretException extends RuntimeException {
        UnreadableSecretException(String message) {
            super(message);
        }
    }

    private final SecretKeySpec key;
    private final String keyId;

    public MfaSecretCipher(byte[] keyMaterial) {
        Objects.requireNonNull(keyMaterial, "keyMaterial must not be null");
        if (keyMaterial.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("The MFA key must have at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(hmac(keyMaterial, "jabiz.mfa.aes"), "AES");
        this.keyId = HexFormat.of().formatHex(hmac(keyMaterial, "jabiz.mfa.key-id")).substring(0, 16);
    }

    /** Identifies the key without revealing it. */
    public String keyId() {
        return keyId;
    }

    public String encrypt(byte[] secret, String userId, byte[] iv) {
        Objects.requireNonNull(secret, "secret must not be null");
        requireUser(userId);
        if (iv == null || iv.length != IV_BYTES) {
            throw new IllegalArgumentException("The IV must have " + IV_BYTES + " bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret);
            Base64.Encoder base64 = Base64.getEncoder();
            return VERSION + ":" + keyId + ":" + base64.encodeToString(iv) + ":" + base64.encodeToString(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    /**
     * The secret of {@code userId}.
     *
     * @throws UnreadableSecretException if it was sealed under another key, for another user, or altered
     */
    public byte[] decrypt(String stored, String userId) {
        requireUser(userId);
        String[] parts = stored == null ? new String[0] : stored.split(":", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new UnreadableSecretException("Not a stored MFA secret");
        }
        if (!keyId.equals(parts[1])) {
            throw new UnreadableSecretException("The MFA secret was sealed under another key");
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            byte[] iv = base64.decode(parts[2]);
            byte[] sealed = base64.decode(parts[3]);
            if (iv.length != IV_BYTES) {
                throw new UnreadableSecretException("Not a stored MFA secret");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed);
        } catch (IllegalArgumentException e) {
            throw new UnreadableSecretException("Not a stored MFA secret");
        } catch (javax.crypto.AEADBadTagException e) {
            throw new UnreadableSecretException("The MFA secret does not belong to this user or was altered");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

    private static byte[] hmac(byte[] key, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}

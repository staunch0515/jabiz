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
 * Seals secrets an application stores (docs/design/10-security.md section 6; decision D56): a merchant's mail server
 * password, say. AES-256-GCM, as {@link MfaSecretCipher} seals TOTP secrets, with a context of the caller's choosing
 * as additional data (the key of the row the secret belongs to), so that a sealed value opens only where it was
 * sealed for. The AES key and a short key identifier are derived from the configured key material
 * ({@code JABIZ_SEAL_KEY}) by HMAC-SHA256 under labels of their own, so the MFA key and this one never coincide; the
 * identifier is stored with each value so that one sealed under another key is recognised as such.
 *
 * <p>Stored form: {@code s1:<key id>:<Base64 IV>:<Base64 ciphertext and tag>}. The caller supplies the random IV
 * (random numbers may block, which the runtime keeps off its event loops).
 */
public final class SealedSecrets {

    /** Minimum key material, as for the other platform keys. */
    public static final int MIN_KEY_BYTES = 32;
    public static final int IV_BYTES = 12;

    private static final String VERSION = "s1";
    private static final int TAG_BITS = 128;

    /** The stored value was sealed under another key or for another context, or altered. */
    public static final class UnreadableSecretException extends RuntimeException {
        UnreadableSecretException(String message) {
            super(message);
        }
    }

    private final SecretKeySpec key;
    private final String keyId;

    public SealedSecrets(byte[] keyMaterial) {
        Objects.requireNonNull(keyMaterial, "keyMaterial must not be null");
        if (keyMaterial.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("The seal key must have at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(hmac(keyMaterial, "jabiz.seal.aes"), "AES");
        this.keyId = HexFormat.of().formatHex(hmac(keyMaterial, "jabiz.seal.key-id")).substring(0, 16);
    }

    /** Identifies the key without revealing it. */
    public String keyId() {
        return keyId;
    }

    /** Whether a value is one this class stored (under any key). */
    public static boolean isSealed(String value) {
        return value != null && value.startsWith(VERSION + ":") && value.split(":", -1).length == 4;
    }

    /**
     * @param context what the secret belongs to (a row's key); it opens for the same context only
     * @param iv      {@value #IV_BYTES} random bytes, fresh for every call
     */
    public String seal(byte[] secret, String context, byte[] iv) {
        Objects.requireNonNull(secret, "secret must not be null");
        requireContext(context);
        if (iv == null || iv.length != IV_BYTES) {
            throw new IllegalArgumentException("The IV must have " + IV_BYTES + " bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret);
            Base64.Encoder base64 = Base64.getEncoder();
            return VERSION + ":" + keyId + ":" + base64.encodeToString(iv) + ":" + base64.encodeToString(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    /**
     * The secret sealed for {@code context}.
     *
     * @throws UnreadableSecretException if it was sealed under another key, for another context, or altered
     */
    public byte[] open(String stored, String context) {
        requireContext(context);
        String[] parts = stored == null ? new String[0] : stored.split(":", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new UnreadableSecretException("Not a sealed secret");
        }
        if (!keyId.equals(parts[1])) {
            throw new UnreadableSecretException("The secret was sealed under another key");
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            byte[] iv = base64.decode(parts[2]);
            byte[] sealed = base64.decode(parts[3]);
            if (iv.length != IV_BYTES) {
                throw new UnreadableSecretException("Not a sealed secret");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed);
        } catch (IllegalArgumentException e) {
            throw new UnreadableSecretException("Not a sealed secret");
        } catch (javax.crypto.AEADBadTagException e) {
            throw new UnreadableSecretException("The secret does not belong to this context or was altered");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    private static void requireContext(String context) {
        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("context must not be blank");
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

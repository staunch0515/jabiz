package com.jabiz.integrity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The key that signs the seal chain (docs/design/21-audit-retention.md section 2): HMAC-SHA256 over each block, so
 * that whoever can change the database cannot recompute the chain without it. Its id - derived from the key, not the
 * key - is stored with each block to tell which key signed it.
 */
public final class IntegrityKey {

    /** Shortest key accepted, in bytes. */
    public static final int MIN_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;
    private final String id;

    public IntegrityKey(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < MIN_BYTES) {
            throw new IllegalArgumentException("The integrity key must have at least " + MIN_BYTES + " bytes");
        }
        this.key = key.clone();
        this.id = sign("jabiz-integrity-key-id").substring(0, 16);
    }

    /** Hex HMAC-SHA256 of the text. */
    public String sign(String text) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** 16 hex characters that identify the key without revealing it. */
    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IntegrityKey that && Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "IntegrityKey[id=" + id + "]";
    }
}

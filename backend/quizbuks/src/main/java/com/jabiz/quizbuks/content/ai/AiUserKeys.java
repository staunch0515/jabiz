package com.jabiz.quizbuks.content.ai;

import com.jabiz.integrity.IntegrityKey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The provider's user id of a sponsor (docs/quizbuks/plans/Q8-ai.md, A-11): Anthropic uses {@code metadata.user_id} to
 * tell abusers apart, so each sponsor gets the same opaque value, HMAC-SHA256 of their id under a key derived from the
 * integrity key ({@code JABIZ_INTEGRITY_KEY}) under its own label {@value #LABEL}. The provider cannot turn it back
 * into the sponsor's id; the operator can find the sponsor by computing it for a suspect, should the provider report
 * one.
 */
public final class AiUserKeys {

    static final String LABEL = "qb.ai-user";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public AiUserKeys(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < 32) {
            throw new IllegalArgumentException("The key must have at least 32 bytes");
        }
        this.key = key.clone();
    }

    public static AiUserKeys of(IntegrityKey integrityKey) {
        return new AiUserKeys(HexFormat.of().parseHex(integrityKey.sign(LABEL)));
    }

    /** {@code qb-} and 32 hexadecimal digits (the first 128 bits of the HMAC). */
    public String of(String sponsorId) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] digest = mac.doFinal(sponsorId.getBytes(StandardCharsets.UTF_8));
            return "qb-" + HexFormat.of().formatHex(Arrays.copyOf(digest, 16));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return "AiUserKeys[key=***]";
    }
}

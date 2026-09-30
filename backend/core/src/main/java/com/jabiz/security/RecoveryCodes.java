package com.jabiz.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One-time recovery codes for users who lost their authenticator (docs/design/10-security.md section 9): ten codes
 * of ten Base32 characters ({@code XXXXX-XXXXX}, 50 bits each), shown once. Only their SHA-256 is stored; each can be
 * used once instead of a TOTP code.
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    /** Random bytes per code: 50 of the 56 bits make the ten characters. */
    public static final int BYTES_PER_CODE = 7;

    private RecoveryCodes() {}

    /** Codes made from {@code COUNT * BYTES_PER_CODE} random bytes. */
    public static List<String> fromRandom(byte[] random) {
        Objects.requireNonNull(random, "random must not be null");
        if (random.length != COUNT * BYTES_PER_CODE) {
            throw new IllegalArgumentException("Recovery codes need " + COUNT * BYTES_PER_CODE + " random bytes");
        }
        List<String> codes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            byte[] part = new byte[BYTES_PER_CODE];
            System.arraycopy(random, i * BYTES_PER_CODE, part, 0, BYTES_PER_CODE);
            String text = Base32.encode(part).substring(0, 10);
            codes.add(text.substring(0, 5) + "-" + text.substring(5));
        }
        return List.copyOf(codes);
    }

    /** The stored form of a code: hex SHA-256 of its characters, upper case, without spaces and hyphens. */
    public static String hash(String code) {
        String normal = normalize(code);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normal.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Whether the text has the shape of a recovery code (TOTP codes are six digits and do not). */
    public static boolean looksLikeCode(String text) {
        if (text == null) {
            return false;
        }
        String normal = normalize(text);
        return normal.length() == 10 && normal.chars().allMatch(c -> Base32.ALPHABET.indexOf(c) >= 0);
    }

    /** The stored hash the code matches, if any. */
    public static Optional<String> match(String code, List<String> hashes) {
        if (!looksLikeCode(code)) {
            return Optional.empty();
        }
        byte[] submitted = hash(code).getBytes(StandardCharsets.US_ASCII);
        String found = null;
        for (String hash : hashes) {
            // Constant time over the stored hashes.
            if (MessageDigest.isEqual(submitted, hash.getBytes(StandardCharsets.US_ASCII))) {
                found = hash;
            }
        }
        return Optional.ofNullable(found);
    }

    private static String normalize(String code) {
        return code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }
}

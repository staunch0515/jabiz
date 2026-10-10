package com.jabiz.runtime.security.secret;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * What the stores of single-use secrets share (refresh tokens, OpenID Connect states, one-time tokens of mail): random
 * bytes drawn off the event loop, and the SHA-256 the database keeps instead of the secret.
 */
public final class SingleUseSecrets {

    private SingleUseSecrets() {}

    /** SecureRandom may block on the operating system's entropy source: never on an event loop. */
    public static Mono<byte[]> randomBytes(SecureRandom random, int length) {
        return Mono.fromCallable(() -> {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            return bytes;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    /** Equality of two stored hashes, in constant time. */
    public static boolean sameHash(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
            b.getBytes(StandardCharsets.US_ASCII));
    }
}

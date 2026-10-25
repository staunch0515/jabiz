package com.jabiz.runtime.security.secret;

import com.jabiz.security.SealedSecrets;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Seals and opens the secrets an application stores (decision D56): {@link SealedSecrets} under the key of
 * {@code jabiz.security.seal.key} ({@code JABIZ_SEAL_KEY}). Without a key (allowed: an application that stores no
 * secret needs none) sealing and opening fail; a {@code MailSenderSource} declared without one is a startup error
 * ({@code MailChecks}). Blocking where it draws random numbers: a process seals through the step {@link SealSecret},
 * the delivery opens on {@code boundedElastic}.
 */
public final class SecretSealer {

    private final SealedSecrets cipher;
    private final SecureRandom random = new SecureRandom();

    /** @param cipher null when no key is configured */
    public SecretSealer(SealedSecrets cipher) {
        this.cipher = cipher;
    }

    /** Whether a key is configured. */
    public boolean configured() {
        return cipher != null;
    }

    private SealedSecrets cipher() {
        if (cipher == null) {
            throw new IllegalStateException("jabiz.security.seal.key (environment variable JABIZ_SEAL_KEY) is not "
                + "set: the application cannot seal or open stored secrets");
        }
        return cipher;
    }

    /**
     * Seals a secret for a context (the key of the row it belongs to); blocking (a fresh random IV).
     *
     * @return the stored form, which opens for the same context only
     */
    public String seal(String secret, String context) {
        Objects.requireNonNull(secret, "secret must not be null");
        SealedSecrets sealed = cipher();
        byte[] iv = new byte[SealedSecrets.IV_BYTES];
        random.nextBytes(iv);
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        try {
            return sealed.seal(bytes, context, iv);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * @throws SealedSecrets.UnreadableSecretException sealed under another key or context, or altered
     */
    public String open(String stored, String context) {
        byte[] bytes = cipher().open(stored, context);
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}

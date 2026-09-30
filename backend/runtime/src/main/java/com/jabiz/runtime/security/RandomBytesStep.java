package com.jabiz.runtime.security;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.ProcessContext;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Puts fresh random bytes into the context. A blocking step: {@link SecureRandom} may wait for the operating system's
 * entropy, which must not happen on an event loop.
 */
@Component
public class RandomBytesStep implements BlockingStep<RandomBytesStep.Metadata, ProcessContext> {

    /**
     * @param key    context key receiving the bytes
     * @param length how many
     */
    public record Metadata(String key, int length) {
        public Metadata {
            Objects.requireNonNull(key, "key must not be null");
            if (length <= 0) {
                throw new IllegalArgumentException("length must be positive");
            }
        }
    }

    private final SecureRandom random = new SecureRandom();

    @Override
    public void run(Metadata metadata, ProcessContext ctx) {
        byte[] bytes = new byte[metadata.length()];
        random.nextBytes(bytes);
        ctx.put(metadata.key(), bytes);
    }
}

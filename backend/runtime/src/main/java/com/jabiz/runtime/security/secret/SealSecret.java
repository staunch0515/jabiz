package com.jabiz.runtime.security.secret;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.process.BlockingStep;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Function;

/**
 * Seals a secret a process is about to store (decision D56): a merchant's mail server password, say. The process
 * writes the sealed value into its entity; the platform opens it where it is used, for the same context (the key of
 * the row). A blank secret seals to null. Blocking: the IV is drawn from {@link java.security.SecureRandom}.
 * <pre>{@code
 * .step("Seal the password", SealSecret.of(ctx -> input(ctx).password(), ctx -> input(ctx).sponsorId(), SEALED))
 * }</pre>
 */
@Component
public class SealSecret<C extends ProcessContext> implements BlockingStep<SealSecret.Metadata<C>, C> {

    /**
     * @param secret    the secret as given (a {@code @Sensitive} component of the input, usually); null or blank: none
     * @param context   what it belongs to, as the opener will name it; never blank
     * @param targetKey the context key receiving the sealed value (null for none)
     */
    public record Metadata<C>(Function<C, String> secret, Function<C, String> context, String targetKey) {
        public Metadata {
            Objects.requireNonNull(secret, "secret must not be null");
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, String> secret,
        Function<C, String> context, String targetKey) {
        return StepSpec.of(SealSecret.class, new Metadata<>(secret, context, targetKey));
    }

    private final SecretSealer sealer;

    public SealSecret(SecretSealer sealer) {
        this.sealer = sealer;
    }

    @Override
    public void run(Metadata<C> metadata, C ctx) {
        String secret = metadata.secret().apply(ctx);
        if (secret == null || secret.isBlank()) {
            ctx.put(metadata.targetKey(), null);
            return;
        }
        String context = metadata.context().apply(ctx);
        if (context == null || context.isBlank()) {
            throw new IllegalStateException("The context of a sealed secret must not be blank");
        }
        ctx.put(metadata.targetKey(), sealer.seal(secret, context));
    }
}

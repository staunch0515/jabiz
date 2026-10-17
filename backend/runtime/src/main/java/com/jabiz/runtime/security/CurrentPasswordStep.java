package com.jabiz.runtime.security;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.ProcessContext;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Checks a password given in a process against a user's BCrypt hash and puts whether it matched into the context
 * (blocking: BCrypt is slow on purpose). The plain password does not stay in the context. Used where somebody proves
 * that they are who registered an account (docs/design/10-security.md section 15.7), and where a signed-in user changes
 * their own password (section 15.8; that process counts a mismatch towards the lock, this step does not).
 */
@Component
public class CurrentPasswordStep implements BlockingStep<CurrentPasswordStep.Metadata, ProcessContext>,
    PasswordHashingLimit.HashingStep {

    /**
     * @param passwordKey context key of the password; removed by the step
     * @param hash        the hash to check against, computed on the context (null: no password matches)
     * @param resultKey   context key receiving {@code true} or {@code false}
     * @param when        whether to check at all (null: always); otherwise nothing is put under the result key
     */
    public record Metadata(String passwordKey, Function<ProcessContext, String> hash, String resultKey,
        Predicate<ProcessContext> when) {
        public Metadata {
            Objects.requireNonNull(passwordKey, "passwordKey must not be null");
            Objects.requireNonNull(hash, "hash must not be null");
            Objects.requireNonNull(resultKey, "resultKey must not be null");
        }
    }

    private final PasswordHasher hasher;

    public CurrentPasswordStep(PasswordHasher hasher) {
        this.hasher = hasher;
    }

    @Override
    public void run(Metadata metadata, ProcessContext ctx) {
        boolean wanted = metadata.when() == null || metadata.when().test(ctx);
        String password = ctx.get(metadata.passwordKey(), String.class);
        ctx.put(metadata.passwordKey(), null);
        if (!wanted) {
            return;
        }
        ctx.put(metadata.resultKey(), password != null && hasher.matches(password, metadata.hash().apply(ctx)));
    }
}

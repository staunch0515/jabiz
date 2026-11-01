package com.jabiz.runtime.security;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.security.SignUpAttempt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Asks the applications' sign-up guards (decision D66) before a process gives an account an entry's registration
 * roles; a refusal fails the process with {@link SignUpRefusedException} (403 {@code SIGN_UP_REFUSED}), so nothing it
 * would have written is written and a mailed link stays usable. Skipped when {@code when} says the process gives no
 * roles after all, and once the process has a violation (which it answers instead).
 */
@Component
public class SignUpGuardStep<C extends ProcessContext> implements StepHandler<SignUpGuardStep.Metadata<C>, C> {

    /**
     * @param path          how the account gets the roles
     * @param entry         the entry whose registration roles
     * @param userId        the existing user's id, or null for a new account
     * @param email         the account's address, or null
     * @param emailVerified whether the address is proven by now
     * @param when          whether the process is about to give roles at all
     */
    public record Metadata<C extends ProcessContext>(SignUpAttempt.Path path, Function<C, String> entry,
        Function<C, Object> userId, Function<C, String> email, Predicate<C> emailVerified, Predicate<C> when) {

        public Metadata {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(email, "email must not be null");
            Objects.requireNonNull(emailVerified, "emailVerified must not be null");
            Objects.requireNonNull(when, "when must not be null");
        }
    }

    private final SignUpGuards guards;

    public SignUpGuardStep(SignUpGuards guards) {
        this.guards = guards;
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> spec(Metadata<C> metadata) {
        return StepSpec.of(SignUpGuardStep.class, metadata);
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        if (!guards.any() || ctx.hasViolations() || !metadata.when().test(ctx)) {
            return Mono.empty();
        }
        String entry = metadata.entry().apply(ctx);
        if (entry == null) {
            return Mono.empty();
        }
        Object user = metadata.userId().apply(ctx);
        return guards.check(metadata.path(), entry, user == null ? null : String.valueOf(user),
                metadata.email().apply(ctx), metadata.emailVerified().test(ctx), ctx.opTime())
            .flatMap(decision -> decision.allowed() ? Mono.<Void>empty()
                : Mono.error(new SignUpRefusedException(decision.reason(), "A sign-up guard refused the registration"
                    + " roles of entry " + entry)));
    }
}

package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.SignInGuards;
import com.jabiz.security.LoginOutcome;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Asks the applications' sign-in guards about an attempt whose credentials and roles were accepted (decision D36
 * item 6): after the role check, before any token or challenge is issued, on every sign-in path and in the second
 * step. A refusal becomes {@link LoginOutcome#REFUSED}: recorded, not counted as a failure.
 */
@Component
public class SignInGuardStep<C extends LoginContext> implements StepHandler<NoMetadata, C> {

    private final SignInGuards guards;

    public SignInGuardStep(SignInGuards guards) {
        this.guards = guards;
    }

    public static <C extends LoginContext> StepSpec<NoMetadata, C> spec() {
        return StepSpec.of(SignInGuardStep.class, NoMetadata.INSTANCE);
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, C ctx) {
        if (ctx.outcome() != LoginOutcome.SUCCESS || !guards.any()) {
            return Mono.empty();
        }
        EntityInstance user = ctx.user().orElseThrow();
        return guards.check(String.valueOf(user.id()), user.get("userName"), ctx.entry().name(),
                ctx.access().roles(), ctx.emailVerified(), ctx.guardFactor(), ctx.opTime())
            .doOnNext(decision -> {
                if (!decision.allowed()) {
                    ctx.setOutcome(LoginOutcome.REFUSED);
                }
            })
            .then();
    }
}

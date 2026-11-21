package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.protect.ProtectedGrants;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/**
 * Refuses the first launch's correction of an address (decision D83) unless the user's history, as the chain of
 * ownership judges it ({@code CredentialChain}), says nobody could have used the account: never a password, a link, a
 * second factor or a proven address, and no change of the address by anyone else (422 {@code INVITATION_NOT_NEEDED}).
 * The same judgement that lets the invitation go to the corrected address. A platform I/O step.
 */
@Component
public class CorrectableAddress<C extends ProcessContext> implements StepHandler<CorrectableAddress.Metadata<C>, C> {

    public record Metadata<C>(Function<C, UUID> user) {}

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, UUID> user) {
        return StepSpec.of(CorrectableAddress.class, new Metadata<>(user));
    }

    private final ProtectedGrants grants;

    public CorrectableAddress(ProtectedGrants grants) {
        this.grants = grants;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> grants.credentials(metadata.user().apply(ctx))).doOnNext(credentials -> {
            if (!credentials.correctable()) {
                ctx.reject(new Violation("userId", PlatformErrorCodes.INVITATION_NOT_NEEDED, "The account was used,"
                    + " or someone else changed its address: its address is changed in the administration"));
            }
        }).then();
    }
}

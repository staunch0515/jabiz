package com.jabiz.runtime.security.protect;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.Rows;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Refuses a signed-in user's request to verify their address (422 {@code EMAIL_SET_BY_ANOTHER}) when someone else
 * changed that address after the account was created and someone else may know a way into the account (decision D48,
 * review M-b; {@link ProtectedGrants.Credentials#addressVerifiable()}): the one asking may not be the user, and the
 * link would go to whoever chose the address - who could then reset the password. An address entered when the account
 * was created is verified by its user, signed in, as before. Nothing is checked when the user is null (an anonymous
 * request) or protected grants are off. A platform I/O step.
 *
 * <p>{@link #unchanged} is the stricter check of an invitation (decision D63; 422 {@code INVITATION_ADDRESS_CHANGED}):
 * an administrator, not the user, asks for the link, so it goes only to the address entered when the account was
 * created and changed by nobody since - not by someone else, nor "by the user" in a session someone else may have
 * opened with a password they knew - whether or not protected grants are on.
 */
@Component
public class AddressVerifiable<C extends ProcessContext> implements StepHandler<AddressVerifiable.Metadata<C>, C> {

    /** @param invitation the stricter check of {@link #unchanged} */
    public record Metadata<C>(Function<C, Object> user, boolean invitation) {
        public Metadata {
            Objects.requireNonNull(user, "user must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, Object> user) {
        return StepSpec.of(AddressVerifiable.class, new Metadata<>(user, false));
    }

    /** Refuses an invitation to an address someone else changed after the account was created (decision D63). */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> unchanged(Function<C, Object> user) {
        return StepSpec.of(AddressVerifiable.class, new Metadata<>(user, true));
    }

    private final ProtectedGrants grants;

    public AddressVerifiable(ProtectedGrants grants) {
        this.grants = grants;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            UUID user = Rows.uuid(metadata.user().apply(ctx));
            if (user == null || (!grants.enabled() && !metadata.invitation())) {
                return Mono.empty();
            }
            return grants.credentials(user).doOnNext(credentials -> {
                if (metadata.invitation()) {
                    if (!credentials.addressAsCreated()) {
                        ctx.reject(new Violation("userId", PlatformErrorCodes.INVITATION_ADDRESS_CHANGED,
                            "The address was changed after the account was created: an invitation goes only to the"
                                + " address entered then"));
                    }
                } else if (!credentials.addressVerifiable()) {
                    ctx.reject(new Violation(null, PlatformErrorCodes.EMAIL_SET_BY_ANOTHER,
                        "The address was changed by someone else, who may know a way into this account"));
                }
            }).then();
        });
    }
}

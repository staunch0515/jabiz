package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ComputeStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.PasskeyEntities;
import com.jabiz.runtime.security.PasskeyVerifier;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sign-in with a passkey (docs/design/10-security.md section 15.16; decision D71), after the platform took back the
 * challenge the answer is for: the passkey of the answer's credential id and its user, the signature checked against
 * the stored public key, then as every sign-in a locked, disabled or role-less user is refused, then the entry's checks
 * and guards. A user-verifying passkey is two factors: no second step follows. The second step after a password and a
 * step-up run the same process for the user they expect.
 *
 * <p>An answer that does not hold (unknown credential, another user's, a bad signature) leaves no login record: the
 * credential's id is no secret, so counting such answers would let anyone lock anyone's account (decision D71 item 2);
 * the requests are rate limited instead. A held answer records the counter and the last use, whatever follows.
 */
public final class SponsorPasskeySignInProcess {

    public static final String NAME = "SPONSOR_PASSKEY_SIGN_IN";

    public static final ProcessDefinition<SponsorPasskeySignInInput, SponsorSignInOutput, PasskeyContext> DEFINITION =
        ProcessDefinition.define(NAME, 1, SponsorPasskeySignInInput.class, SponsorSignInOutput.class,
            PasskeyContext.class, pb -> pb
                .description("Sign-in with a passkey: the passkey's user, the roles, and the attempt.")
                .permissions(SecurityPermissions.SIGN_IN)
                .entryOnly()
                .contextFactory(PasskeyContext::new)
                .outputMapper(LoginContext::output)

                .step("Resolve the entry", SignInEntryStep.<PasskeyContext>spec())
                .step("Load the passkey", QueryEntities.<PasskeyContext>of(PasskeyEntities.PASSKEY_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.Eq("credentialId", ctx.input().credentialId()),
                        "credentialId"), PasskeyContext.KEY_PASSKEY))
                .step("Load the user", QueryEntities.<PasskeyContext>of(SecurityEntities.USER_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.In("userId", ctx.passkeyUserIds()), "userId"),
                    LoginContext.KEY_USERS))
                .step("Load the latest login record", QueryEntities.<PasskeyContext>of(
                    SecurityEntities.LOGIN_RECORD_DATASET, ctx -> Rbac.latestLoginRecordOf(ctx.userId()),
                    LoginContext.KEY_LATEST_RECORD))
                .step("Check the passkey", PasskeyCheckStep.class, NoMetadata.INSTANCE)
                .steps(SponsorSignInProcess::accessSteps)
                .steps(SponsorSignInProcess::entrySteps)
                .steps(SponsorSignInProcess::secondFactorSteps)
                .step("Create the login record", PasskeyLoginRecordStep.class, NoMetadata.INSTANCE)
                .compute("Record the use", (metadata, ctx) -> recordUse(ctx)));

    private SponsorPasskeySignInProcess() {}

    /** The counter the answer carried and the time, on the passkey whose signature held. */
    private static void recordUse(PasskeyContext ctx) {
        if (!ctx.verified()) {
            return;
        }
        EntityInstance passkey = ctx.passkey().orElseThrow();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("signCount", ctx.signCount());
        changes.put("backedUp", ctx.backedUp());
        changes.put("lastUsedAt", ctx.opTime());
        ctx.changes().update(PasskeyEntities.PASSKEY, passkey.id(), passkey.version(), changes);
    }

    /**
     * Checks the answer against the passkey, then whether its user may sign in at all: not locked, enabled. The
     * entry must take passkeys. An answer that does not hold leaves the outcome open: no record.
     */
    @Component
    public static class PasskeyCheckStep implements ComputeStep<NoMetadata, PasskeyContext> {

        private static final Logger log = LoggerFactory.getLogger(PasskeyCheckStep.class);

        private final PasskeyVerifier verifier;
        private final LoginAttemptPolicy policy;

        public PasskeyCheckStep(PasskeyVerifier verifier, LoginAttemptPolicy policy) {
            this.verifier = verifier;
            this.policy = policy;
        }

        @Override
        public void compute(NoMetadata metadata, PasskeyContext ctx) {
            if (ctx.user().isEmpty() || ctx.entry() == null || !ctx.entry().passkeys()) {
                return;
            }
            EntityInstance user = ctx.user().get();
            EntityInstance passkey = ctx.passkey().orElseThrow();
            try {
                PasskeyVerifier.Asserted asserted = verifier.authenticate(ctx.input().assertion(),
                    ctx.input().challenge(), passkey.get("credentialData"),
                    ((Number) passkey.get("signCount")).longValue(), UUID.fromString(String.valueOf(user.id())));
                ctx.setVerified(asserted.signCount(), asserted.backedUp());
            } catch (PasskeyVerifier.InvalidPasskeyException refused) {
                log.info("Passkey {} of user {} refused: {}", passkey.id(), user.id(), refused.getMessage());
                // No record: the user stays unloaded for the steps that follow.
                ctx.put(LoginContext.KEY_USERS, List.of());
                return;
            }
            if (policy.isLocked(ctx.latestState(), ctx.opTime())) {
                ctx.setOutcome(LoginOutcome.LOCKED);
            } else if (!Rbac.enabled(user)) {
                ctx.setOutcome(LoginOutcome.DISABLED);
            } else {
                // Provisional: the role check comes next.
                ctx.setOutcome(LoginOutcome.SUCCESS);
            }
        }
    }

    /** {@link LoginRecordStep} for sign-ins with a passkey. */
    @Component
    public static class PasskeyLoginRecordStep implements ComputeStep<NoMetadata, PasskeyContext> {

        private final LoginAttemptPolicy policy;

        public PasskeyLoginRecordStep(LoginAttemptPolicy policy) {
            this.policy = policy;
        }

        @Override
        public void compute(NoMetadata metadata, PasskeyContext ctx) {
            LoginRecordStep.register(policy, ctx);
        }
    }
}

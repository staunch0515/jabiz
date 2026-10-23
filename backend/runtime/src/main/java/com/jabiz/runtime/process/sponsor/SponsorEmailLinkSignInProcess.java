package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sign-in with a mailed one-time link (docs/design/10-security.md section 15.10; decision D54), after the platform
 * used the link: like {@link SponsorOidcSignInProcess} it records the attempt ({@code factor = EMAIL_LINK}) and ends
 * normally on refusals: a locked, disabled or role-less user is refused, then the entry's checks and guards, then the
 * second factor as for passwords (the mailbox never counts as one). No password is guessed here, so refusals do not
 * count towards the lock, but a lock in force applies.
 */
public final class SponsorEmailLinkSignInProcess {

    public static final String NAME = "SPONSOR_EMAIL_LINK_SIGN_IN";

    public static final ProcessDefinition<SponsorEmailLinkSignInInput, SponsorSignInOutput, EmailLinkContext>
        DEFINITION = ProcessDefinition.define(NAME, 1, SponsorEmailLinkSignInInput.class, SponsorSignInOutput.class,
            EmailLinkContext.class, pb -> pb
                .description("Sign-in with a mailed link: the user, the roles, and the attempt.")
                .permissions(SecurityPermissions.SIGN_IN)
                .entryOnly()
                .contextFactory(EmailLinkContext::new)
                .outputMapper(LoginContext::output)

                .step("Resolve the entry", SignInEntryStep.<EmailLinkContext>spec())
                .step("Load the user", QueryEntities.<EmailLinkContext>of(SecurityEntities.USER_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.In("userId", userIds(ctx.input().userId())), "userId"),
                    LoginContext.KEY_USERS))
                .step("Load the latest login record", QueryEntities.<EmailLinkContext>of(
                    SecurityEntities.LOGIN_RECORD_DATASET, ctx -> Rbac.latestLoginRecordOf(ctx.userId()),
                    LoginContext.KEY_LATEST_RECORD))
                .step("Check the account", EmailLinkAccountStep.class, NoMetadata.INSTANCE)
                .steps(SponsorSignInProcess::accessSteps)
                .steps(SponsorSignInProcess::entrySteps)
                .steps(SponsorSignInProcess::secondFactorSteps)
                .step("Create the login record", EmailLinkLoginRecordStep.class, NoMetadata.INSTANCE));

    private SponsorEmailLinkSignInProcess() {}

    private static List<Object> userIds(String userId) {
        try {
            return userId == null ? List.of() : List.of(UUID.fromString(userId));
        } catch (IllegalArgumentException notAUser) {
            return List.of();
        }
    }

    /** Whether the user may sign in at all: not locked, enabled. */
    @Component
    public static class EmailLinkAccountStep implements com.jabiz.process.ComputeStep<NoMetadata, EmailLinkContext> {

        private final LoginAttemptPolicy policy;

        public EmailLinkAccountStep(LoginAttemptPolicy policy) {
            this.policy = policy;
        }

        @Override
        public void compute(NoMetadata metadata, EmailLinkContext ctx) {
            ctx.user().ifPresent(user -> {
                if (policy.isLocked(ctx.latestState(), ctx.opTime())) {
                    ctx.setOutcome(LoginOutcome.LOCKED);
                } else if (!Rbac.enabled(user)) {
                    ctx.setOutcome(LoginOutcome.DISABLED);
                } else {
                    // Provisional: the role check comes next.
                    ctx.setOutcome(LoginOutcome.SUCCESS);
                }
            });
        }
    }

    /** {@link LoginRecordStep} for sign-ins with a mailed link. */
    @Component
    public static class EmailLinkLoginRecordStep implements com.jabiz.process.ComputeStep<NoMetadata, EmailLinkContext> {

        private final LoginAttemptPolicy policy;

        public EmailLinkLoginRecordStep(LoginAttemptPolicy policy) {
            this.policy = policy;
        }

        @Override
        public void compute(NoMetadata metadata, EmailLinkContext ctx) {
            LoginRecordStep.register(policy, ctx);
        }
    }
}

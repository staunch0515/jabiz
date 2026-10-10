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

import java.util.List;

/**
 * Sign-in through an OpenID Connect provider (docs/design/10-security.md section 12; decision D28 item 6), after the
 * platform has checked the provider's ID token. Like {@link SponsorSignInProcess} it records the attempt
 * ({@code factor = OIDC}) and ends normally on refusals: a subject nobody linked leaves no record (nobody is signed
 * up by a provider); a locked, disabled or role-less user is refused; then the second factor as for passwords, unless
 * the provider's authentication counts as one. No password is guessed here, so refusals do not count towards the
 * lock, but a lock in force applies.
 */
public final class SponsorOidcSignInProcess {

    public static final String NAME = "SPONSOR_OIDC_SIGN_IN";

    public static final ProcessDefinition<SponsorOidcSignInInput, SponsorSignInOutput, OidcContext> DEFINITION =
        ProcessDefinition.define(NAME, 1, SponsorOidcSignInInput.class, SponsorSignInOutput.class, OidcContext.class,
            pb -> pb
                .description("Sign-in through an identity provider: the linked user, the roles, and the attempt.")
                .permissions(SecurityPermissions.SIGN_IN)
                .internal()
                .contextFactory(OidcContext::new)
                .outputMapper(LoginContext::output)

                .step("Resolve the entry", SignInEntryStep.<OidcContext>spec())
                .step("Load the linked account", QueryEntities.<OidcContext>of(
                    SecurityEntities.USER_IDENTITY_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("provider", ctx.input().provider()),
                        new QueryPredicate.Eq("subject", ctx.input().subject()))), "provider"),
                    OidcContext.KEY_IDENTITIES))
                .step("Load the user", QueryEntities.<OidcContext>of(SecurityEntities.USER_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.In("userId", ctx.linkedUserIds()), "userId"),
                    LoginContext.KEY_USERS))
                .step("Load the latest login record", QueryEntities.<OidcContext>of(
                    SecurityEntities.LOGIN_RECORD_DATASET, ctx -> Rbac.latestLoginRecordOf(ctx.userId()),
                    LoginContext.KEY_LATEST_RECORD))
                .step("Check the account", OidcAccountStep.class, NoMetadata.INSTANCE)
                .steps(SponsorSignInProcess::accessSteps)
                .steps(SponsorSignInProcess::entrySteps)
                .steps(SponsorSignInProcess::secondFactorSteps)
                .step("Create the login record", OidcLoginRecordStep.class, NoMetadata.INSTANCE));

    private SponsorOidcSignInProcess() {}

    /** Whether the linked user may sign in at all: not locked, enabled. */
    @org.springframework.stereotype.Component
    public static class OidcAccountStep implements com.jabiz.process.ComputeStep<NoMetadata, OidcContext> {

        private final LoginAttemptPolicy policy;

        public OidcAccountStep(LoginAttemptPolicy policy) {
            this.policy = policy;
        }

        @Override
        public void compute(NoMetadata metadata, OidcContext ctx) {
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
}

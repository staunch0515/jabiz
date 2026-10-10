package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.MfaCodes;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.security.LoginOutcome;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The second factor of a sign-in, or a step-up (docs/design/10-security.md sections 9 and 10; decision D28 item 2):
 * like {@link SponsorSignInProcess} a process whose refusals end normally, so that its login record counts towards
 * the lock. The user is named by id (from a verified challenge or the caller's access token); the roles are checked
 * again, since they may have changed since the password step.
 */
public final class SponsorMfaVerifyProcess {

    public static final String NAME = "SPONSOR_MFA_VERIFY";

    public static final ProcessDefinition<SponsorMfaVerifyInput, SponsorSignInOutput, MfaContext> DEFINITION =
        ProcessDefinition.define(NAME, 1, SponsorMfaVerifyInput.class, SponsorSignInOutput.class, MfaContext.class,
            pb -> pb
                .description("Second factor: checks a TOTP or recovery code, the roles, and records the attempt.")
                .permissions(SecurityPermissions.SIGN_IN)
                .internal()
                .contextFactory(MfaContext::new)
                .outputMapper(LoginContext::output)

                .step("Resolve the entry", SignInEntryStep.<MfaContext>spec())
                .step("Load the user", QueryEntities.<MfaContext>of(SecurityEntities.USER_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.Eq("userId", UUID.fromString(ctx.userIdArgument())),
                        "userId"),
                    LoginContext.KEY_USERS))
                .step("Load the latest login record", QueryEntities.<MfaContext>of(
                    SecurityEntities.LOGIN_RECORD_DATASET, ctx -> Rbac.latestLoginRecordOf(ctx.userId()),
                    LoginContext.KEY_LATEST_RECORD))
                .step("Load the later sign-ins", QueryEntities.<MfaContext>of(SecurityEntities.LOGIN_RECORD_DATASET,
                    ctx -> ctx.challengeAttemptNo() == null || ctx.userId() == null
                        ? Rbac.all(new QueryPredicate.In("userId", List.of()), "attemptNo")
                        : Rbac.all(new QueryPredicate.And(List.of(new QueryPredicate.Eq("userId", ctx.userId()),
                            new QueryPredicate.Gt("attemptNo", ctx.challengeAttemptNo()))), "attemptNo"),
                    MfaContext.KEY_LATER_RECORDS))
                .step("Load the second factor", QueryEntities.<MfaContext>of(SecurityEntities.USER_MFA_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.In("userId",
                        ctx.userId() == null ? List.of() : List.of(ctx.userId())), "userId"),
                    LoginContext.KEY_MFA))
                .step("Check the second factor", MfaCheckStep.class, NoMetadata.INSTANCE)

                .steps(SponsorSignInProcess::accessSteps)
                .steps(SponsorSignInProcess::entrySteps)

                .compute("Use up the recovery code", (metadata, ctx) -> {
                    // Spent only by a sign-in that succeeds.
                    if (ctx.usedRecoveryCode() == null || ctx.outcome() != LoginOutcome.SUCCESS) {
                        return;
                    }
                    EntityInstance mfa = ctx.confirmedMfa().orElseThrow();
                    List<String> left = MfaCodes.hashes(mfa.get("recoveryCodes")).stream()
                        .filter(hash -> !hash.equals(ctx.usedRecoveryCode())).toList();
                    ctx.changes().update(SecurityEntities.USER_MFA, mfa.id(), mfa.version(),
                        Map.of("recoveryCodes", MfaCodes.join(left)));
                })
                .step("Create the login record", MfaLoginRecordStep.class, NoMetadata.INSTANCE));

    private SponsorMfaVerifyProcess() {}
}

package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sign-in (docs/design/06-process.md section 10, docs/design/10-security.md sections 4 and 15), in these parts:
 * <ol>
 *   <li>authentication and user load: the user by name, or by verified e-mail address (decision D36 item 4), and the
 *       latest login record through their datasets, then the password check in a blocking step;</li>
 *   <li>role and access check: the user's roles in effect that the sign-in entry accepts, and their permissions; at
 *       least one enabled role;</li>
 *   <li>entry checks: a verified address where the entry requires one, then the applications' sign-in guards;</li>
 *   <li>second factor: a user who has set one up continues with {@link SponsorMfaVerifyProcess}
 *       ({@link LoginOutcome#MFA_REQUIRED}); one whose role requires it but who has none sets it up first
 *       ({@link LoginOutcome#MFA_ENROLLMENT_REQUIRED}, docs/design/10-security.md section 9);</li>
 *   <li>login record: registered as a change, committed by the platform with the operation.</li>
 * </ol>
 * A refused attempt is not a failed process: its login record must be committed to count towards the lock. The
 * caller ({@code POST /api/auth/login}) answers every outcome but {@link LoginOutcome#SUCCESS} alike, except the two
 * that follow a right password ({@link LoginOutcome#REFUSED}, {@link LoginOutcome#EMAIL_NOT_VERIFIED}).
 */
public final class SponsorSignInProcess {

    public static final String NAME = "SPONSOR_SIGN_IN";

    public static final ProcessDefinition<SponsorSignInInput, SponsorSignInOutput, LoginContext> DEFINITION =
        ProcessDefinition.define(NAME, 1,
            SponsorSignInInput.class, SponsorSignInOutput.class, LoginContext.class, pb -> pb
                .description("Sign-in: verifies credentials, checks that a role is in effect and records the attempt.")
                .permissions(SecurityPermissions.SIGN_IN)
                .internal()
                .contextFactory(LoginContext::new)
                .outputMapper(LoginContext::output)

                .step("Resolve the entry", SignInEntryStep.<LoginContext>spec())
                .step("Load the user", QueryEntities.<LoginContext>of(SecurityEntities.USER_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userName", ctx.identifier())).limit(1)
                        .build(),
                    LoginContext.KEY_USERS))
                // No user of that name: the name may be a verified address (decision D36 item 4). An unverified
                // address is like an unknown name.
                .step("Load the user by e-mail address", UserByEmailStep.class, NoMetadata.INSTANCE)
                .step("Load the latest login record", QueryEntities.<LoginContext>of(
                    SecurityEntities.LOGIN_RECORD_DATASET, ctx -> Rbac.latestLoginRecordOf(ctx.userId()),
                    LoginContext.KEY_LATEST_RECORD))
                .step("Authentication", AuthenticationStep.class, NoMetadata.INSTANCE)

                .steps(SponsorSignInProcess::accessSteps)
                .steps(SponsorSignInProcess::entrySteps)

                .steps(SponsorSignInProcess::secondFactorSteps)

                .step("Create the login record", LoginRecordStep.class, NoMetadata.INSTANCE));

    private SponsorSignInProcess() {}

    /**
     * The role and access check, shared with the second step of a sign-in: the user's roles in effect that the
     * entry accepts and their permissions (so permissions, the second-factor requirement and the data period all come
     * from those roles only, decision D36 item 1); a user without such an enabled role is refused
     * ({@link LoginOutcome#NO_ROLE}).
     */
    static <I, O, C extends LoginContext> void accessSteps(ProcessDefinitionBuilder<I, O, C> pb) {
        pb.step("Load the role assignments", QueryEntities.<C>of(SecurityEntities.USER_ROLE_DATASET,
                ctx -> Rbac.assignmentsOf(ctx.outcome() == LoginOutcome.SUCCESS ? ctx.userId() : null),
                LoginContext.KEY_ASSIGNMENTS))
            .step("Load the roles", QueryEntities.<C>of(SecurityEntities.ROLE_DATASET,
                ctx -> Rbac.rolesOf(ctx.list(LoginContext.KEY_ASSIGNMENTS)), LoginContext.KEY_ROLES))
            .step("Load the permissions", QueryEntities.<C>of(SecurityEntities.ROLE_PERMISSION_DATASET,
                ctx -> Rbac.permissionsOf(ctx.acceptedRoles()), LoginContext.KEY_ROLE_PERMISSIONS))
            .compute("Role and access check", (metadata, ctx) -> {
                if (ctx.outcome() != LoginOutcome.SUCCESS) {
                    return;
                }
                Rbac.Access access = Rbac.access(ctx.list(LoginContext.KEY_ASSIGNMENTS), ctx.acceptedRoles(),
                    ctx.list(LoginContext.KEY_ROLE_PERMISSIONS));
                if (access.roles().isEmpty()) {
                    ctx.setOutcome(LoginOutcome.NO_ROLE);
                } else {
                    ctx.setAccess(access);
                }
            });
    }

    /**
     * What the entry asks beyond the roles (decision D36 items 3 and 6), shared by every sign-in path: a verified
     * e-mail address where the entry requires one ({@link LoginOutcome#EMAIL_NOT_VERIFIED}), then the applications'
     * sign-in guards ({@link LoginOutcome#REFUSED}). Neither counts as a failure.
     */
    static <I, O, C extends LoginContext> void entrySteps(ProcessDefinitionBuilder<I, O, C> pb) {
        pb.compute("Verified e-mail address", (metadata, ctx) -> {
                if (ctx.outcome() == LoginOutcome.SUCCESS && ctx.entry().requireVerifiedEmail()
                    && !ctx.emailVerified()) {
                    ctx.setOutcome(LoginOutcome.EMAIL_NOT_VERIFIED);
                }
            })
            .step("Sign-in guards", SignInGuardStep.<C>spec());
    }

    /**
     * Whether the sign-in needs a second factor next (docs/design/10-security.md section 9): a user who has set one
     * up does, unless the attempt passed one already (an identity provider's, section 12); a user without one whose
     * role requires one sets it up first.
     */
    static <I, O, C extends LoginContext> void secondFactorSteps(ProcessDefinitionBuilder<I, O, C> pb) {
        pb.step("Load the second factor", QueryEntities.<C>of(SecurityEntities.USER_MFA_DATASET,
                ctx -> Rbac.all(new QueryPredicate.In("userId", ctx.outcome() == LoginOutcome.SUCCESS
                    ? List.of(ctx.userId()) : List.of()), "userId"), LoginContext.KEY_MFA))
            .compute("Second factor needed", (metadata, ctx) -> {
                if (ctx.outcome() != LoginOutcome.SUCCESS || ctx.secondFactorPassed()) {
                    return;
                }
                if (ctx.confirmedMfa().isPresent()) {
                    ctx.setOutcome(LoginOutcome.MFA_REQUIRED);
                } else if (ctx.access().mfaRequired()) {
                    ctx.setOutcome(LoginOutcome.MFA_ENROLLMENT_REQUIRED);
                }
            });
    }

    /**
     * The attributes of a login record carrying {@code state}.
     *
     * @param factor  what the attempt was checked with, or null (an unlock)
     * @param mfaStep the last TOTP step accepted so far, or a negative number for none
     */
    public static Map<String, Object> record(Object userId, String userName, LoginOutcome outcome,
        LoginAttemptPolicy.State state, Instant time, String requestId, String factor, long mfaStep) {
        return record(userId, userName, outcome, state, time, requestId, factor, mfaStep, null);
    }

    /** As above, with where the attempt came from (decision D36 item 7); null for none (an unlock). */
    public static Map<String, Object> record(Object userId, String userName, LoginOutcome outcome,
        LoginAttemptPolicy.State state, Instant time, String requestId, String factor, long mfaStep,
        SignInSource source) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("userId", userId);
        attributes.put("userName", userName);
        attributes.put("attemptNo", state.attemptNo());
        attributes.put("outcome", outcome.name());
        attributes.put("failureCount", state.failureCount());
        attributes.put("lockedUntil", state.lockedUntil());
        attributes.put("attemptTime", time);
        attributes.put("requestId", requestId);
        attributes.put("factor", factor);
        attributes.put("mfaStep", mfaStep < 0 ? null : mfaStep);
        if (source != null) {
            attributes.put("entry", source.entryOrDefault());
            attributes.put("clientIp", source.clientIp());
            attributes.put("userAgent", source.userAgent());
        }
        return attributes;
    }
}

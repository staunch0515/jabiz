package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessDefinition;
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
import java.util.Map;

/**
 * Sign-in (docs/design/06-process.md section 10, docs/design/10-security.md section 4), in three parts:
 * <ol>
 *   <li>authentication and user load: the user and the latest login record through their datasets, then the
 *       password check in a blocking step;</li>
 *   <li>role and access check: the user's roles in effect and their permissions; at least one enabled role;</li>
 *   <li>login record: registered as a change, committed by the platform with the operation.</li>
 * </ol>
 * A refused attempt is not a failed process: its login record must be committed to count towards the lock. The
 * caller ({@code POST /api/auth/login}) answers every outcome but {@link LoginOutcome#SUCCESS} alike.
 */
public final class SponsorSignInProcess {

    public static final String NAME = "SPONSOR_SIGN_IN";

    public static final ProcessDefinition<SponsorSignInInput, SponsorSignInOutput, LoginContext> DEFINITION =
        ProcessDefinition.define(NAME, 1,
            SponsorSignInInput.class, SponsorSignInOutput.class, LoginContext.class, pb -> pb
                .description("Sign-in: verifies credentials, checks that a role is in effect and records the attempt.")
                .permissions(SecurityPermissions.SIGN_IN)
                .contextFactory(LoginContext::new)
                .outputMapper(LoginContext::output)

                .step("Load the user", QueryEntities.<LoginContext>of(SecurityEntities.USER_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userName", ctx.userName())).limit(1)
                        .build(),
                    LoginContext.KEY_USERS))
                .step("Load the latest login record", QueryEntities.<LoginContext>of(
                    SecurityEntities.LOGIN_RECORD_DATASET, ctx -> Rbac.latestLoginRecordOf(ctx.userId()),
                    LoginContext.KEY_LATEST_RECORD))
                .step("Authentication", AuthenticationStep.class, NoMetadata.INSTANCE)

                .step("Load the role assignments", QueryEntities.<LoginContext>of(SecurityEntities.USER_ROLE_DATASET,
                    ctx -> Rbac.assignmentsOf(ctx.outcome() == LoginOutcome.SUCCESS ? ctx.userId() : null),
                    LoginContext.KEY_ASSIGNMENTS))
                .step("Load the roles", QueryEntities.<LoginContext>of(SecurityEntities.ROLE_DATASET,
                    ctx -> Rbac.rolesOf(ctx.list(LoginContext.KEY_ASSIGNMENTS)), LoginContext.KEY_ROLES))
                .step("Load the permissions", QueryEntities.<LoginContext>of(SecurityEntities.ROLE_PERMISSION_DATASET,
                    ctx -> Rbac.permissionsOf(ctx.list(LoginContext.KEY_ROLES)), LoginContext.KEY_ROLE_PERMISSIONS))
                .compute("Role and access check", (metadata, ctx) -> {
                    if (ctx.outcome() != LoginOutcome.SUCCESS) {
                        return;
                    }
                    Rbac.Access access = Rbac.access(ctx.list(LoginContext.KEY_ROLES),
                        ctx.list(LoginContext.KEY_ROLE_PERMISSIONS));
                    if (access.roles().isEmpty()) {
                        ctx.setOutcome(LoginOutcome.NO_ROLE);
                    } else {
                        ctx.setAccess(access);
                    }
                })

                .step("Create the login record", LoginRecordStep.class, NoMetadata.INSTANCE));

    private SponsorSignInProcess() {}

    /** The attributes of a login record carrying {@code state}. */
    public static Map<String, Object> record(Object userId, String userName, LoginOutcome outcome,
        LoginAttemptPolicy.State state, Instant time, String requestId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("userId", userId);
        attributes.put("userName", userName);
        attributes.put("attemptNo", state.attemptNo());
        attributes.put("outcome", outcome.name());
        attributes.put("failureCount", state.failureCount());
        attributes.put("lockedUntil", state.lockedUntil());
        attributes.put("attemptTime", time);
        attributes.put("requestId", requestId);
        return attributes;
    }
}

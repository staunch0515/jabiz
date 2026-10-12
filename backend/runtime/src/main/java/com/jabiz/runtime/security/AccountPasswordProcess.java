package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * A signed-in user changes their own password (docs/design/10-security.md section 15.8; decision D43 item 3). Runs
 * only through {@code POST /api/auth/account/password} as the user ({@code entryOnly()}, permission
 * {@link SecurityPermissions#ACCOUNT} granted to no role), and only on the caller's own account.
 *
 * <p>Like signing in, a wrong current password is not a failed process: it ends normally with
 * {@link Outcome#INCORRECT} and a login record {@link LoginOutcome#BAD_CREDENTIALS}, which counts towards the lock of
 * the account; while the account is locked the password is not even checked ({@link LoginOutcome#LOCKED}). A user's
 * attempts run one at a time (an exclusive lock per user), so that concurrent guesses each count. The new password is
 * hashed whether the current one was right or not (the same work either way). A right one sets it, records
 * {@link LoginOutcome#PASSWORD_CHANGED} (no failure; the counter stays), ends every other session of the user (the one
 * of the refresh token given, if it is a live one of the caller's, stays) and, with mail on, tells the user by mail
 * ({@value AuthMailTemplates#PASSWORD_CHANGED}). A user without a password (identity provider only) has none to change:
 * 422 {@code PASSWORD_NOT_SET}, nothing recorded.
 */
@Configuration
public class AccountPasswordProcess {

    public static final String NAME = "SEC_ACCOUNT_PASSWORD_CHANGE";

    /** What became of the attempt; the caller answers {@code INCORRECT} with 422 {@code PASSWORD_INCORRECT}. */
    public enum Outcome {
        /** The password is changed. */
        CHANGED,
        /** The current password was wrong, or the account is locked: nothing changed but the login record. */
        INCORRECT
    }

    /**
     * @param userId          the caller, who changes their own password
     * @param currentPassword proves the caller knows the password (an access token alone does not suffice)
     * @param newPassword     checked against the password rules
     * @param refreshToken    a refresh token of the session to keep, or null: every session ends
     * @param source          where the request came from, for the login record
     */
    public record Input(@NotNull String userId, @Sensitive String currentPassword, @Sensitive String newPassword,
        @Sensitive String refreshToken, SignInSource source) {
        @Override
        public String toString() {
            return "Input[userId=" + userId + ", currentPassword=***, newPassword=***, refreshToken=***]";
        }
    }

    public record Output(Outcome outcome) {}

    // Context keys.
    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String LATEST_RECORD = "latest_login_record";
    static final String CURRENT_PASSWORD = "current_password";
    static final String NEW_PASSWORD = "new_password";
    static final String KEEP_TOKEN = "keep_refresh_token";
    static final String LOCKED = "locked";
    static final String MATCHES = "password_matches";
    static final String HASH = "password_hash";
    static final String SOURCE = "source";
    static final String OUTCOME = "outcome";
    static final String CHANGED_USER = "changed_user";
    /** Name of the lock that serializes a user's attempts (the platform's own, apart from applications' names). */
    static final String LOCK = "jabiz.platform.account-password:";

    static ProcessDefinition<Input, Output, ProcessContext> definition(LoginAttemptPolicy policy,
        boolean mailEnabled) {
        return ProcessDefinition.define(NAME, 1, Input.class, Output.class, ProcessContext.class, pb -> pb
            .description("Changes the signed-in user's own password, given the current one; ends the other sessions.")
            .permissions(SecurityPermissions.ACCOUNT)
            .entryOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(USER_ID, input.userId());
                // The secrets one by one: only the password steps see them (and remove them), only the session
                // step the token.
                ctx.put(CURRENT_PASSWORD, input.currentPassword());
                ctx.put(NEW_PASSWORD, input.newPassword());
                ctx.put(KEEP_TOKEN, input.refreshToken());
                ctx.put(SOURCE, input.source() == null ? SignInSource.NONE : input.source());
                return ctx;
            })
            .outputMapper(ctx -> new Output(ctx.get(OUTCOME, Outcome.class)))
            // One attempt of a user at a time: concurrent attempts would all build on the same latest login record,
            // and all but one would lose the race for the next one unrecorded (guesses that never count).
            .step("One attempt at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
            .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
            .compute("Check the account", (metadata, ctx) -> checkAccount(ctx))
            .step("Load the latest login record", QueryEntities.of(SecurityEntities.LOGIN_RECORD_DATASET,
                ctx -> Rbac.latestLoginRecordOf(user(ctx).id()), LATEST_RECORD))
            .compute("Check the lock", (metadata, ctx) -> ctx.put(LOCKED,
                policy.isLocked(Rbac.state(latest(ctx)), ctx.opTime())))
            // Not while locked: guesses then are not even considered (as at sign-in).
            .step("Check the current password", CurrentPasswordStep.class, new CurrentPasswordStep.Metadata(
                CURRENT_PASSWORD, ctx -> user(ctx).get("passwordHash"), MATCHES,
                ctx -> !ctx.hasViolations() && !Boolean.TRUE.equals(ctx.get(LOCKED))))
            // Whenever the current password was checked, right or wrong: the work (and so the time) is the same, and
            // tells nothing about the guess. Only a right one uses the hash.
            .step("Hash the new password", HashPasswordStep.class, new HashPasswordStep.Metadata(NEW_PASSWORD, HASH,
                "newPassword", false, ctx -> !ctx.hasViolations() && ctx.get(MATCHES) != null))
            .compute("Record the attempt", (metadata, ctx) -> record(ctx, policy))
            .step("End the other sessions", RevokeSessionsStep.class, new RevokeSessionsStep.Metadata(CHANGED_USER,
                RefreshTokenStore.REASON_PASSWORD_CHANGE, ctx -> ctx.get(CHANGED_USER) != null, KEEP_TOKEN))
            .step("Tell the user", SendMail.when(ctx -> mailEnabled && ctx.get(CHANGED_USER) != null
                    && user(ctx).get("email") != null, AuthMailTemplates.PASSWORD_CHANGED_MAIL,
                ctx -> MailRecipient.user(user(ctx).id()),
                Map.of(AuthMailTemplates.ENTRY, ctx -> source(ctx).entryOrDefault()))));
    }

    /**
     * The caller's own, enabled account with a password. Another user's account is refused whatever the caller holds
     * (an administrator sets passwords with {@code SEC_USER_SET_PASSWORD}).
     */
    static void checkAccount(ProcessContext ctx) {
        EntityInstance user = user(ctx);
        if (!String.valueOf(user.id()).equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "A user's password is changed here by "
                + "the user only");
        }
        if (!Rbac.enabled(user)) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "The account is disabled");
        }
        if (user.get("passwordHash") == null) {
            ctx.reject(new Violation("currentPassword", PlatformErrorCodes.PASSWORD_NOT_SET, "The account has no "
                + "password: it signs in through an identity provider; a password is set with the password reset",
                Map.of("field", "currentPassword")));
        }
    }

    /** Records the attempt; a right password also sets the new one and marks the user's other sessions to end. */
    static void record(ProcessContext ctx, LoginAttemptPolicy policy) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance user = user(ctx);
        boolean locked = Boolean.TRUE.equals(ctx.get(LOCKED));
        boolean changed = matched(ctx);
        LoginOutcome outcome = locked ? LoginOutcome.LOCKED
            : changed ? LoginOutcome.PASSWORD_CHANGED : LoginOutcome.BAD_CREDENTIALS;
        EntityInstance previous = latest(ctx);
        LoginAttemptPolicy.State next = policy.next(Rbac.state(previous), outcome, ctx.opTime());
        ctx.changes().insert(SecurityEntities.LOGIN_RECORD, SponsorSignInProcess.record(user.id(),
            user.get("userName"), outcome, next, ctx.opTime(), ctx.request().requestId(),
            SecurityEntities.FACTOR_PASSWORD, Rbac.mfaStep(previous), source(ctx)));
        if (changed) {
            ctx.changes().update(SecurityEntities.USER, user.id(), user.version(),
                Map.of("passwordHash", ctx.get(HASH, String.class)));
            ctx.put(CHANGED_USER, user);
        }
        ctx.put(OUTCOME, changed ? Outcome.CHANGED : Outcome.INCORRECT);
    }

    private static boolean matched(ProcessContext ctx) {
        return !ctx.hasViolations() && !Boolean.TRUE.equals(ctx.get(LOCKED)) && Boolean.TRUE.equals(ctx.get(MATCHES));
    }

    private static EntityInstance user(ProcessContext ctx) {
        return ctx.get(USER, EntityInstance.class);
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance latest(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(LATEST_RECORD);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private static SignInSource source(ProcessContext ctx) {
        return ctx.get(SOURCE, SignInSource.class);
    }

    @Bean
    ProcessDefinition<Input, Output, ProcessContext> secAccountPasswordChangeProcess(LoginAttemptPolicy policy,
        @Value("${jabiz.mail.enabled:false}") boolean mailEnabled) {
        return definition(policy, mailEnabled);
    }
}

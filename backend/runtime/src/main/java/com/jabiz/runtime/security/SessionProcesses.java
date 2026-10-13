package com.jabiz.runtime.security;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Ending sessions from the session list (docs/design/10-security.md section 15.9; ROADMAP phase 16g). A user ends one
 * of their own sessions, or all their others, at the sign-in entry of the session they are signed in with: these run
 * only through {@code /api/auth/account/sessions/**} as that user ({@code entryOnly()}, permission
 * {@link SecurityPermissions#ACCOUNT} granted to no role). An administrator ends one or every session of a user, at
 * any entry, with a reason and a recent second factor ({@value #USER_REVOKE_NAME}, an action on {@code SecUser}).
 * Every revocation names its operation; the access tokens already issued expire by themselves (decision D12).
 */
@Configuration
public class SessionProcesses {

    public static final String SESSION_REVOKE_NAME = "SEC_ACCOUNT_SESSION_REVOKE";
    public static final String OTHERS_REVOKE_NAME = "SEC_ACCOUNT_SESSIONS_REVOKE_OTHERS";
    public static final String USER_REVOKE_NAME = "SEC_USER_SESSIONS_REVOKE";

    /**
     * @param userId    the caller, whose session ends
     * @param sessionId the session to end: one of the caller's live sessions at the caller's entry (the caller's own
     *                  included, which signs it out)
     */
    public record SessionInput(@NotNull String userId, @NotNull UUID sessionId) {}

    /**
     * @param userId        the caller, whose other sessions end
     * @param keepSessionId the caller's own session, which stays: taken from the caller's access token, never from
     *                      the request
     */
    public record OthersInput(@NotNull String userId, @NotNull UUID keepSessionId) {}

    /**
     * @param userId    the user whose sessions end
     * @param sessionId the one session to end; none: every one of the user's, at every entry
     * @param reason    why (for example: device lost); kept with the operation
     */
    public record UserSessionsInput(@NotNull String userId, UUID sessionId, @NotBlank @Size(max = 500) String reason) {}

    /** @param revoked how many sessions ended */
    public record Output(int revoked) {}

    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String SESSION_ID = "session_id";
    static final String REVOKED = "revoked";

    public static final ProcessDefinition<SessionInput, Output, ProcessContext> SESSION_REVOKE =
        ProcessDefinition.define(SESSION_REVOKE_NAME, 1, SessionInput.class, Output.class, ProcessContext.class,
            pb -> pb
                .description("Ends one of the signed-in user's own sessions.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(SESSION_ID, input.sessionId());
                    return ctx;
                })
                .outputMapper(SessionProcesses::output)
                .compute("Check the caller", (metadata, ctx) -> checkCaller(ctx))
                .step("End the session", RevokeSessionStep.class, new RevokeSessionStep.Metadata(USER_ID, SESSION_ID,
                    RevokeSessionStep.Scope.ONE, true, RefreshTokenStore.REASON_SELF_REVOKE, REVOKED)));

    public static final ProcessDefinition<OthersInput, Output, ProcessContext> OTHERS_REVOKE =
        ProcessDefinition.define(OTHERS_REVOKE_NAME, 1, OthersInput.class, Output.class, ProcessContext.class,
            pb -> pb
                .description("Ends every other session of the signed-in user at the entry of this one.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(SESSION_ID, input.keepSessionId());
                    return ctx;
                })
                .outputMapper(SessionProcesses::output)
                .compute("Check the caller", (metadata, ctx) -> checkCaller(ctx))
                .step("End the other sessions", RevokeSessionStep.class, new RevokeSessionStep.Metadata(USER_ID,
                    SESSION_ID, RevokeSessionStep.Scope.ALL_BUT, true, RefreshTokenStore.REASON_SELF_REVOKE,
                    REVOKED)));

    public static final ProcessDefinition<UserSessionsInput, Output, ProcessContext> USER_REVOKE =
        ProcessDefinition.define(USER_REVOKE_NAME, 1, UserSessionsInput.class, Output.class, ProcessContext.class,
            pb -> pb
                .description("Ends one or every session of a user, at every sign-in entry.")
                .permissions(SecurityPermissions.SESSION_REVOKE)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .actsOn(SecurityEntities.USER, "userId")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(SESSION_ID, input.sessionId());
                    return ctx;
                })
                .outputMapper(SessionProcesses::output)
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("End the sessions", RevokeSessionStep.class, new RevokeSessionStep.Metadata(USER_ID,
                    SESSION_ID, RevokeSessionStep.Scope.ONE_OR_ALL, false, RefreshTokenStore.REASON_ADMIN_REVOKE,
                    REVOKED)));

    /** A user's sessions are ended here by the user only (an administrator has {@value #USER_REVOKE_NAME}). */
    static void checkCaller(ProcessContext ctx) {
        if (!String.valueOf(ctx.get(USER_ID)).equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "A user's own sessions are ended here "
                + "by the user only");
        }
    }

    private static Output output(ProcessContext ctx) {
        Object revoked = ctx.get(REVOKED);
        return new Output(revoked instanceof Integer count ? count : 0);
    }

    @Bean
    ProcessDefinition<SessionInput, Output, ProcessContext> secAccountSessionRevokeProcess() {
        return SESSION_REVOKE;
    }

    @Bean
    ProcessDefinition<OthersInput, Output, ProcessContext> secAccountSessionsRevokeOthersProcess() {
        return OTHERS_REVOKE;
    }

    @Bean
    ProcessDefinition<UserSessionsInput, Output, ProcessContext> secUserSessionsRevokeProcess() {
        return USER_REVOKE;
    }
}

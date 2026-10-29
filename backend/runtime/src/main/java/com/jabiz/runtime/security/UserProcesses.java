package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.MailTemplates;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.protect.ProtectedChangeEntities;
import com.jabiz.runtime.security.protect.ProtectedChangeProcesses;
import com.jabiz.runtime.security.protect.ProtectedGrants;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;
import com.jabiz.security.MfaRequirement;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The processes that set what the dataset API cannot (docs/design/10-security.md section 3): creating a user with a
 * password, setting a password, lifting a lock. The password hash is sensitive, so these are its only writers.
 */
@Configuration
public class UserProcesses {

    /**
     * @param password optional: a user without one signs in through an identity provider only
     *                 (docs/design/10-security.md section 12), or once they accepted an invitation
     * @param email    where notifications go; optional
     * @param locale   the language of the user's mail ({@code zh}, {@code ja}, {@code en}); optional
     * @param invite   mails the user an invitation to set their own password, leading to the administration's pages
     *                 (decision D63; {@code SEC_USER_INVITE} names another entry): needs the address, mail on and no
     *                 password
     */
    public record CreateUserInput(@NotBlank String userName, String displayName, String tenantId,
        @Sensitive String password, Boolean enabled, String email, String locale, Boolean invite) {
        @Override
        public String toString() {
            return "CreateUserInput[userName=" + userName + ", password=***]";
        }
    }

    public record SetPasswordInput(@NotNull String userId, @Sensitive @NotBlank String password) {
        @Override
        public String toString() {
            return "SetPasswordInput[userId=" + userId + ", password=***]";
        }
    }

    public record UserIdInput(@NotNull String userId) {}

    /** @param locale one of the platform's languages, or null for the platform's default */
    public record LocaleInput(@NotNull String userId, String locale) {}

    public record UserIdOutput(String userId) {}

    static final String PASSWORD = "password";
    static final String HASH = "password_hash";
    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String LATEST_RECORD = "latest_login_record";
    static final String NEW_USER = "new_user";
    static final String LOCALE = "locale";
    static final String INVITE = "invite";

    private static final HashPasswordStep.Metadata HASH_PASSWORD =
        new HashPasswordStep.Metadata(PASSWORD, HASH, PASSWORD);

    static ProcessDefinition<CreateUserInput, UserIdOutput, ProcessContext> createUser(
        AuthMailTemplates.Invitation invitation, MailTemplates mail, SignInEntries entries) {
        return ProcessDefinition.define("SEC_USER_CREATE", 1, CreateUserInput.class, UserIdOutput.class,
            ProcessContext.class, pb -> pb
                .description("Creates a user with a password, or invites them to set their own.")
                .permissions(SecurityPermissions.USER_CREATE)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    // The fields one by one: the input record holds the plain password, which only the hashing
                    // step may see (and removes).
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("userName", input.userName());
                    user.put("displayName", input.displayName());
                    user.put("tenantId", input.tenantId());
                    // Stripped: an invitation goes to the address as stored, and its link holds only for that one.
                    user.put("email", input.email() == null ? null : input.email().strip());
                    user.put("locale", input.locale());
                    user.put("enabled", input.enabled() == null || input.enabled());
                    ctx.put(NEW_USER, user);
                    ctx.put(PASSWORD, input.password());
                    if (Boolean.TRUE.equals(input.invite())) {
                        ctx.put(INVITE, true);
                    }
                    return ctx;
                })
                .outputMapper(ctx -> new UserIdOutput(String.valueOf(ctx.get(USER_ID))))
                .compute("Check the invitation", (metadata, ctx) -> {
                    if (!ctx.contains(INVITE)) {
                        return;
                    }
                    InvitationProcesses.checkSending(ctx, mail, entries, InvitationProcesses.DEFAULT_ENTRY);
                    if (ctx.get(PASSWORD) != null) {
                        // The administrator would know a password, which the invitation exists to avoid.
                        ctx.reject(new Violation(PASSWORD, PlatformErrorCodes.INVALID_VALUE, "An invited user sets"
                            + " their own password", Map.of("field", PASSWORD)));
                    }
                    String email = email(ctx);
                    if (email == null) {
                        ctx.reject(new Violation("email", PlatformErrorCodes.MAIL_NO_ADDRESS, "An invitation needs"
                            + " the user's e-mail address", Map.of("field", "email")));
                    } else if (!MailRecipient.ADDRESS.matcher(email).matches()) {
                        ctx.reject(new Violation("email", PlatformErrorCodes.INVALID_VALUE, "Not an e-mail address",
                            Map.of("field", "email")));
                    }
                    @SuppressWarnings("unchecked")
                    Object enabled = ((Map<String, Object>) ctx.get(NEW_USER)).get("enabled");
                    if (!Boolean.TRUE.equals(enabled)) {
                        ctx.reject(new Violation("enabled", PlatformErrorCodes.INVITATION_USER_DISABLED,
                            "A disabled user cannot accept an invitation"));
                    }
                })
                // Not for a request refused already: no hashing work for it.
                .step("Hash the password", HashPasswordStep.class,
                    new HashPasswordStep.Metadata(PASSWORD, HASH, PASSWORD, true, ctx -> !ctx.hasViolations()))
                .compute("Register the user", (metadata, ctx) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> user = new LinkedHashMap<>((Map<String, Object>) ctx.get(NEW_USER));
                    user.put("passwordHash", ctx.get(HASH));
                    ctx.put(USER_ID, ctx.changes().insert(SecurityEntities.USER, user));
                })
                .step("Send the invitation", SendMail.when(ctx -> ctx.contains(INVITE) && !ctx.hasViolations(),
                    invitation.mail(), ctx -> MailRecipient.user(ctx.get(USER_ID), email(ctx), userLocale(ctx)),
                    Map.of(AuthMailTemplates.ENTRY, ctx -> InvitationProcesses.DEFAULT_ENTRY))));
    }

    /** The new user's address, or null for none. */
    @SuppressWarnings("unchecked")
    private static String email(ProcessContext ctx) {
        Object email = ((Map<String, Object>) ctx.get(NEW_USER)).get("email");
        return email == null || String.valueOf(email).isBlank() ? null : String.valueOf(email).strip();
    }

    /** The user's own language, or null: the invitation then speaks as SEC_USER_INVITE's would. */
    @SuppressWarnings("unchecked")
    private static Locale userLocale(ProcessContext ctx) {
        Object locale = ((Map<String, Object>) ctx.get(NEW_USER)).get("locale");
        return locale == null || String.valueOf(locale).isBlank() ? null : Locale.of(String.valueOf(locale));
    }

    /**
     * The answer of {@code SEC_USER_SET_PASSWORD} and {@code SEC_MFA_RESET}: done now ({@code APPLIED}), or, for a
     * holder of a protected permission, waiting for another administrator's approval ({@code PENDING}, decision D48).
     */
    public record CredentialResetOutput(String userId, String status, String changeId, String approval,
        String approvalRequestId) {

        static CredentialResetOutput of(ProcessContext ctx, Object userId) {
            ProtectedChangeProcesses.ProtectedChangeOutput change = ProtectedChangeProcesses.output(ctx, userId);
            return new CredentialResetOutput(String.valueOf(userId), change.status(), change.changeId(),
                change.approval(), change.approvalRequestId());
        }
    }

    /** Set while the new password waits for approval: the hash is not written, the sessions stay. */
    static final String DEFERRED = "credential_deferred";

    public static final ProcessDefinition<SetPasswordInput, CredentialResetOutput, ProcessContext> SET_PASSWORD =
        ProcessDefinition.define("SEC_USER_SET_PASSWORD", 1, SetPasswordInput.class, CredentialResetOutput.class,
            ProcessContext.class, pb -> pb
                .description("Sets the password of a user; for a holder of a protected permission it waits for"
                    + " another administrator's approval.")
                .permissions(SecurityPermissions.USER_PASSWORD)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(PASSWORD, input.password());
                    return ctx;
                })
                .outputMapper(ctx -> CredentialResetOutput.of(ctx, ctx.get(USER_ID)))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Hash the password", HashPasswordStep.class, HASH_PASSWORD)
                .step("Assess the reset", ProtectedChangeProcesses.assess(ctx -> new ProtectedGrants.Draft(
                    ProtectedChangeEntities.PASSWORD, java.util.UUID.fromString(String.valueOf(
                        ctx.get(USER, EntityInstance.class).id())), null, null)))
                .compute("Register the new hash", (metadata, ctx) -> {
                    EntityInstance user = ctx.get(USER, EntityInstance.class);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("kind", ProtectedChangeEntities.PASSWORD);
                    values.put("userId", user.id());
                    values.put("passwordHash", ctx.get(HASH, String.class));
                    if (ProtectedChangeProcesses.defer(ctx, values, "Password reset by an administrator")) {
                        ctx.put(DEFERRED, true);
                        return;
                    }
                    ctx.changes().update(SecurityEntities.USER, user.id(), user.version(),
                        Map.of("passwordHash", ctx.get(HASH, String.class)));
                })
                .step("Ask for approval", ProtectedChangeProcesses.requireApproval())
                .step("End the user's sessions", RevokeSessionsStep.class,
                    new RevokeSessionsStep.Metadata(USER, RefreshTokenStore.REASON_PASSWORD,
                        ctx -> !ctx.contains(DEFERRED))));

    public static final ProcessDefinition<UserIdInput, UserIdOutput, ProcessContext> UNLOCK =
        ProcessDefinition.define("SEC_USER_UNLOCK", 1, UserIdInput.class, UserIdOutput.class,
            ProcessContext.class, pb -> pb
                .description("Lifts the lock of a user after failed sign-ins.")
                .permissions(SecurityPermissions.USER_UNLOCK)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    return ctx;
                })
                .outputMapper(ctx -> new UserIdOutput(String.valueOf(ctx.get(USER_ID))))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Load the latest login record", QueryEntities.of(SecurityEntities.LOGIN_RECORD_DATASET,
                    ctx -> Rbac.latestLoginRecordOf(ctx.get(USER, EntityInstance.class).id()), LATEST_RECORD))
                .compute("Record the unlock", (metadata, ctx) -> {
                    EntityInstance user = ctx.get(USER, EntityInstance.class);
                    @SuppressWarnings("unchecked")
                    List<EntityInstance> latest = (List<EntityInstance>) ctx.get(LATEST_RECORD);
                    LoginAttemptPolicy.State state = Rbac.state(latest.isEmpty() ? null : latest.getFirst());
                    LoginAttemptPolicy.State next = new LoginAttemptPolicy.State(state.attemptNo() + 1, 0, null);
                    ctx.changes().insert(SecurityEntities.LOGIN_RECORD, SponsorSignInProcess.record(user.id(),
                        user.get("userName"), LoginOutcome.UNLOCKED, next, ctx.opTime(), ctx.request().requestId(),
                        null, Rbac.mfaStep(latest.isEmpty() ? null : latest.getFirst())));
                }));

    /**
     * The user's own choice of the language of their mail (docs/design/18-numbering-approvals-tasks.md section 5.6).
     * Runs only through the account settings, as the user ({@link SecurityPermissions#ACCOUNT}, granted to no role).
     */
    public static final ProcessDefinition<LocaleInput, UserIdOutput, ProcessContext> SET_LOCALE =
        ProcessDefinition.define("SEC_USER_SET_LOCALE", 1, LocaleInput.class, UserIdOutput.class,
            ProcessContext.class, pb -> pb
                .description("Sets the language of the user's own mail.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(LOCALE, input.locale() == null || input.locale().isBlank() ? null : input.locale());
                    return ctx;
                })
                .outputMapper(ctx -> new UserIdOutput(String.valueOf(ctx.get(USER_ID))))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .compute("Record the language", (metadata, ctx) -> {
                    EntityInstance user = ctx.get(USER, EntityInstance.class);
                    if (!String.valueOf(user.id()).equals(ctx.request().actorId())) {
                        throw new com.jabiz.runtime.PermissionDeniedException(SecurityPermissions.ACCOUNT,
                            "A user's language is set by the user only");
                    }
                    Object locale = ctx.get(LOCALE);
                    if (!java.util.Objects.equals(locale, user.get("locale"))) {
                        Map<String, Object> changed = new LinkedHashMap<>();
                        changed.put("locale", locale);
                        ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), changed);
                    }
                }));

    @Bean
    ProcessDefinition<LocaleInput, UserIdOutput, ProcessContext> secUserSetLocaleProcess() {
        return SET_LOCALE;
    }

    @Bean
    ProcessDefinition<CreateUserInput, UserIdOutput, ProcessContext> secUserCreateProcess(
        AuthMailTemplates.Invitation invitation, MailTemplates mail, SignInEntries entries) {
        return createUser(invitation, mail, entries);
    }

    @Bean
    ProcessDefinition<SetPasswordInput, CredentialResetOutput, ProcessContext> secUserSetPasswordProcess() {
        return SET_PASSWORD;
    }

    @Bean
    ProcessDefinition<UserIdInput, UserIdOutput, ProcessContext> secUserUnlockProcess() {
        return UNLOCK;
    }
}

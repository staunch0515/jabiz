package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailRecipient;
import com.jabiz.security.MfaRequirement;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.mail.MailTokens;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.sponsor.SponsorPasskeySignInProcess;
import com.jabiz.runtime.process.sponsor.SponsorSignInOutput;
import com.jabiz.runtime.process.sponsor.SponsorPasskeySignInInput;
import com.jabiz.runtime.process.sponsor.PasskeyContext;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The passkeys of a user (decision D71; docs/design/10-security.md section 15.16): registering one (the caller's own,
 * after the platform checked who the caller is and took back the challenge), renaming and removing the caller's own,
 * and an administrator's removal. Mails: the confirmation link for users without a password, and the notice that a
 * passkey was added.
 */
@Configuration
public class PasskeyProcesses {

    public static final String REGISTER = "SEC_PASSKEY_REGISTER";
    public static final String RENAME = "SEC_PASSKEY_RENAME";
    public static final String REMOVE = "SEC_PASSKEY_REMOVE";
    public static final String REMOVE_ADMIN = "SEC_PASSKEY_REMOVE_ADMIN";
    public static final String CONFIRMATION_SEND = "SEC_PASSKEY_CONFIRMATION_SEND";
    public static final String CONFIRMATION_USE = "SEC_PASSKEY_CONFIRMATION_USE";
    /** Checks the caller's current password before a passkey is added (decision D71 item 4). */
    public static final String PASSWORD_CHECK = "SEC_PASSKEY_PASSWORD_CHECK";

    /** The longest name of a passkey. */
    public static final int MAX_NAME = 100;
    /** The name of a passkey whose user gave none. */
    public static final String DEFAULT_NAME = "Passkey";
    /** Name of the lock that serializes a user's changes of passkeys (the platform's own namespace). */
    static final String LOCK = "jabiz.platform.passkeys:";

    /**
     * @param attestation the browser's answer, WebAuthn's JSON (kept out of the operation record)
     * @param challenge   the challenge it must answer, which the platform took back for this user
     */
    public record RegisterInput(@NotNull String userId, @NotBlank String credentialId, @Sensitive String attestation,
        @NotBlank String challenge, String name) {
        @Override
        public String toString() {
            return "RegisterInput[userId=" + userId + ", credentialId=" + credentialId + ", ***]";
        }
    }

    public record RegisterOutput(String passkeyId) {}

    public record RenameInput(@NotNull String userId, @NotBlank String passkeyId, String name) {}

    public record RemoveInput(@NotNull String userId, @NotBlank String passkeyId) {}

    /** @param reason why it is removed (for example: device lost) */
    public record RemoveAdminInput(@NotNull String userId, @NotBlank String passkeyId, @NotBlank String reason) {}

    public record PasskeyOutput(String passkeyId) {}

    public record UserInput(@NotNull String userId) {}

    /** @param passkeyToken the confirmation mail's token; named apart, as secret names are masked wherever they appear */
    public record ConfirmationInput(@NotNull String userId, @Sensitive String passkeyToken) {
        @Override
        public String toString() {
            return "ConfirmationInput[userId=" + userId + ", ***]";
        }
    }

    public record ConfirmationOutput(boolean confirmed) {}

    // Context keys.
    static final String INPUT = "input";
    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String OWN = "own";
    static final String SAME = "same_credential";
    static final String PASSKEY_ID = "passkey_id";
    static final String USED = "used";

    @Bean
    ProcessDefinition<RegisterInput, RegisterOutput, ProcessContext> secPasskeyRegisterProcess(
        PasskeyVerifier verifier, PasskeyProperties properties,
        @Value("${jabiz.mail.enabled:false}") boolean mailEnabled) {
        return ProcessDefinition.define(REGISTER, 1, RegisterInput.class, RegisterOutput.class, ProcessContext.class,
            pb -> pb
                .description("Adds a passkey to the signed-in user's own account.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory(PasskeyProcesses::context)
                .outputMapper(ctx -> new RegisterOutput(String.valueOf(ctx.get(PASSKEY_ID))))
                .step("One change at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Load the user's passkeys and the credential's", QueryEntities.all(
                    QueryEntities.<ProcessContext>query(PasskeyEntities.PASSKEY_DATASET, ctx -> Rbac.all(
                        new QueryPredicate.Eq("userId", ctx.get(USER_ID)), "createdAt"), OWN),
                    QueryEntities.<ProcessContext>query(PasskeyEntities.PASSKEY_DATASET, ctx -> Rbac.all(
                        new QueryPredicate.Eq("credentialId", register(ctx).credentialId()), "createdAt"), SAME)))
                .compute("Add the passkey", (metadata, ctx) -> registerPasskey(ctx, verifier,
                    properties.maxPerUser()))
                .step("Tell the user", SendMail.when(ctx -> mailEnabled && ctx.get(PASSKEY_ID) != null
                        && user(ctx).get("email") != null, AuthMailTemplates.PASSKEY_ADDED_MAIL,
                    ctx -> MailRecipient.user(user(ctx).id()),
                    Map.of(AuthMailTemplates.ENTRY, PasskeyProcesses::entryOf,
                        AuthMailTemplates.PASSKEY_NAME, ctx -> name(register(ctx).name())))));
    }

    @Bean
    ProcessDefinition<RenameInput, PasskeyOutput, ProcessContext> secPasskeyRenameProcess() {
        return ProcessDefinition.define(RENAME, 1, RenameInput.class, PasskeyOutput.class, ProcessContext.class,
            pb -> pb
                .description("Renames one of the signed-in user's own passkeys.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory(PasskeyProcesses::context)
                .outputMapper(ctx -> new PasskeyOutput(String.valueOf(ctx.get(PASSKEY_ID))))
                .step("Load the user's passkeys", ownPasskeys())
                .compute("Rename it", (metadata, ctx) -> {
                    RenameInput input = (RenameInput) ctx.get(INPUT);
                    EntityInstance passkey = own(ctx, input.userId(), input.passkeyId());
                    String name = name(input.name());
                    if (!name.equals(passkey.get("name"))) {
                        ctx.changes().update(PasskeyEntities.PASSKEY, passkey.id(), passkey.version(),
                            Map.of("name", name));
                    }
                    ctx.put(PASSKEY_ID, passkey.id());
                }));
    }

    @Bean
    ProcessDefinition<RemoveInput, PasskeyOutput, ProcessContext> secPasskeyRemoveProcess() {
        return ProcessDefinition.define(REMOVE, 1, RemoveInput.class, PasskeyOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes one of the signed-in user's own passkeys: it signs in no more.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory(PasskeyProcesses::context)
                .outputMapper(ctx -> new PasskeyOutput(String.valueOf(ctx.get(PASSKEY_ID))))
                .step("One change at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
                .step("Load the user's passkeys", ownPasskeys())
                .compute("Remove it", (metadata, ctx) -> {
                    RemoveInput input = (RemoveInput) ctx.get(INPUT);
                    EntityInstance passkey = own(ctx, input.userId(), input.passkeyId());
                    ctx.changes().delete(PasskeyEntities.PASSKEY, passkey.id(), passkey.version());
                    ctx.put(PASSKEY_ID, passkey.id());
                }));
    }

    /**
     * An administrator's removal of a user's passkey (decision D71 item 8): with a reason, recent second factor at the
     * administration; never a registration on the user's behalf. It only takes access away, so no second
     * administrator approves it.
     */
    @Bean
    ProcessDefinition<RemoveAdminInput, PasskeyOutput, ProcessContext> secPasskeyRemoveAdminProcess() {
        return ProcessDefinition.define(REMOVE_ADMIN, 1, RemoveAdminInput.class, PasskeyOutput.class,
            ProcessContext.class, pb -> pb
                .description("Removes a passkey of a user (for example when the device was lost).")
                .permissions(SecurityPermissions.MFA_RESET)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .contextFactory(PasskeyProcesses::context)
                .outputMapper(ctx -> new PasskeyOutput(String.valueOf(ctx.get(PASSKEY_ID))))
                .step("One change at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
                .step("Load the user's passkeys", ownPasskeys())
                .compute("Remove it", (metadata, ctx) -> {
                    RemoveAdminInput input = (RemoveAdminInput) ctx.get(INPUT);
                    EntityInstance passkey = list(ctx, OWN).stream()
                        .filter(p -> String.valueOf(p.id()).equals(input.passkeyId())).findFirst()
                        .orElseThrow(() -> new EntityNotFoundException("No such passkey of the user"));
                    ctx.changes().delete(PasskeyEntities.PASSKEY, passkey.id(), passkey.version());
                    ctx.put(PASSKEY_ID, passkey.id());
                }));
    }

    /**
     * Mails the signed-in user a link that confirms them before a passkey is added: for users without a password and
     * without a second factor (decision D71 item 4). Only to the user's own verified address (a link proves the
     * mailbox, decision D54).
     */
    @Bean
    ProcessDefinition<UserInput, ConfirmationOutput, ProcessContext> secPasskeyConfirmationSendProcess(
        AuthMailTemplates.PasskeyConfirmation confirmation,
        @Value("${jabiz.mail.enabled:false}") boolean mailEnabled) {
        return ProcessDefinition.define(CONFIRMATION_SEND, 1, UserInput.class, ConfirmationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Mails the signed-in user a link that confirms them before a passkey is added.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory(PasskeyProcesses::context)
                .outputMapper(ctx -> new ConfirmationOutput(true))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .compute("Check the account", (metadata, ctx) -> {
                    checkOwn(ctx);
                    if (!mailEnabled || user(ctx).get("email") == null || !SecurityEntities.emailVerified(user(ctx))) {
                        ctx.reject(new Violation(null, PlatformErrorCodes.REAUTHENTICATION_UNAVAILABLE,
                            "No verified address to send the confirmation to"));
                    }
                })
                .step("Send the link", SendMail.<ProcessContext>when(ctx -> !ctx.hasViolations(), confirmation.mail(),
                    ctx -> MailRecipient.user(user(ctx).id()), Map.of(AuthMailTemplates.ENTRY,
                        PasskeyProcesses::entryOf))));
    }

    /** Uses the confirmation mail's link: its token, of the caller. */
    @Bean
    ProcessDefinition<ConfirmationInput, ConfirmationOutput, ProcessContext> secPasskeyConfirmationUseProcess() {
        return ProcessDefinition.define(CONFIRMATION_USE, 1, ConfirmationInput.class, ConfirmationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Uses the link that confirms the signed-in user before a passkey is added.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory(PasskeyProcesses::context)
                .outputMapper(ctx -> new ConfirmationOutput(true))
                .step("Use the link", MailTokens.consume(AuthMailTemplates.PASSKEY,
                    ctx -> ((ConfirmationInput) ctx.get(INPUT)).passkeyToken(), USED))
                .compute("Check the user", (metadata, ctx) -> {
                    MailTokens.Consumed used = (MailTokens.Consumed) ctx.get(USED);
                    String caller = ctx.request().actorId();
                    if (used == null || caller == null || !caller.equals(used.userId())
                        || !caller.equals(ctx.get(USER_ID))) {
                        ctx.reject(new Violation(null, PlatformErrorCodes.TOKEN_INVALID,
                            "The link is not the signed-in user's"));
                    }
                }));
    }

    @Bean
    ProcessDefinition<IdentityProcesses.BeginInput, IdentityProcesses.BeginOutput, ProcessContext>
    secPasskeyPasswordCheckProcess(LoginAttemptPolicy policy) {
        return IdentityProcesses.passwordCheck(PASSWORD_CHECK, "Checks the signed-in user's current password before a "
            + "passkey is added.", policy);
    }

    @Bean
    ProcessDefinition<SponsorPasskeySignInInput, SponsorSignInOutput, PasskeyContext> sponsorPasskeySignInProcess() {
        return SponsorPasskeySignInProcess.DEFINITION;
    }

    // ---- computations ------------------------------------------------------------------------------------------

    private static void registerPasskey(ProcessContext ctx, PasskeyVerifier verifier, int maxPerUser) {
        checkOwn(ctx);
        RegisterInput input = register(ctx);
        PasskeyVerifier.Registered registered;
        try {
            registered = verifier.register(input.attestation(), input.challenge());
        } catch (PasskeyVerifier.InvalidPasskeyException e) {
            ctx.reject(new Violation(null, PlatformErrorCodes.PASSKEY_INVALID, e.getMessage()));
            return;
        }
        if (!registered.credentialId().equals(input.credentialId())) {
            ctx.reject(new Violation(null, PlatformErrorCodes.PASSKEY_INVALID, "The answer names another credential"));
            return;
        }
        if (!list(ctx, SAME).isEmpty()) {
            ctx.reject(new Violation(null, PlatformErrorCodes.PASSKEY_EXISTS, "The passkey is registered already"));
            return;
        }
        if (list(ctx, OWN).size() >= maxPerUser) {
            ctx.reject(new Violation(null, PlatformErrorCodes.PASSKEY_LIMIT, "The user keeps " + maxPerUser
                + " passkeys already", Map.of("max", maxPerUser)));
            return;
        }
        Map<String, Object> passkey = new LinkedHashMap<>();
        passkey.put("userId", input.userId());
        passkey.put("credentialId", registered.credentialId());
        passkey.put("credentialData", registered.credentialData());
        passkey.put("signCount", registered.signCount());
        passkey.put("name", name(input.name()));
        passkey.put("transports", registered.transports());
        passkey.put("backedUp", registered.backedUp());
        passkey.put("aaguid", registered.aaguid());
        passkey.put("createdAt", ctx.opTime());
        passkey.put("lastUsedAt", null);
        ctx.put(PASSKEY_ID, ctx.changes().insert(PasskeyEntities.PASSKEY, passkey));
    }

    /** The caller's own account, enabled: another user's is refused whatever the caller holds. */
    private static void checkOwn(ProcessContext ctx) {
        EntityInstance user = user(ctx);
        if (!String.valueOf(user.id()).equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "Passkeys are added by their user only");
        }
        if (!Rbac.enabled(user)) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "The account is disabled");
        }
    }

    /** The caller's passkey of that id; another user's is the same 404 as none. */
    private static EntityInstance own(ProcessContext ctx, String userId, String passkeyId) {
        if (!userId.equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "Passkeys are changed by their user only");
        }
        Optional<EntityInstance> passkey = list(ctx, OWN).stream()
            .filter(p -> String.valueOf(p.id()).equals(passkeyId)).findFirst();
        return passkey.orElseThrow(() -> new EntityNotFoundException("No such passkey of the user"));
    }

    /** A name as stored: no control characters, at most {@value #MAX_NAME} characters, {@value #DEFAULT_NAME} for none. */
    static String name(String given) {
        if (given == null) {
            return DEFAULT_NAME;
        }
        StringBuilder clean = new StringBuilder();
        given.codePoints().filter(point -> !Character.isISOControl(point)
                && Character.getType(point) != Character.FORMAT)
            .forEach(clean::appendCodePoint);
        String name = clean.toString().strip();
        if (name.codePointCount(0, name.length()) > MAX_NAME) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME)).strip();
        }
        return name.isEmpty() ? DEFAULT_NAME : name;
    }

    private static QueryEntities.Metadata<ProcessContext> ownQuery() {
        return QueryEntities.query(PasskeyEntities.PASSKEY_DATASET,
            ctx -> Rbac.all(new QueryPredicate.Eq("userId", ctx.get(USER_ID)), "createdAt"), OWN);
    }

    private static com.jabiz.process.StepSpec<QueryEntities.AllMetadata<ProcessContext>, ProcessContext> ownPasskeys() {
        return QueryEntities.all(ownQuery());
    }

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(USER_ID, switch (input) {
            case RegisterInput i -> i.userId();
            case RenameInput i -> i.userId();
            case RemoveInput i -> i.userId();
            case RemoveAdminInput i -> i.userId();
            case UserInput i -> i.userId();
            case ConfirmationInput i -> i.userId();
            default -> throw new IllegalArgumentException("Not an input of the passkeys: " + input);
        });
        return ctx;
    }

    private static RegisterInput register(ProcessContext ctx) {
        return (RegisterInput) ctx.get(INPUT);
    }

    private static EntityInstance user(ProcessContext ctx) {
        return ctx.get(USER, EntityInstance.class);
    }

    private static String entryOf(ProcessContext ctx) {
        String entry = ctx.request().entry();
        return entry == null || entry.isBlank() ? com.jabiz.context.RequestContext.DEFAULT_ENTRY : entry;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object rows = ctx.get(key);
        return rows instanceof List<?> found ? (List<EntityInstance>) found : List.of();
    }
}

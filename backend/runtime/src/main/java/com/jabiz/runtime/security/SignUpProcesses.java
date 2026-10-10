package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepImplementation;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.MailMessageParams;
import com.jabiz.runtime.mail.MailTokens;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.EmailVerification;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;
import com.jabiz.security.Sensitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Self-registration, address verification and password reset (docs/design/10-security.md section 15; decision D36
 * items 2, 3 and 5 and its implementation notes 4 to 7 and 12). Like signing in, each is a process that writes what it
 * did; they run only through {@code SignUpController} (their permission is granted to no role), anonymously except
 * the signed-in user's request for a new verification mail.
 * <ul>
 *   <li>{@value #SIGN_UP}: an address nobody has gets a new user (named after the address), the entry's registration
 *       roles and a verification mail; an address that belongs to a user gets no user, never the submitted password,
 *       and its user (when enabled) gets the "you already have an account" mail instead. The password is hashed
 *       either way, so the two cannot be told apart by time either.</li>
 *   <li>{@value #JOIN}: the link of that mail adds the entry's registration roles to the user; an address that was not
 *       verified must be given a new password at the same time, which ends every session and removes a second factor
 *       set up before the address was proven (account pre-claim protection).</li>
 *   <li>{@value #VERIFY}: the link of a verification mail proves the address; {@value #VERIFY_SEND} sends a new one
 *       (to unverified users only), which supersedes the earlier link.</li>
 *   <li>{@value #RESET_REQUEST} sends a password reset link to an enabled user; {@value #RESET_CONFIRM} sets the
 *       password with it, proves the address, ends every session and records {@link LoginOutcome#PASSWORD_RESET}
 *       (which lifts a lock).</li>
 * </ul>
 * Every link is a one-time token of its purpose ({@link MailTokens}); it is void once the user's address differs from
 * the one it was sent to, or the user is disabled, and every refusal of a link is the same {@code TOKEN_INVALID}.
 * Proving the address writes {@code verifiedEmail} and {@code emailVerifiedAt}, the only writers of both.
 */
@Configuration
public class SignUpProcesses {

    private static final Logger log = LoggerFactory.getLogger(SignUpProcesses.class);

    public static final String SIGN_UP = "SPONSOR_SIGN_UP";
    public static final String JOIN = "SPONSOR_SIGN_UP_JOIN";
    public static final String VERIFY = "SPONSOR_EMAIL_VERIFY";
    public static final String VERIFY_SEND = "SPONSOR_EMAIL_VERIFY_SEND";
    public static final String RESET_REQUEST = "SPONSOR_PASSWORD_RESET_REQUEST";
    public static final String RESET_CONFIRM = "SPONSOR_PASSWORD_RESET_CONFIRM";

    /** Longest user name ({@code SecUser.userName}). */
    static final int MAX_USER_NAME = 100;
    /** Characters of the suffix that tells apart a registered name already taken. */
    static final int SUFFIX_LENGTH = 6;
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    // Context keys.
    static final String PASSWORD = "password";
    static final String HASH = "password_hash";
    static final String USERS = "users";
    static final String NAMES = "names";
    static final String ROLES = "roles";
    static final String ASSIGNMENTS = "assignments";
    static final String MFA = "mfa";
    static final String LATEST_RECORD = "latest_login_record";
    static final String RANDOM = "random";
    static final String CONSUMED = "consumed";
    static final String MAIL_PARAMS = "mail_params";
    static final String NEW_USER = "new_user";
    static final String EXISTING_USER = "existing_user";
    static final String USER = "user";
    static final String END_SESSIONS = "end_sessions";

    /**
     * @param entry       the sign-in entry registered with; it must let people register
     * @param email       checked by the caller (as every value here, so that both outcomes fail alike)
     * @param displayName optional
     * @param locale      the language of the user's mail, one of the platform's; optional
     */
    public record SignUpInput(String entry, String email, @Sensitive String password, String displayName,
        String locale) {
        @Override
        public String toString() {
            return "SignUpInput[entry=" + entry + ", email=" + email + ", password=***]";
        }
    }

    /** @param password a new password; required when the user's address was not verified */
    public record JoinInput(@Sensitive String token, @Sensitive String password) {
        @Override
        public String toString() {
            return "JoinInput[***]";
        }
    }

    public record VerifyInput(@Sensitive String token) {
        @Override
        public String toString() {
            return "VerifyInput[***]";
        }
    }

    /**
     * @param entry  whose pages the link finishes at
     * @param email  the address to send to (anonymous request), or null
     * @param userId the signed-in user asking for themselves, or null
     */
    public record VerifySendInput(String entry, String email, String userId) {}

    public record ResetRequestInput(String entry, String email) {}

    /** @param source where the request came from, for the login record */
    public record ResetConfirmInput(@Sensitive String token, @Sensitive String password, SignInSource source) {
        @Override
        public String toString() {
            return "ResetConfirmInput[***]";
        }
    }

    /** The same for every outcome: the caller learns nothing from it. */
    public record Done(String status) {
        public static final Done DONE = new Done("DONE");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <M> Class<? extends StepImplementation<M, AccountContext>> on(
        Class<? extends StepImplementation<M, ? super AccountContext>> step) {
        return (Class) step;
    }

    private static final Function<AccountContext, Done> DONE = ctx -> Done.DONE;

    static ProcessDefinition<SignUpInput, Done, AccountContext> signUp(AuthMailTemplates.Templates mail,
        SignInEntries entries) {
        return ProcessDefinition.define(SIGN_UP, 1, SignUpInput.class, Done.class, AccountContext.class, pb -> pb
            .description("Self-registration: a new user with the entry's roles, or a mail to the address's user.")
            .permissions(SecurityPermissions.SIGN_UP)
            .internal()
            .contextFactory((start, input) -> {
                AccountContext ctx = new AccountContext(start, input.entry(), input.email(), input.displayName(),
                    input.locale(), null, null, null);
                ctx.put(PASSWORD, input.password());
                return ctx;
            })
            .outputMapper(DONE)
            .step("Find the address", LoadUserByEmail.<AccountContext>into(AccountContext::email, USERS))
            .step("Find the user name", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userName", baseName(ctx.email())))
                    .limit(1).build(), NAMES))
            .step("Find the registration roles", QueryEntities.<AccountContext>of(SecurityEntities.ROLE_DATASET,
                ctx -> rolesNamed(entries.resolve(ctx.entryName()).registrationRoles()), ROLES))
            .step("Draw a name suffix", on(RandomBytesStep.class), new RandomBytesStep.Metadata(RANDOM, SUFFIX_LENGTH))
            // Whether or not the address is taken: the time the answer takes tells nothing.
            .step("Hash the password", on(HashPasswordStep.class),
                new HashPasswordStep.Metadata(PASSWORD, HASH, PASSWORD))
            .compute("Register", (metadata, ctx) -> register(ctx, entries))
            .step("Send the verification", SendMail.<AccountContext>when(
                ctx -> ctx.get(NEW_USER) != null && !ctx.hasViolations(), mail.verifyEmail(),
                ctx -> MailRecipient.user(ctx.get(NEW_USER), ctx.email(), mailLocale(ctx)),
                Map.of(AuthMailTemplates.ENTRY, AccountContext::entryName)))
            .step("Tell the account's user", SendMail.<AccountContext>when(
                ctx -> ctx.get(EXISTING_USER) != null && !ctx.hasViolations(), mail.accountExists(),
                ctx -> MailRecipient.user(ctx.get(EXISTING_USER, EntityInstance.class).id()),
                Map.of(AuthMailTemplates.ENTRY, AccountContext::entryName))));
    }

    static ProcessDefinition<JoinInput, Done, AccountContext> join(SignInEntries entries) {
        return ProcessDefinition.define(JOIN, 1, JoinInput.class, Done.class, AccountContext.class, pb -> pb
            .description("Adds an entry's registration roles to the user whose address a mailed link proves.")
            .permissions(SecurityPermissions.SIGN_UP)
            .internal()
            .contextFactory((start, input) -> {
                AccountContext ctx = new AccountContext(start, null, null, null, null, null, input.token(), null);
                ctx.put(PASSWORD, input.password());
                return ctx;
            })
            .outputMapper(DONE)
            .step("Use the link", MailTokens.<AccountContext>consume(AuthMailTemplates.JOIN, AccountContext::token,
                CONSUMED))
            .step("Read the mail", MailMessageParams.<AccountContext>of(
                ctx -> ctx.consumed() == null ? null : ctx.consumed().messageId(), MAIL_PARAMS))
            .step("Load the user", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET,
                SignUpProcesses::linkUser, USERS))
            .step("Load the role assignments", QueryEntities.<AccountContext>of(SecurityEntities.USER_ROLE_DATASET,
                ctx -> Rbac.assignmentsOf(ctx.user() == null ? null : ctx.user().id()), ASSIGNMENTS))
            .step("Find the registration roles", QueryEntities.<AccountContext>of(SecurityEntities.ROLE_DATASET,
                ctx -> rolesNamed(entries.resolve(ctx.entryName()).registrationRoles()), ROLES))
            .step("Load the second factor", QueryEntities.<AccountContext>of(SecurityEntities.USER_MFA_DATASET,
                SignUpProcesses::secondFactorOf, MFA))
            // Only an address not proven before needs the new password (and only then is it hashed).
            .step("Hash the new password", on(HashPasswordStep.class), new HashPasswordStep.Metadata(PASSWORD, HASH,
                PASSWORD, true, ctx -> unproven(((AccountContext) ctx).user())))
            .compute("Join", (metadata, ctx) -> join(ctx, entries))
            .step("End the sessions", on(RevokeSessionsStep.class), new RevokeSessionsStep.Metadata(END_SESSIONS,
                RefreshTokenStore.REASON_JOIN, ctx -> ctx.get(END_SESSIONS) != null && !ctx.hasViolations())));
    }

    static ProcessDefinition<VerifyInput, Done, AccountContext> verify() {
        return ProcessDefinition.define(VERIFY, 1, VerifyInput.class, Done.class, AccountContext.class, pb -> pb
            .description("Proves a user's e-mail address with the link of a verification mail.")
            .permissions(SecurityPermissions.SIGN_UP)
            .internal()
            .contextFactory((start, input) -> new AccountContext(start, null, null, null, null, null, input.token(),
                null))
            .outputMapper(DONE)
            .step("Use the link", MailTokens.<AccountContext>consume(AuthMailTemplates.VERIFY, AccountContext::token,
                CONSUMED))
            .step("Load the user", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET,
                SignUpProcesses::linkUser, USERS))
            .compute("Record the proof", (metadata, ctx) -> {
                EntityInstance user = linkedUser(ctx);
                if (user != null) {
                    ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), proof(ctx, user));
                }
            }));
    }

    static ProcessDefinition<VerifySendInput, Done, AccountContext> verifySend(AuthMailTemplates.Templates mail) {
        return ProcessDefinition.define(VERIFY_SEND, 1, VerifySendInput.class, Done.class, AccountContext.class,
            pb -> pb
                .description("Sends a new verification mail to a user whose address is not verified.")
                .permissions(SecurityPermissions.SIGN_UP)
                .internal()
                .contextFactory((start, input) -> new AccountContext(start, input.entry(), input.email(), null, null,
                    input.userId(), null, null))
                .outputMapper(DONE)
                .step("Find the user", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET,
                    ctx -> ctx.userId() != null ? byId(ctx.userId()) : LoadUserByEmail.query(ctx.email()), USERS))
                .step("Send the verification", SendMail.<AccountContext>when(ctx -> {
                        EntityInstance user = ctx.user();
                        return user != null && Rbac.enabled(user) && user.get("email") != null && unproven(user);
                    }, mail.verifyEmail(), ctx -> MailRecipient.user(ctx.user().id()),
                    Map.of(AuthMailTemplates.ENTRY, AccountContext::entryName))));
    }

    static ProcessDefinition<ResetRequestInput, Done, AccountContext> resetRequest(
        AuthMailTemplates.Templates mail) {
        return ProcessDefinition.define(RESET_REQUEST, 1, ResetRequestInput.class, Done.class, AccountContext.class,
            pb -> pb
                .description("Sends a password reset link to the enabled user of an address.")
                .permissions(SecurityPermissions.SIGN_UP)
                .internal()
                .contextFactory((start, input) -> new AccountContext(start, input.entry(), input.email(), null, null,
                    null, null, null))
                .outputMapper(DONE)
                .step("Find the address", LoadUserByEmail.<AccountContext>into(AccountContext::email, USERS))
                .step("Send the link", SendMail.<AccountContext>when(
                    ctx -> ctx.user() != null && Rbac.enabled(ctx.user()), mail.passwordReset(),
                    ctx -> MailRecipient.user(ctx.user().id()),
                    Map.of(AuthMailTemplates.ENTRY, AccountContext::entryName))));
    }

    static ProcessDefinition<ResetConfirmInput, Done, AccountContext> resetConfirm(LoginAttemptPolicy policy) {
        return ProcessDefinition.define(RESET_CONFIRM, 1, ResetConfirmInput.class, Done.class, AccountContext.class,
            pb -> pb
                .description("Sets a new password with the link of a password reset mail and ends every session.")
                .permissions(SecurityPermissions.SIGN_UP)
                .internal()
                .contextFactory((start, input) -> {
                    AccountContext ctx = new AccountContext(start, null, null, null, null, null, input.token(),
                        input.source());
                    ctx.put(PASSWORD, input.password());
                    return ctx;
                })
                .outputMapper(DONE)
                .step("Use the link", MailTokens.<AccountContext>consume(AuthMailTemplates.RESET,
                    AccountContext::token, CONSUMED))
                .step("Read the mail", MailMessageParams.<AccountContext>of(
                    ctx -> ctx.consumed() == null ? null : ctx.consumed().messageId(), MAIL_PARAMS))
                .step("Load the user", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET,
                    SignUpProcesses::linkUser, USERS))
                .step("Load the latest login record", QueryEntities.<AccountContext>of(
                    SecurityEntities.LOGIN_RECORD_DATASET,
                    ctx -> Rbac.latestLoginRecordOf(ctx.user() == null ? null : ctx.user().id()), LATEST_RECORD))
                .step("Load the second factor", QueryEntities.<AccountContext>of(SecurityEntities.USER_MFA_DATASET,
                    SignUpProcesses::secondFactorOf, MFA))
                // Not for a link that found nobody: no hashing work for made-up tokens.
                .step("Hash the new password", on(HashPasswordStep.class), new HashPasswordStep.Metadata(PASSWORD,
                    HASH, PASSWORD, false, ctx -> ((AccountContext) ctx).user() != null))
                .compute("Set the password", (metadata, ctx) -> resetPassword(ctx, policy))
                .step("End the sessions", on(RevokeSessionsStep.class), new RevokeSessionsStep.Metadata(END_SESSIONS,
                    RefreshTokenStore.REASON_PASSWORD_RESET,
                    ctx -> ctx.get(END_SESSIONS) != null && !ctx.hasViolations())));
    }

    // ---- computations ---------------------------------------------------------------------------------------------

    /** Registers a new user, or notes the user of the address to tell; the submitted password is never theirs. */
    static void register(AccountContext ctx, SignInEntries entries) {
        SignInEntries.Entry entry = entries.find(ctx.entryName()).filter(SignInEntries.Entry::selfRegistration)
            .orElse(null);
        if (entry == null) {
            ctx.reject(new Violation(null, PlatformErrorCodes.SIGN_UP_DISABLED, "Registration is not open here"));
            return;
        }
        Map<String, EntityInstance> roles = registrationRoles(ctx, entry);
        if (roles == null) {
            return;
        }
        List<EntityInstance> found = ctx.list(USERS);
        if (!found.isEmpty()) {
            EntityInstance existing = ctx.user();
            if (existing != null && Rbac.enabled(existing)) {
                ctx.put(EXISTING_USER, existing);
            }
            return;
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userName", ctx.list(NAMES).isEmpty() ? baseName(ctx.email())
            : suffixed(baseName(ctx.email()), ctx.get(RANDOM, byte[].class)));
        user.put("displayName", ctx.displayName() == null || ctx.displayName().isBlank() ? null
            : ctx.displayName().strip());
        user.put("email", ctx.email());
        user.put("locale", ctx.locale());
        user.put("enabled", true);
        user.put("passwordHash", ctx.get(HASH, String.class));
        Object userId = ctx.changes().insert(SecurityEntities.USER, user);
        for (EntityInstance role : roles.values()) {
            ctx.changes().insert(SecurityEntities.USER_ROLE, Map.of("userId", userId, "roleId", role.id()));
        }
        ctx.put(NEW_USER, userId);
    }

    /** Adds the entry's registration roles; an address not proven before also takes the new password. */
    static void join(AccountContext ctx, SignInEntries entries) {
        EntityInstance user = linkedUser(ctx);
        if (user == null) {
            return;
        }
        SignInEntries.Entry entry = entries.find(ctx.entryName()).filter(SignInEntries.Entry::selfRegistration)
            .orElse(null);
        if (entry == null) {
            ctx.reject(new Violation(null, PlatformErrorCodes.SIGN_UP_DISABLED, "Registration is not open here"));
            return;
        }
        Map<String, EntityInstance> roles = registrationRoles(ctx, entry);
        if (roles == null) {
            return;
        }
        boolean unproven = unproven(user);
        String hash = ctx.get(HASH, String.class);
        if (unproven && hash == null) {
            // Whoever registered the address first may know the password: it cannot stay (implementation note 6).
            ctx.reject(new Violation(PASSWORD, PlatformErrorCodes.REQUIRED,
                "A new password is required to join with an unverified address", Map.of("field", PASSWORD)));
            return;
        }
        Set<String> held = new HashSet<>();
        ctx.list(ASSIGNMENTS).forEach(assignment -> held.add(String.valueOf(assignment.<Object>get("roleId"))));
        for (EntityInstance role : roles.values()) {
            if (!held.contains(String.valueOf(role.id()))) {
                ctx.changes().insert(SecurityEntities.USER_ROLE, Map.of("userId", user.id(), "roleId", role.id()));
            }
        }
        Map<String, Object> changed = proof(ctx, user);
        if (unproven) {
            changed.put("passwordHash", hash);
            claim(ctx, user);
        }
        ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), changed);
    }

    /** Sets the password, proves the address and records the reset, which lifts a lock. */
    static void resetPassword(AccountContext ctx, LoginAttemptPolicy policy) {
        EntityInstance user = linkedUser(ctx);
        String hash = ctx.get(HASH, String.class);
        if (user == null || hash == null) {
            return;
        }
        Map<String, Object> changed = proof(ctx, user);
        changed.put("passwordHash", hash);
        if (unproven(user)) {
            claim(ctx, user);
        }
        // Sessions opened with the old password must not live on (decision D36 item 5).
        ctx.put(END_SESSIONS, user);
        ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), changed);
        List<EntityInstance> latest = ctx.list(LATEST_RECORD);
        EntityInstance previous = latest.isEmpty() ? null : latest.getFirst();
        LoginAttemptPolicy.State next = policy.next(Rbac.state(previous), LoginOutcome.PASSWORD_RESET, ctx.opTime());
        String entry = ctx.entryName();
        ctx.changes().insert(SecurityEntities.LOGIN_RECORD, SponsorSignInProcess.record(user.id(),
            user.get("userName"), LoginOutcome.PASSWORD_RESET, next, ctx.opTime(), ctx.request().requestId(), null,
            Rbac.mfaStep(previous), ctx.source().withEntry(entry)));
    }

    /**
     * The user of an address that was not proven until now: whoever registered it may have set up a second factor
     * and may hold sessions. Both go with the new password (account pre-claim protection).
     */
    private static void claim(AccountContext ctx, EntityInstance user) {
        for (EntityInstance mfa : ctx.list(MFA)) {
            ctx.changes().delete(SecurityEntities.USER_MFA, mfa.id(), mfa.version());
        }
        ctx.put(END_SESSIONS, user);
    }

    /** The verification fields proving the user's current address. */
    private static Map<String, Object> proof(AccountContext ctx, EntityInstance user) {
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("verifiedEmail", user.get("email"));
        changed.put("emailVerifiedAt", ctx.opTime());
        return changed;
    }

    /**
     * The user a used link stands for: enabled and still at the address the mail went to (regardless of case).
     * Otherwise the link is void: {@code TOKEN_INVALID}, as for any other invalid link (once).
     */
    static EntityInstance linkedUser(AccountContext ctx) {
        MailTokens.Consumed consumed = ctx.consumed();
        if (consumed == null) {
            // The token step rejected the link already.
            return null;
        }
        EntityInstance user = ctx.user();
        if (user == null || !Rbac.enabled(user) || consumed.userId() == null
            || !consumed.userId().equals(String.valueOf(user.id()))
            || !EmailVerification.verified(user.get("email"), consumed.address())) {
            if (!ctx.hasViolations()) {
                ctx.reject(new Violation(null, PlatformErrorCodes.TOKEN_INVALID, "Invalid token (user or address "
                    + "changed)"));
            }
            return null;
        }
        return user;
    }

    /** The entry's registration roles by code, enabled and existing; null (and a violation) when one is missing. */
    private static Map<String, EntityInstance> registrationRoles(AccountContext ctx, SignInEntries.Entry entry) {
        Map<String, EntityInstance> byCode = new LinkedHashMap<>();
        ctx.list(ROLES).stream().filter(Rbac::enabled)
            .forEach(role -> byCode.put(String.valueOf(role.<Object>get("roleCode")), role));
        if (!byCode.keySet().containsAll(entry.registrationRoles())) {
            log.warn("Sign-in entry {} grants roles that do not exist or are disabled: {}", entry.name(),
                entry.registrationRoles().stream().filter(code -> !byCode.containsKey(code)).sorted().toList());
            ctx.reject(new Violation(null, PlatformErrorCodes.SIGN_UP_DISABLED, "Registration is not open here"));
            return null;
        }
        byCode.keySet().retainAll(entry.registrationRoles());
        return byCode;
    }

    private static boolean unproven(EntityInstance user) {
        return user != null && !SecurityEntities.emailVerified(user);
    }

    private static EntityQuery linkUser(AccountContext ctx) {
        MailTokens.Consumed consumed = ctx.consumed();
        return consumed == null || consumed.userId() == null ? byId(null) : byId(consumed.userId());
    }

    private static EntityQuery byId(String userId) {
        QueryPredicate where;
        try {
            where = userId == null ? new QueryPredicate.In("userId", List.of())
                : new QueryPredicate.Eq("userId", UUID.fromString(userId));
        } catch (IllegalArgumentException notAUserId) {
            where = new QueryPredicate.In("userId", List.of());
        }
        return EntityQuery.builder().where(where).limit(1).build();
    }

    private static EntityQuery secondFactorOf(AccountContext ctx) {
        EntityInstance user = ctx.user();
        return Rbac.all(new QueryPredicate.In("userId", user == null ? List.of() : List.of(user.id())), "userId");
    }

    private static EntityQuery rolesNamed(Set<String> codes) {
        return Rbac.all(new QueryPredicate.In("roleCode", List.copyOf(codes)), "roleCode");
    }

    /** The user name a registration starts from: the address in lower case (implementation note 12), cut to fit. */
    static String baseName(String email) {
        String name = EmailVerification.normalize(email == null ? "" : email.strip());
        return name.length() > MAX_USER_NAME ? name.substring(0, MAX_USER_NAME) : name;
    }

    /** The name with {@code -} and six random letters or digits, when the plain one is taken. */
    static String suffixed(String base, byte[] random) {
        StringBuilder name = new StringBuilder(base.length() > MAX_USER_NAME - SUFFIX_LENGTH - 1
            ? base.substring(0, MAX_USER_NAME - SUFFIX_LENGTH - 1) : base).append('-');
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            name.append(SUFFIX_ALPHABET.charAt((random[i] & 0xff) % SUFFIX_ALPHABET.length()));
        }
        return name.toString();
    }

    /** The language of the verification mail: as asked, else the request's. */
    private static Locale mailLocale(AccountContext ctx) {
        return ctx.locale() != null ? Locale.of(ctx.locale()) : ctx.request().locale();
    }

    // ---- beans ----------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<SignUpInput, Done, AccountContext> sponsorSignUpProcess(AuthMailTemplates.Templates mail,
        SignInEntries entries) {
        return signUp(mail, entries);
    }

    @Bean
    ProcessDefinition<JoinInput, Done, AccountContext> sponsorSignUpJoinProcess(SignInEntries entries) {
        return join(entries);
    }

    @Bean
    ProcessDefinition<VerifyInput, Done, AccountContext> sponsorEmailVerifyProcess() {
        return verify();
    }

    @Bean
    ProcessDefinition<VerifySendInput, Done, AccountContext> sponsorEmailVerifySendProcess(
        AuthMailTemplates.Templates mail) {
        return verifySend(mail);
    }

    @Bean
    ProcessDefinition<ResetRequestInput, Done, AccountContext> sponsorPasswordResetRequestProcess(
        AuthMailTemplates.Templates mail) {
        return resetRequest(mail);
    }

    @Bean
    ProcessDefinition<ResetConfirmInput, Done, AccountContext> sponsorPasswordResetConfirmProcess(
        LoginAttemptPolicy policy) {
        return resetConfirm(policy);
    }
}

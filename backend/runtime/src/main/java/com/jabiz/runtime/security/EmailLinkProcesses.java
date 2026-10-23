package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailRecipient;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.MailMessageParams;
import com.jabiz.runtime.mail.MailTokens;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.EmailVerification;
import com.jabiz.security.Sensitive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Signing in with a mailed one-time link (docs/design/10-security.md section 15.11; decision D54), at an entry that
 * turns it on ({@code jabiz.security.entries.<name>.email-link}; never the administration, nor an entry taking every
 * role). Both processes run only through {@code EmailLinkController} ({@code entryOnly()}, {@code SIGN_UP} granted to
 * no role):
 * <ul>
 *   <li>{@value #SEND}: answered alike for every address. An enabled user at a trusted address (verified, or
 *       pre-claimable) gets a sign-in link; an address nobody uses gets one that opens an account, where the entry lets
 *       people register; anyone else gets nothing.</li>
 *   <li>{@value #OPEN}: uses the link (the first of the recipient's links used wins, as for verification, D53), then
 *       checks the user it stands for as any mailed link is checked (enabled, still at that address) and proves the
 *       address. A pre-claimable account is taken over (its password, second factor, provider links and sessions go:
 *       whoever registered it may not own the mailbox). An address link opens the account then: verified, no password,
 *       the entry's registration roles; when the address meanwhile belongs to a user at a trusted address, the link
 *       stands for that user. The sign-in itself, with its record, guards and second factor, is
 *       {@code SPONSOR_EMAIL_LINK_SIGN_IN} run right afterwards.</li>
 * </ul>
 * A link carries the path of the entry's front end to return to ({@code to}): a relative path, checked before it is
 * mailed and again by the page.
 */
@Configuration
public class EmailLinkProcesses {

    public static final String SEND = "SPONSOR_EMAIL_LINK_SEND";
    public static final String OPEN = "SPONSOR_EMAIL_LINK_OPEN";

    public static final String TEMPLATE = "jabiz.auth.email-link";
    /** The token's purpose and placeholder, in the platform's namespace. */
    public static final String SIGN_IN = "jabiz.signIn";
    /** The parameter with the path to return to in the entry's front end. */
    public static final String TO = "to";
    /** Longest path to return to. */
    public static final int MAX_TO = 200;
    /**
     * A path of the front end: starts with one {@code /}, then the characters of a path and its query only (no
     * scheme, host, {@code //} or {@code \}), so the link can only lead within the front end.
     */
    static final Pattern RETURN_PATH = Pattern.compile("/(?![/\\\\])[A-Za-z0-9._~!$&'()*+,;=:@%/?-]*");

    /** Whether {@code to} may be mailed as the path to return to: empty (the front end's root), or a path of it. */
    public static boolean returnPath(String to) {
        return to == null || to.isEmpty() || (to.length() <= MAX_TO && RETURN_PATH.matcher(to).matches()
            && !to.contains("/../") && !to.endsWith("/..") && !to.contains("\\"));
    }

    /**
     * @param to     the path to return to, checked ({@link #returnPath}); empty for the root
     * @param locale the language of the mail to an address nobody uses ({@code zh}, {@code ja}, {@code en}); optional
     */
    public record SendInput(String entry, String email, String to, String locale) {}

    public record OpenInput(@Sensitive String token) {
        @Override
        public String toString() {
            return "OpenInput[***]";
        }
    }

    /**
     * @param userId  the user the link stands for
     * @param entry   the entry the link was mailed for
     * @param created whether the link opened the account
     */
    public record OpenOutput(String userId, String entry, boolean created) {}

    /** The link: one token, valid {@code jabiz.security.email-link.ttl} (15 minutes); the entry and the path. */
    static MailTemplate template(Duration validity) {
        return MailTemplate.define(TEMPLATE, t -> t.category(MailCategory.TRANSACTIONAL)
            .param(AuthMailTemplates.ENTRY).param(TO).token(SIGN_IN, validity));
    }

    private static final String TO_USER = "to_user";
    private static final String TO_ADDRESS = "to_address";
    private static final String OPENED = "opened";

    // ---- the request ------------------------------------------------------------------------------------------------

    static ProcessDefinition<SendInput, SignUpProcesses.Done, AccountContext> send(MailTemplate template,
        SignInEntries entries) {
        return ProcessDefinition.define(SEND, 1, SendInput.class, SignUpProcesses.Done.class, AccountContext.class,
            pb -> pb
                .description("Mails a one-time sign-in link to the address, if it may have one.")
                .permissions(SecurityPermissions.SIGN_UP)
                .entryOnly()
                .contextFactory((start, input) -> {
                    AccountContext ctx = new AccountContext(start, input.entry(), input.email(), null,
                        input.locale(), null, null, null);
                    ctx.put(TO, input.to() == null ? "" : input.to());
                    return ctx;
                })
                .outputMapper(ctx -> SignUpProcesses.Done.DONE)
                .step("Find the address", LoadUserByEmail.<AccountContext>into(AccountContext::email,
                    SignUpProcesses.USERS))
                .compute("Decide", (metadata, ctx) -> {
                    SignInEntries.Entry entry = entries.find(ctx.entryName()).filter(SignInEntries.Entry::emailLink)
                        .orElse(null);
                    if (entry == null || !returnPath((String) ctx.get(TO))) {
                        return;
                    }
                    List<EntityInstance> found = ctx.list(SignUpProcesses.USERS);
                    EntityInstance user = ctx.user();
                    if (!found.isEmpty()) {
                        // An address an administrator set and nobody verified gets nothing: its link proves nothing.
                        if (user != null && Rbac.enabled(user) && SignUpProcesses.trusted(user)) {
                            ctx.put(TO_USER, user.id());
                        }
                    } else if (entry.selfRegistration()) {
                        ctx.put(TO_ADDRESS, true);
                    }
                })
                .step("Send the link", SendMail.<AccountContext>when(ctx -> ctx.get(TO_USER) != null, template,
                    ctx -> MailRecipient.user(ctx.get(TO_USER)), params()))
                .step("Send the link to the address", SendMail.<AccountContext>when(
                    ctx -> ctx.get(TO_ADDRESS) != null, template,
                    ctx -> MailRecipient.address(ctx.email(), SignUpProcesses.mailLocale(ctx)), params())));
    }

    private static Map<String, java.util.function.Function<AccountContext, ?>> params() {
        return Map.of(AuthMailTemplates.ENTRY, AccountContext::entryName, TO, ctx -> ctx.get(TO));
    }

    // ---- the link ---------------------------------------------------------------------------------------------------

    static ProcessDefinition<OpenInput, OpenOutput, AccountContext> open(SignInEntries entries) {
        return ProcessDefinition.define(OPEN, 1, OpenInput.class, OpenOutput.class, AccountContext.class, pb -> pb
            .description("Uses a mailed sign-in link: proves the address, and opens the account where it may.")
            .permissions(SecurityPermissions.SIGN_UP)
            .entryOnly()
            .contextFactory((start, input) -> new AccountContext(start, null, null, null, null, null,
                input.token(), null))
            .outputMapper(ctx -> ctx.get(OPENED, OpenOutput.class))
            .step("Use the link", MailTokens.<AccountContext>consumeAnyUnused(SIGN_IN, AccountContext::token,
                SignUpProcesses.CONSUMED))
            .step("Read the mail", MailMessageParams.<AccountContext>of(
                ctx -> ctx.consumed() == null ? null : ctx.consumed().messageId(), SignUpProcesses.MAIL_PARAMS))
            // The user the link went to, or (a link to an address) whoever has the address now.
            .step("Load the user", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET, ctx -> {
                MailTokens.Consumed consumed = ctx.consumed();
                if (consumed == null) {
                    return SignUpProcesses.byId(null);
                }
                return consumed.userId() != null ? SignUpProcesses.byId(consumed.userId())
                    : LoadUserByEmail.query(consumed.address());
            }, SignUpProcesses.USERS))
            .step("Find the user name", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userName", SignUpProcesses.baseName(
                    ctx.consumed() == null ? "" : ctx.consumed().address()))).limit(1).build(), SignUpProcesses.NAMES))
            .step("Find the registration roles", QueryEntities.<AccountContext>of(SecurityEntities.ROLE_DATASET,
                ctx -> SignUpProcesses.rolesNamed(entries.resolve(ctx.entryName()).registrationRoles()),
                SignUpProcesses.ROLES))
            .step("Load the second factor", QueryEntities.<AccountContext>of(SecurityEntities.USER_MFA_DATASET,
                ctx -> ofUser(ctx), SignUpProcesses.MFA))
            .step("Load the provider links", QueryEntities.<AccountContext>of(SecurityEntities.USER_IDENTITY_DATASET,
                ctx -> ofUser(ctx), SignUpProcesses.IDENTITIES))
            .step("Draw a name suffix", SignUpProcesses.on(RandomBytesStep.class), new RandomBytesStep.Metadata(
                SignUpProcesses.RANDOM, SignUpProcesses.SUFFIX_LENGTH))
            .compute("Sign in or open the account", (metadata, ctx) -> opened(ctx, entries))
            .step("End the sessions", SignUpProcesses.on(RevokeSessionsStep.class), new RevokeSessionsStep.Metadata(
                SignUpProcesses.END_SESSIONS, RefreshTokenStore.REASON_CLAIM,
                ctx -> ctx.get(SignUpProcesses.END_SESSIONS) != null && !ctx.hasViolations())));
    }

    private static EntityQuery ofUser(AccountContext ctx) {
        EntityInstance user = ctx.user();
        return Rbac.all(new QueryPredicate.In("userId", user == null ? List.of() : List.of(user.id())), "userId");
    }

    static void opened(AccountContext ctx, SignInEntries entries) {
        MailTokens.Consumed consumed = ctx.consumed();
        if (consumed == null) {
            // The token step rejected the link already.
            return;
        }
        SignInEntries.Entry entry = entries.find(ctx.entryName()).filter(SignInEntries.Entry::emailLink).orElse(null);
        if (entry == null) {
            invalid(ctx, "the entry no longer signs in by link");
            return;
        }
        EntityInstance user = ctx.user();
        boolean toUser = consumed.userId() != null;
        if (user != null) {
            boolean same = !toUser || consumed.userId().equals(String.valueOf(user.id()));
            if (!same || !Rbac.enabled(user) || !EmailVerification.verified(user.get("email"), consumed.address())
                || !SignUpProcesses.trusted(user)) {
                invalid(ctx, "user or address changed");
                return;
            }
            Map<String, Object> changed = SignUpProcesses.proof(ctx, user);
            if (SignUpProcesses.preClaimable(user)) {
                // Whoever registered the address may not own the mailbox: the account is the mailbox owner's now.
                ctx.put(SignUpProcesses.LINKED, user);
                SignUpProcesses.claim(ctx);
                changed.put("passwordHash", null);
            }
            if (!SecurityEntities.emailVerified(user) || changed.containsKey("passwordHash")) {
                ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), changed);
            }
            ctx.put(OPENED, new OpenOutput(String.valueOf(user.id()), entry.name(), false));
            return;
        }
        if (toUser || !entry.selfRegistration()) {
            invalid(ctx, toUser ? "the user is gone" : "the entry takes no registrations");
            return;
        }
        Map<String, EntityInstance> roles = SignUpProcesses.registrationRoles(ctx, entry);
        if (roles == null) {
            return;
        }
        String base = SignUpProcesses.baseName(consumed.address());
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("userName", ctx.list(SignUpProcesses.NAMES).isEmpty() ? base
            : SignUpProcesses.suffixed(base, ctx.get(SignUpProcesses.RANDOM, byte[].class)));
        created.put("email", consumed.address());
        // The mailbox is proven: verified at once. Not registeredEmail: never pre-claimable; no password.
        created.put("verifiedEmail", consumed.address());
        created.put("emailVerifiedAt", ctx.opTime());
        created.put("enabled", true);
        Object userId = ctx.changes().insert(SecurityEntities.USER, created);
        for (EntityInstance role : roles.values()) {
            ctx.changes().insert(SecurityEntities.USER_ROLE, Map.of("userId", userId, "roleId", role.id()));
        }
        ctx.put(OPENED, new OpenOutput(String.valueOf(userId), entry.name(), true));
    }

    private static void invalid(AccountContext ctx, String why) {
        if (!ctx.hasViolations()) {
            ctx.reject(new Violation(null, PlatformErrorCodes.TOKEN_INVALID, "Invalid token (" + why + ")"));
        }
    }

    // ---- beans ------------------------------------------------------------------------------------------------------

    @Bean
    MailTemplate authEmailLinkMail(@Value("${jabiz.security.email-link.ttl:PT15M}") Duration validity) {
        return template(validity);
    }

    @Bean
    ProcessDefinition<SendInput, SignUpProcesses.Done, AccountContext> sponsorEmailLinkSendProcess(
        @org.springframework.beans.factory.annotation.Qualifier("authEmailLinkMail") MailTemplate template,
        SignInEntries entries) {
        return send(template, entries);
    }

    @Bean
    ProcessDefinition<OpenInput, OpenOutput, AccountContext> sponsorEmailLinkOpenProcess(SignInEntries entries) {
        return open(entries);
    }
}

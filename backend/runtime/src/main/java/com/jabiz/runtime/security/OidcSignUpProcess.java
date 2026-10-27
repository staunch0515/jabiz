package com.jabiz.runtime.security;

import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An identity provider opens an account at the first sign-in (docs/design/10-security.md section 12; decision D39
 * items 1 and 4; ROADMAP phase 16f). Runs only through the sign-in callback ({@code entryOnly()}, permission
 * {@link SecurityPermissions#SIGN_UP} granted to no role), after the platform has checked the ID token, found no user
 * linked to its subject, and found the provider allowed to open accounts, the entry open to registration and the address
 * one the provider vouches for ({@link IdTokenValidator.Identity#trustedEmail}). The sign-in itself, with its login
 * record, guards, lock and second factor, is {@code SPONSOR_OIDC_SIGN_IN} run again afterwards.
 *
 * <p>The new user is named as a registration names it (the address in lower case, with a random suffix when taken), has
 * the address verified (the provider proved it) but not as {@code registeredEmail} (so it is never pre-claimable), no
 * password, the entry's registration roles and the link. A provider that opens accounts for addresses it does not vouch
 * for ({@link OidcProvider#signUpUnverifiedEmail}, decision D60 item 3) gets the address unverified and as
 * {@code registeredEmail} instead: pre-claimable as a self-registration is, so the mailbox's owner can always take the
 * account over (and its link goes with that), with the same verification mail. Nothing is opened when the address belongs to a user already,
 * whatever that user is ({@link Outcome#EMAIL_IN_USE}: the provider's word never takes over an account), when the
 * subject is linked by now ({@link Outcome#EXISTS}), or when the entry no longer lets people register or its
 * registration roles are missing or disabled ({@link Outcome#DISABLED}).
 */
@Configuration
public class OidcSignUpProcess {

    private static final Logger log = LoggerFactory.getLogger(OidcSignUpProcess.class);

    public static final String NAME = "SPONSOR_OIDC_SIGN_UP";

    /** What became of the request. */
    public enum Outcome {
        /** A new user, linked to the subject. */
        CREATED,
        /** The subject is linked already (a concurrent sign-in opened the account): sign in. */
        EXISTS,
        /** The address belongs to a user: nothing opened, nothing linked. */
        EMAIL_IN_USE,
        /** The entry does not let people register (any more), or its roles are missing. */
        DISABLED
    }

    /**
     * @param entry       the sign-in entry the sign-in started in (its state's)
     * @param provider    the provider's id
     * @param subject     the provider's subject
     * @param email         the address the token gave
     * @param displayName   the name the token gave, cleaned; optional
     * @param locale        the language of the request, one of the platform's; optional
     * @param emailVerified whether the provider vouches for the address ({@link IdTokenValidator.Identity#trustedEmail});
     *                      absent (inputs from before decision D60) means it does
     */
    public record Input(@NotBlank String entry, @NotBlank String provider, @NotBlank String subject,
        @NotBlank String email, String displayName, String locale, Boolean emailVerified) {

        /** An address the provider vouches for (decision D39). */
        public Input(String entry, String provider, String subject, String email, String displayName,
            String locale) {
            this(entry, provider, subject, email, displayName, locale, true);
        }
    }

    public record Output(Outcome outcome) {}

    // Context keys (others as SignUpProcesses names them).
    static final String PROVIDER = "provider";
    static final String SUBJECT = "subject";
    static final String LINKS = "links";
    static final String OUTCOME = "outcome";
    static final String VERIFIED = "verified";

    static ProcessDefinition<Input, Output, AccountContext> definition(SignInEntries entries,
        AuthMailTemplates.Templates mail) {
        return ProcessDefinition.define(NAME, 1, Input.class, Output.class, AccountContext.class, pb -> pb
            .description("Opens an account for a provider's subject with a verified address nobody uses.")
            .permissions(SecurityPermissions.SIGN_UP)
            .entryOnly()
            .contextFactory((start, input) -> {
                AccountContext ctx = new AccountContext(start, input.entry(), input.email(), input.displayName(),
                    input.locale(), null, null, null);
                ctx.put(PROVIDER, input.provider());
                ctx.put(SUBJECT, input.subject());
                ctx.put(VERIFIED, !Boolean.FALSE.equals(input.emailVerified()));
                return ctx;
            })
            .outputMapper(ctx -> new Output(ctx.get(OUTCOME, Outcome.class)))
            .step("Find the address", LoadUserByEmail.<AccountContext>into(AccountContext::email,
                SignUpProcesses.USERS))
            .step("Find the link", QueryEntities.<AccountContext>of(SecurityEntities.USER_IDENTITY_DATASET,
                ctx -> Rbac.all(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("provider", ctx.get(PROVIDER, String.class)),
                    new QueryPredicate.Eq("subject", ctx.get(SUBJECT, String.class)))), "provider"), LINKS))
            .step("Find the user name", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userName",
                    SignUpProcesses.baseName(ctx.email()))).limit(1).build(), SignUpProcesses.NAMES))
            .step("Find the registration roles", QueryEntities.<AccountContext>of(SecurityEntities.ROLE_DATASET,
                ctx -> Rbac.all(new QueryPredicate.In("roleCode",
                    List.copyOf(entries.resolve(ctx.entryName()).registrationRoles())), "roleCode"),
                SignUpProcesses.ROLES))
            .step("Draw a name suffix", SignUpProcesses.on(RandomBytesStep.class), new RandomBytesStep.Metadata(
                SignUpProcesses.RANDOM, SignUpProcesses.SUFFIX_LENGTH))
            .compute("Open the account", (metadata, ctx) -> open(ctx, entries))
            // An address the provider did not vouch for is verified by mail, as a self-registration's (D60 item 3).
            .step("Send the verification", SendMail.<AccountContext>when(
                ctx -> ctx.get(SignUpProcesses.NEW_USER) != null && !ctx.hasViolations(), mail.verifyEmail(),
                ctx -> MailRecipient.user(ctx.get(SignUpProcesses.NEW_USER), ctx.email(),
                    SignUpProcesses.mailLocale(ctx)),
                Map.of(AuthMailTemplates.ENTRY, AccountContext::entryName))));
    }

    /** Opens the account, or says why not. */
    static void open(AccountContext ctx, SignInEntries entries) {
        SignInEntries.Entry entry = entries.find(ctx.entryName()).filter(SignInEntries.Entry::selfRegistration)
            .orElse(null);
        if (entry == null) {
            ctx.put(OUTCOME, Outcome.DISABLED);
            return;
        }
        if (!ctx.list(LINKS).isEmpty()) {
            ctx.put(OUTCOME, Outcome.EXISTS);
            return;
        }
        if (!ctx.list(SignUpProcesses.USERS).isEmpty()) {
            ctx.put(OUTCOME, Outcome.EMAIL_IN_USE);
            return;
        }
        Map<String, EntityInstance> roles = new LinkedHashMap<>();
        ctx.list(SignUpProcesses.ROLES).stream().filter(Rbac::enabled)
            .forEach(role -> roles.put(String.valueOf(role.<Object>get("roleCode")), role));
        if (entry.registrationRoles().isEmpty() || !roles.keySet().containsAll(entry.registrationRoles())) {
            log.warn("Sign-in entry {} grants roles that do not exist or are disabled: {}", entry.name(),
                entry.registrationRoles().stream().filter(code -> !roles.containsKey(code)).sorted().toList());
            ctx.put(OUTCOME, Outcome.DISABLED);
            return;
        }
        roles.keySet().retainAll(entry.registrationRoles());
        String base = SignUpProcesses.baseName(ctx.email());
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userName", ctx.list(SignUpProcesses.NAMES).isEmpty() ? base
            : SignUpProcesses.suffixed(base, ctx.get(SignUpProcesses.RANDOM, byte[].class)));
        user.put("displayName", ctx.displayName());
        user.put("email", ctx.email());
        if (Boolean.TRUE.equals(ctx.get(VERIFIED))) {
            // The provider proved the address: verified at once. Not registeredEmail: never pre-claimable.
            user.put("verifiedEmail", ctx.email());
            user.put("emailVerifiedAt", ctx.opTime());
        } else {
            // Nobody proved the address (decision D60 item 3): pre-claimable, as a self-registration.
            user.put("registeredEmail", ctx.email());
        }
        user.put("locale", ctx.locale());
        user.put("enabled", true);
        Object userId = ctx.changes().insert(SecurityEntities.USER, user);
        for (EntityInstance role : roles.values()) {
            ctx.changes().insert(SecurityEntities.USER_ROLE, Map.of("userId", userId, "roleId", role.id()));
        }
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("userId", userId);
        link.put("provider", ctx.get(PROVIDER, String.class));
        link.put("subject", ctx.get(SUBJECT, String.class));
        link.put("email", ctx.email());
        ctx.changes().insert(SecurityEntities.USER_IDENTITY, link);
        if (!Boolean.TRUE.equals(ctx.get(VERIFIED))) {
            ctx.put(SignUpProcesses.NEW_USER, userId);
        }
        ctx.put(OUTCOME, Outcome.CREATED);
    }

    @Bean
    ProcessDefinition<Input, Output, AccountContext> sponsorOidcSignUpProcess(SignInEntries entries,
        AuthMailTemplates.Templates mail) {
        return definition(entries, mail);
    }
}

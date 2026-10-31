package com.jabiz.runtime.security;

import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.runtime.EntityInstance;
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
 * Opens an account for a person another platform vouched for with a signed token (decision D59; docs/design/10-security.md
 * section 15.12), at an entry that lets people register and only when the provider may open accounts: a user without
 * an e-mail address or a password, holding the entry's registration roles, linked to the provider's identity
 * ({@code ext:<provider>}, {@code <tenant>/<sub>}). The same person in another tenant is another user. Run by the
 * sign-in endpoint only ({@code entryOnly}); the sign-in follows.
 */
@Configuration
public class ExternalTokenSignUpProcess {

    private static final Logger log = LoggerFactory.getLogger(ExternalTokenSignUpProcess.class);

    public static final String NAME = "SPONSOR_EXTERNAL_TOKEN_SIGN_UP";

    /** What became of the request. */
    public enum Outcome { CREATED, EXISTS, DISABLED }

    /**
     * @param provider the identity provider ({@code ext:<name>})
     * @param subject  the person within the tenant ({@code <tenant>/<sub>}), which also makes the user name
     */
    public record Input(@NotBlank String entry, @NotBlank String provider, @NotBlank String subject, String locale) {}

    public record Output(Outcome outcome) {}

    static final String PROVIDER = "provider";
    static final String SUBJECT = "subject";
    static final String LINKS = "links";
    static final String OUTCOME = "outcome";

    static ProcessDefinition<Input, Output, AccountContext> definition(SignInEntries entries) {
        return ProcessDefinition.define(NAME, 1, Input.class, Output.class, AccountContext.class, pb -> pb
            .description("Opens an account for a person another platform vouched for with a signed token.")
            .permissions(SecurityPermissions.SIGN_UP)
            .entryOnly()
            .contextFactory((start, input) -> {
                AccountContext ctx = new AccountContext(start, input.entry(), null, null, input.locale(), null, null,
                    null);
                ctx.put(PROVIDER, input.provider());
                ctx.put(SUBJECT, input.subject());
                return ctx;
            })
            .outputMapper(ctx -> new Output(ctx.get(OUTCOME, Outcome.class)))
            .step("Find the link", QueryEntities.<AccountContext>of(SecurityEntities.USER_IDENTITY_DATASET,
                ctx -> Rbac.all(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("provider", ctx.get(PROVIDER, String.class)),
                    new QueryPredicate.Eq("subject", ctx.get(SUBJECT, String.class)))), "provider"), LINKS))
            .step("Find the user name", QueryEntities.<AccountContext>of(SecurityEntities.USER_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userName", baseName(ctx))).limit(1).build(),
                SignUpProcesses.NAMES))
            .step("Find the registration roles", QueryEntities.<AccountContext>of(SecurityEntities.ROLE_DATASET,
                ctx -> Rbac.all(new QueryPredicate.In("roleCode",
                    List.copyOf(entries.resolve(ctx.entryName()).registrationRoles())), "roleCode"),
                SignUpProcesses.ROLES))
            .step("Draw a name suffix", SignUpProcesses.on(RandomBytesStep.class), new RandomBytesStep.Metadata(
                SignUpProcesses.RANDOM, SignUpProcesses.SUFFIX_LENGTH))
            // Only where an account would be opened (D66); the token is proven, so the refusal keeps its 403.
            .step("Sign-up guards", SignUpGuardStep.spec(new SignUpGuardStep.Metadata<AccountContext>(
                SignUpAttempt.Path.EXTERNAL_TOKEN, AccountContext::entryName, ctx -> null, ctx -> null,
                ctx -> false, ctx -> ctx.list(LINKS).isEmpty()
                    && entries.find(ctx.entryName()).filter(SignInEntries.Entry::selfRegistration).isPresent())))
            .compute("Open the account", (metadata, ctx) -> open(ctx, entries)));
    }

    /** The user name: the identity's subject, which tells the tenant and the person ({@code shop/42}). */
    static String baseName(AccountContext ctx) {
        String subject = ctx.get(SUBJECT, String.class);
        return subject.length() > SignUpProcesses.MAX_USER_NAME ? subject.substring(0, SignUpProcesses.MAX_USER_NAME)
            : subject;
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
        String base = baseName(ctx);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userName", ctx.list(SignUpProcesses.NAMES).isEmpty() ? base
            : SignUpProcesses.suffixed(base, ctx.get(SignUpProcesses.RANDOM, byte[].class)));
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
        ctx.changes().insert(SecurityEntities.USER_IDENTITY, link);
        ctx.put(OUTCOME, Outcome.CREATED);
    }

    @Bean
    ProcessDefinition<Input, Output, AccountContext> sponsorExternalTokenSignUpProcess(SignInEntries entries) {
        return definition(entries);
    }
}

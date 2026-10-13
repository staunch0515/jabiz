package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A signed-in user links provider accounts to their own account and removes them (docs/design/10-security.md section
 * 12; decision D39 item 2; ROADMAP phase 16f). These run only through {@code /api/auth/account/identities/**} as the
 * user ({@code entryOnly()}, permission {@link SecurityPermissions#ACCOUNT} granted to no role), on the caller's own
 * account only.
 * <ul>
 *   <li>{@value #LINK_BEGIN}: proves that the caller is the account's user before a link starts, with the current
 *       password, when the session has no recent second factor. As at sign-in, a wrong password is recorded
 *       ({@link LoginOutcome#BAD_CREDENTIALS}) and counts towards the lock; while locked the password is not even
 *       checked. Attempts of a user run one at a time, together with password changes (the same lock).</li>
 *   <li>{@value #LINK_SELF}: links the provider account the platform has just verified. An account that registered
 *       itself and was never verified (pre-claimable, section 15.7) cannot: whoever owns the mailbox may still take it
 *       over, and must not find a way in left behind. A provider account links one user only; a user links one account
 *       of each provider; linking the same one again changes nothing. With mail on, the user is told.</li>
 *   <li>{@value #UNLINK_SELF}: removes one of the caller's links, unless it is the only way left to sign in (no
 *       password, no other link). Sessions signed in through it end at their next refresh (section 12).</li>
 * </ul>
 */
@Configuration
public class IdentityProcesses {

    public static final String LINK_BEGIN = "SEC_IDENTITY_LINK_BEGIN";
    public static final String LINK_SELF = "SEC_IDENTITY_LINK_SELF";
    public static final String UNLINK_SELF = "SEC_IDENTITY_UNLINK_SELF";

    /** Whether the current password was right; {@code INCORRECT} also while the account is locked. */
    public enum Check { VERIFIED, INCORRECT }

    /**
     * @param userId          the caller
     * @param currentPassword proves that the caller is the account's user
     * @param source          where the request came from, for the login record of a wrong password
     */
    public record BeginInput(@NotNull String userId, @Sensitive String currentPassword, SignInSource source) {
        @Override
        public String toString() {
            return "BeginInput[userId=" + userId + ", currentPassword=***]";
        }
    }

    public record BeginOutput(Check outcome) {}

    /**
     * @param userId        the caller
     * @param provider      the provider's id
     * @param subject       the provider's subject, as its verified ID token named it
     * @param email         the address the token named, for display; optional
     * @param providerLabel the provider's label in the caller's language, for the mail
     */
    public record LinkInput(@NotNull String userId, @NotBlank String provider, @NotBlank String subject, String email,
        String providerLabel) {}

    /** {@code LINKED}, or {@code ALREADY_LINKED} when the caller had linked this very account before. */
    public record LinkOutput(String status) {}

    /** @param identityId the link to remove, one of the caller's */
    public record UnlinkInput(@NotNull String userId, @NotNull String identityId) {}

    public record UnlinkOutput(String identityId) {}

    // Context keys.
    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String LATEST_RECORD = "latest_login_record";
    static final String CURRENT_PASSWORD = "current_password";
    static final String LOCKED = "locked";
    static final String MATCHES = "password_matches";
    static final String SOURCE = "source";
    static final String OUTCOME = "outcome";
    static final String INPUT = "input";
    static final String BY_SUBJECT = "by_subject";
    static final String OWN = "own_links";
    static final String LINKED = "linked";
    static final String IDENTITY_ID = "identity_id";
    /** Name of the lock that serializes a user's changes of links (the platform's own namespace). */
    static final String LINKS_LOCK = "jabiz.platform.identity-links:";

    static ProcessDefinition<BeginInput, BeginOutput, ProcessContext> begin(LoginAttemptPolicy policy) {
        return ProcessDefinition.define(LINK_BEGIN, 1, BeginInput.class, BeginOutput.class, ProcessContext.class,
            pb -> pb
                .description("Checks the signed-in user's current password before a provider account is linked.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(CURRENT_PASSWORD, input.currentPassword());
                    ctx.put(SOURCE, input.source() == null ? SignInSource.NONE : input.source());
                    return ctx;
                })
                .outputMapper(ctx -> new BeginOutput(Boolean.TRUE.equals(ctx.get(OUTCOME)) ? Check.VERIFIED
                    : Check.INCORRECT))
                // The lock of the password change: both build on the user's latest login record.
                .step("One attempt at a time", HoldLock.<ProcessContext>exclusive(ctx -> AccountPasswordProcess.LOCK
                    + ctx.get(USER_ID)))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .compute("Check the account", (metadata, ctx) -> checkPasswordAccount(ctx))
                .step("Load the latest login record", QueryEntities.of(SecurityEntities.LOGIN_RECORD_DATASET,
                    ctx -> Rbac.latestLoginRecordOf(user(ctx).id()), LATEST_RECORD))
                .compute("Check the lock", (metadata, ctx) -> ctx.put(LOCKED,
                    policy.isLocked(Rbac.state(latest(ctx)), ctx.opTime())))
                .step("Check the current password", CurrentPasswordStep.class, new CurrentPasswordStep.Metadata(
                    CURRENT_PASSWORD, ctx -> user(ctx).get("passwordHash"), MATCHES,
                    ctx -> !ctx.hasViolations() && !Boolean.TRUE.equals(ctx.get(LOCKED))))
                .compute("Record a refusal", (metadata, ctx) -> recordCheck(ctx, policy)));
    }

    static ProcessDefinition<LinkInput, LinkOutput, ProcessContext> link(boolean mailEnabled) {
        return ProcessDefinition.define(LINK_SELF, 1, LinkInput.class, LinkOutput.class, ProcessContext.class, pb -> pb
            .description("Links a provider account the platform has verified to the signed-in user's own account.")
            .permissions(SecurityPermissions.ACCOUNT)
            .entryOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(USER_ID, input.userId());
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new LinkOutput(Boolean.TRUE.equals(ctx.get(LINKED)) ? "LINKED" : "ALREADY_LINKED"))
            .step("One change at a time", HoldLock.<ProcessContext>exclusive(ctx -> LINKS_LOCK + ctx.get(USER_ID)))
            .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
            .compute("Check the account", (metadata, ctx) -> checkLinkAccount(ctx))
            .step("Find the provider account", QueryEntities.of(SecurityEntities.USER_IDENTITY_DATASET,
                ctx -> Rbac.all(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("provider", linkInput(ctx).provider()),
                    new QueryPredicate.Eq("subject", linkInput(ctx).subject()))), "provider"), BY_SUBJECT))
            .step("Load the user's links", QueryEntities.of(SecurityEntities.USER_IDENTITY_DATASET,
                ctx -> Rbac.all(new QueryPredicate.Eq("userId", user(ctx).id()), "provider"), OWN))
            .compute("Link", (metadata, ctx) -> link(ctx))
            .step("Tell the user", SendMail.when(ctx -> mailEnabled && Boolean.TRUE.equals(ctx.get(LINKED))
                    && user(ctx).get("email") != null, AuthMailTemplates.IDENTITY_LINKED_MAIL,
                ctx -> MailRecipient.user(user(ctx).id()),
                Map.of(AuthMailTemplates.ENTRY, ctx -> entryOf(ctx),
                    AuthMailTemplates.PROVIDER, ctx -> labelOf(linkInput(ctx))))));
    }

    /**
     * @param signsIn whether a link of this provider can still sign in: the provider is configured and offered to some
     *                entry. Links to providers that went away do not count as a way left to sign in.
     */
    static ProcessDefinition<UnlinkInput, UnlinkOutput, ProcessContext> unlink(
        java.util.function.Predicate<String> signsIn) {
        return ProcessDefinition.define(UNLINK_SELF, 1, UnlinkInput.class, UnlinkOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes a provider account from the signed-in user's own account.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(IDENTITY_ID, input.identityId());
                    return ctx;
                })
                .outputMapper(ctx -> new UnlinkOutput(String.valueOf(ctx.get(IDENTITY_ID))))
                // Two removals at once must not both see the other link left.
                .step("One change at a time", HoldLock.<ProcessContext>exclusive(ctx -> LINKS_LOCK + ctx.get(USER_ID)))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .compute("Check the caller", (metadata, ctx) -> checkOwn(ctx))
                .step("Load the user's links", QueryEntities.of(SecurityEntities.USER_IDENTITY_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.Eq("userId", user(ctx).id()), "provider"), OWN))
                .compute("Remove the link", (metadata, ctx) -> unlink(ctx, signsIn)));
    }

    // ---- checks and computations -----------------------------------------------------------------------------------

    /** The caller's own account, enabled. Another user's is refused whatever the caller holds. */
    static void checkOwn(ProcessContext ctx) {
        EntityInstance user = user(ctx);
        if (!String.valueOf(user.id()).equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "A user's provider accounts are changed "
                + "here by the user only");
        }
        if (!Rbac.enabled(user)) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "The account is disabled");
        }
    }

    /** {@link #checkOwn}, and a password to check: none (identity provider only) is 422 {@code PASSWORD_NOT_SET}. */
    static void checkPasswordAccount(ProcessContext ctx) {
        checkOwn(ctx);
        if (user(ctx).get("passwordHash") == null) {
            ctx.reject(new Violation("currentPassword", PlatformErrorCodes.PASSWORD_NOT_SET, "The account has no "
                + "password: confirm with the second factor instead", Map.of("field", "currentPassword")));
        }
    }

    /** {@link #checkOwn}, and not pre-claimable (section 15.7): 422 {@code IDENTITY_LINK_UNAVAILABLE}. */
    static void checkLinkAccount(ProcessContext ctx) {
        checkOwn(ctx);
        if (SignUpProcesses.preClaimable(user(ctx))) {
            ctx.reject(new Violation(null, PlatformErrorCodes.IDENTITY_LINK_UNAVAILABLE, "An account whose "
                + "registered address was never verified cannot link a provider account"));
        }
    }

    /** Records a wrong password or an attempt while locked (counting towards the lock); a right one records nothing. */
    static void recordCheck(ProcessContext ctx, LoginAttemptPolicy policy) {
        if (ctx.hasViolations()) {
            return;
        }
        boolean locked = Boolean.TRUE.equals(ctx.get(LOCKED));
        boolean matched = !locked && Boolean.TRUE.equals(ctx.get(MATCHES));
        ctx.put(OUTCOME, matched);
        if (matched) {
            return;
        }
        EntityInstance user = user(ctx);
        LoginOutcome outcome = locked ? LoginOutcome.LOCKED : LoginOutcome.BAD_CREDENTIALS;
        EntityInstance previous = latest(ctx);
        LoginAttemptPolicy.State next = policy.next(Rbac.state(previous), outcome, ctx.opTime());
        ctx.changes().insert(SecurityEntities.LOGIN_RECORD, SponsorSignInProcess.record(user.id(),
            user.get("userName"), outcome, next, ctx.opTime(), ctx.request().requestId(),
            SecurityEntities.FACTOR_PASSWORD, Rbac.mfaStep(previous), ctx.get(SOURCE, SignInSource.class)));
    }

    /** Links the provider account, unless it is someone else's, or the user has another one of the provider. */
    static void link(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance user = user(ctx);
        LinkInput input = linkInput(ctx);
        for (EntityInstance existing : list(ctx, BY_SUBJECT)) {
            if (String.valueOf(existing.<Object>get("userId")).equals(String.valueOf(user.id()))) {
                // Linked before: nothing to do, no mail.
                return;
            }
            ctx.reject(new Violation(null, PlatformErrorCodes.IDENTITY_IN_USE, "The provider account is linked to "
                + "another user"));
            return;
        }
        if (list(ctx, OWN).stream().anyMatch(own -> input.provider().equals(own.get("provider")))) {
            ctx.reject(new Violation(null, PlatformErrorCodes.IDENTITY_PROVIDER_LINKED, "Another account of the "
                + "provider is linked already"));
            return;
        }
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("userId", user.id());
        link.put("provider", input.provider());
        link.put("subject", input.subject());
        link.put("email", input.email());
        ctx.changes().insert(SecurityEntities.USER_IDENTITY, link);
        ctx.put(LINKED, true);
    }

    /**
     * Removes the caller's link; 404 for none of theirs, 422 when it is the last way to sign in: no password and no
     * other link that can still sign in.
     */
    static void unlink(ProcessContext ctx, java.util.function.Predicate<String> signsIn) {
        String wanted = String.valueOf(ctx.get(IDENTITY_ID));
        List<EntityInstance> own = list(ctx, OWN);
        EntityInstance identity = own.stream().filter(link -> String.valueOf(link.id()).equals(wanted)).findFirst()
            .orElseThrow(() -> new EntityNotFoundException("No provider account " + wanted + " of the caller's"));
        boolean anotherWay = own.stream().anyMatch(link -> link != identity
            && signsIn.test(String.valueOf(link.<Object>get("provider"))));
        if (user(ctx).get("passwordHash") == null && !anotherWay) {
            ctx.reject(new Violation(null, PlatformErrorCodes.LAST_SIGN_IN_METHOD, "The account would have no way to "
                + "sign in left"));
            return;
        }
        ctx.changes().delete(SecurityEntities.USER_IDENTITY, identity.id(), identity.version());
    }

    private static EntityInstance user(ProcessContext ctx) {
        return ctx.get(USER, EntityInstance.class);
    }

    private static LinkInput linkInput(ProcessContext ctx) {
        return ctx.get(INPUT, LinkInput.class);
    }

    private static String entryOf(ProcessContext ctx) {
        String entry = ctx.request().entry();
        return entry == null || entry.isBlank() ? com.jabiz.context.RequestContext.DEFAULT_ENTRY : entry;
    }

    private static String labelOf(LinkInput input) {
        return input.providerLabel() == null || input.providerLabel().isBlank() ? input.provider()
            : input.providerLabel();
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object rows = ctx.get(key);
        return rows instanceof List<?> found ? (List<EntityInstance>) found : List.of();
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance latest(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(LATEST_RECORD);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    @Bean
    ProcessDefinition<BeginInput, BeginOutput, ProcessContext> secIdentityLinkBeginProcess(LoginAttemptPolicy policy) {
        return begin(policy);
    }

    @Bean
    ProcessDefinition<LinkInput, LinkOutput, ProcessContext> secIdentityLinkSelfProcess(
        @Value("${jabiz.mail.enabled:false}") boolean mailEnabled) {
        return link(mailEnabled);
    }

    @Bean
    ProcessDefinition<UnlinkInput, UnlinkOutput, ProcessContext> secIdentityUnlinkSelfProcess(
        OidcProviders providers, SignInEntries entries) {
        return unlink(id -> providers.find(id)
            .map(provider -> entries.all().stream().anyMatch(entry -> provider.offers(entry.name())))
            .orElse(false));
    }
}

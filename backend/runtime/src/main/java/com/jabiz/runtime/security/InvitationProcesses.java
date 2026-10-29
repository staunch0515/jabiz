package com.jabiz.runtime.security;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.mail.MailTemplates;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.sponsor.SignInSource;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.security.protect.AddressVerifiable;
import com.jabiz.runtime.security.protect.BootstrapAdminRetiredException;
import com.jabiz.runtime.security.protect.ProtectedChangeEntities;
import com.jabiz.runtime.security.protect.ProtectedChangeProcesses;
import com.jabiz.runtime.security.protect.ProtectedGrants;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.MfaRequirement;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.UUID;

/**
 * Invitations of an administrator's new users (docs/design/10-security.md section 15.14; decision D63). An account an
 * administrator creates has no password of its own and an address nobody proved, so its user could neither sign in nor
 * get a password reset link (only trusted addresses get one). An invitation mails the user a one-time link that sets
 * their password and proves the address at once, as a password reset does: the password is the user's own
 * ({@code CredentialChain}), so protected grants can be given to it later.
 * <ul>
 *   <li>{@value #INVITE} (and {@code SEC_USER_CREATE} with {@code invite}) sends the link to an enabled user whose
 *       address is not verified yet and is still the one entered when the account was created; an address anyone
 *       changed since is refused ({@code INVITATION_ADDRESS_CHANGED}), since changing the address is how an account is
 *       taken over. A newer invitation supersedes the earlier link.</li>
 *   <li>{@value #ACCEPT} sets the password with the link, proves the address, ends every session and records
 *       {@code PASSWORD_RESET}, as {@code SPONSOR_PASSWORD_RESET_CONFIRM} does. The link is void once the address is
 *       verified, so accepting one invitation voids the others.</li>
 * </ul>
 * An invitation needs no approval. Once the setup window has closed, a holder of a protected permission is not invited
 * ({@code PROTECTED_CHANGE_REQUIRES_APPROVAL}), as setting their password needs another administrator's approval: whoever
 * entered the address may read that mailbox. The protected grant is withdrawn, the user invited, and the grant asked for
 * again, when the approvers see the address proven by mail (decision D48).
 */
@Configuration
public class InvitationProcesses {

    public static final String INVITE = "SEC_USER_INVITE";
    public static final String ACCEPT = "SPONSOR_INVITATION_ACCEPT";

    /** The entry whose pages an invitation's link leads to when none is named. */
    public static final String DEFAULT_ENTRY = "admin";

    static final String USER_ID = "userId";
    static final String USER = "user";
    static final String ENTRY = "entry";

    /**
     * @param entry the sign-in entry whose front end the link leads to; optional ({@value #DEFAULT_ENTRY})
     */
    public record InviteInput(@NotNull UUID userId, String entry) {}

    /** @param status {@code SENT}: the mail is recorded and goes out after the commit */
    public record InviteOutput(String userId, String status) {}

    /** @param source where the request came from, for the login record */
    public record AcceptInput(@Sensitive String token, @Sensitive String password, SignInSource source)
        implements SignUpProcesses.PasswordLinkInput {
        @Override
        public String toString() {
            return "AcceptInput[***]";
        }
    }

    static ProcessDefinition<InviteInput, InviteOutput, ProcessContext> invite(AuthMailTemplates.Invitation invitation,
        MailTemplates mail, SignInEntries entries) {
        return ProcessDefinition.define(INVITE, 1, InviteInput.class, InviteOutput.class, ProcessContext.class,
            pb -> pb
                .description("Mails a user a link to set their password, to the address entered when the account was"
                    + " created.")
                .permissions(SecurityPermissions.USER_CREATE)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .actsOn(SecurityEntities.USER, USER_ID)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(ENTRY, entryOrDefault(input.entry()));
                    return ctx;
                })
                .outputMapper(ctx -> new InviteOutput(String.valueOf(ctx.get(USER_ID)), "SENT"))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                // As setting the user's password (SEC_USER_SET_PASSWORD): protected for a holder of a protected
                // permission once the setup window has closed.
                .step("Assess the invitation", ProtectedChangeProcesses.assess(ctx -> new ProtectedGrants.Draft(
                    ProtectedChangeEntities.PASSWORD, (UUID) ctx.get(USER_ID), null, null)))
                .compute("Check the account", (metadata, ctx) -> {
                    checkSending(ctx, mail, entries, String.valueOf(ctx.get(ENTRY)));
                    checkAccount(ctx, ctx.get(USER, EntityInstance.class));
                    checkProtected(ctx);
                })
                .step("Check the address", AddressVerifiable.unchanged(
                    ctx -> ctx.hasViolations() ? null : ctx.get(USER, EntityInstance.class).id()))
                .step("Send the invitation", SendMail.when(ctx -> !ctx.hasViolations(), invitation.mail(),
                    ctx -> MailRecipient.user(ctx.get(USER, EntityInstance.class).id()),
                    Map.of(AuthMailTemplates.ENTRY, ctx -> ctx.get(ENTRY)))));
    }

    static ProcessDefinition<AcceptInput, SignUpProcesses.Done, AccountContext> accept(LoginAttemptPolicy policy) {
        return SignUpProcesses.passwordByLink(ACCEPT, AcceptInput.class,
            "Sets the password of an invited user with the invitation's link, proving the address.",
            AuthMailTemplates.INVITE, false, true, policy);
    }

    /** The named entry, or {@value #DEFAULT_ENTRY}. */
    static String entryOrDefault(String entry) {
        return entry == null || entry.isBlank() ? DEFAULT_ENTRY : entry.strip();
    }

    /** Whether an invitation can be sent at all: a known entry, and mail on (else it would silently never arrive). */
    static void checkSending(ProcessContext ctx, MailTemplates mail, SignInEntries entries, String entry) {
        if (entries.find(entry).isEmpty()) {
            ctx.reject(new Violation(ENTRY, PlatformErrorCodes.INVALID_VALUE, "No sign-in entry " + entry,
                Map.of("field", ENTRY)));
        }
        if (!mail.enabled()) {
            ctx.reject(new Violation(null, PlatformErrorCodes.MAIL_DISABLED, "E-mail is not enabled (jabiz.mail"
                + ".enabled)"));
        }
    }

    /**
     * Refuses inviting a holder of a protected permission once the setup window has closed (a first administrator who
     * is retired: 403), as {@code SEC_USER_SET_PASSWORD} would ask for an approval.
     */
    static void checkProtected(ProcessContext ctx) {
        ProtectedGrants.Assessment assessment = ctx.contains(ProtectedChangeProcesses.ASSESSMENT)
            ? ctx.get(ProtectedChangeProcesses.ASSESSMENT, ProtectedGrants.Assessment.class) : null;
        if (assessment == null || !assessment.protectedChange()) {
            return;
        }
        if (assessment.retiredBootstrap()) {
            throw new BootstrapAdminRetiredException("Other administrators exist: the first administrator may no longer"
                + " invite a holder of protected permissions (" + assessment.gained() + ")");
        }
        if (assessment.needsApproval()) {
            ctx.reject(new Violation(USER_ID, PlatformErrorCodes.PROTECTED_CHANGE_REQUIRES_APPROVAL, "The user holds"
                + " protected permissions " + assessment.gained() + ": withdraw them, invite the user, and ask for them"
                + " again"));
        }
    }

    /** Whether the user may be invited: enabled, with an address that is not verified yet. */
    static void checkAccount(ProcessContext ctx, EntityInstance user) {
        if (!Rbac.enabled(user)) {
            ctx.reject(new Violation(USER_ID, PlatformErrorCodes.INVITATION_USER_DISABLED, "The user is disabled"));
        } else if (user.get("email") == null || String.valueOf(user.<Object>get("email")).isBlank()) {
            ctx.reject(new Violation(USER_ID, PlatformErrorCodes.MAIL_NO_ADDRESS, "The user has no e-mail address"));
        } else if (SecurityEntities.emailVerified(user)) {
            ctx.reject(new Violation(USER_ID, PlatformErrorCodes.INVITATION_NOT_NEEDED,
                "The address is verified: the user resets the password themselves"));
        }
    }

    // ---- beans ----------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<InviteInput, InviteOutput, ProcessContext> secUserInviteProcess(
        AuthMailTemplates.Invitation invitation, MailTemplates mail, SignInEntries entries) {
        return invite(invitation, mail, entries);
    }

    @Bean
    ProcessDefinition<AcceptInput, SignUpProcesses.Done, AccountContext> sponsorInvitationAcceptProcess(
        LoginAttemptPolicy policy) {
        return accept(policy);
    }
}

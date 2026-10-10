package com.jabiz.runtime.security;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The platform's mail of self-registration, address verification and password reset (docs/design/10-security.md
 * section 15; decision D36, sent as decision D35 says). All three are transactional (no unsubscribing) and carry the
 * sign-in entry as their only parameter; their links lead to the administration's pages
 * {@code {baseUrl}/auth/<page>?entry=<entry>&token=<token>}, which finish at the entry's {@code app-path}. Texts are
 * the messages {@code mail.jabiz.auth.<name>.subject|body}.
 * <ul>
 *   <li>{@value #VERIFY_EMAIL}: token {@value #VERIFY}, valid {@code jabiz.security.sign-up.verify-ttl} (24 hours);</li>
 *   <li>{@value #ACCOUNT_EXISTS}: token {@value #JOIN}, valid {@code jabiz.security.sign-up.join-ttl} (24 hours),
 *       sent instead of a verification when the address registered with belongs to an account already;</li>
 *   <li>{@value #PASSWORD_RESET}: token {@value #RESET}, valid {@code jabiz.security.password-reset.ttl} (1 hour).</li>
 * </ul>
 */
@Configuration
public class AuthMailTemplates {

    public static final String VERIFY_EMAIL = "jabiz.auth.verify-email";
    public static final String ACCOUNT_EXISTS = "jabiz.auth.account-exists";
    public static final String PASSWORD_RESET = "jabiz.auth.password-reset";

    /** Purposes of the tokens, which are also their placeholders. */
    public static final String VERIFY = "verify";
    public static final String JOIN = "join";
    public static final String RESET = "reset";

    /** The only parameter: the sign-in entry whose pages the link finishes at. */
    public static final String ENTRY = "entry";

    /** The three templates, as the processes that send them use them. */
    public record Templates(MailTemplate verifyEmail, MailTemplate accountExists, MailTemplate passwordReset) {}

    static MailTemplate verifyEmail(Duration validity) {
        return MailTemplate.define(VERIFY_EMAIL, t -> t.category(MailCategory.TRANSACTIONAL).param(ENTRY)
            .token(VERIFY, validity));
    }

    static MailTemplate accountExists(Duration validity) {
        return MailTemplate.define(ACCOUNT_EXISTS, t -> t.category(MailCategory.TRANSACTIONAL).param(ENTRY)
            .token(JOIN, validity));
    }

    static MailTemplate passwordReset(Duration validity) {
        return MailTemplate.define(PASSWORD_RESET, t -> t.category(MailCategory.TRANSACTIONAL).param(ENTRY)
            .token(RESET, validity));
    }

    @Bean
    Templates authMailTemplateSet(@Value("${jabiz.security.sign-up.verify-ttl:PT24H}") Duration verify,
        @Value("${jabiz.security.sign-up.join-ttl:PT24H}") Duration join,
        @Value("${jabiz.security.password-reset.ttl:PT1H}") Duration reset) {
        return new Templates(verifyEmail(verify), accountExists(join), passwordReset(reset));
    }

    @Bean
    MailTemplate authVerifyEmailMail(Templates templates) {
        return templates.verifyEmail();
    }

    @Bean
    MailTemplate authAccountExistsMail(Templates templates) {
        return templates.accountExists();
    }

    @Bean
    MailTemplate authPasswordResetMail(Templates templates) {
        return templates.passwordReset();
    }
}

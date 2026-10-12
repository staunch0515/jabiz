package com.jabiz.runtime.mail;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup check of e-mail and mail templates (docs/design/18-numbering-approvals-tasks.md sections 5.4, 5.6). */
class MailChecksTest {

    private static final MailTemplate OK = MailTemplate.define("t.ok", t -> t.category(MailCategory.TRANSACTIONAL)
        .param("userName").token("verify", Duration.ofHours(1)));
    private static final MailTemplate NEWS = MailTemplate.define("t.news", t -> t.category(MailCategory.NOTIFICATION)
        .param("name"));
    private static final MailTemplate PARTIAL = MailTemplate.define("t.partial",
        t -> t.category(MailCategory.TRANSACTIONAL));

    private static MessageCatalog catalog(Locale... locales) {
        return new MessageCatalog(List.of("mailcheck/messages"), List.of(locales), locales[0],
            MailChecksTest.class.getClassLoader());
    }

    private static MailChecks checks(boolean enabled, String from, String baseUrl, JavaMailSender sender,
        MessageCatalog messages, MailTemplate... templates) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (sender != null) {
            beans.addBean("mail", sender);
        }
        for (int i = 0; i < templates.length; i++) {
            beans.addBean("template" + i, templates[i]);
        }
        return new MailChecks(enabled, from, baseUrl, beans.getBeanProvider(JavaMailSender.class),
            beans.getBeanProvider(MailTemplate.class), messages, name -> name.toLowerCase().contains("password"));
    }

    @Test
    void mailThatIsOnNeedsAServerASenderAndTheAddressOfTheApplication() {
        MessageCatalog english = catalog(Locale.ENGLISH);
        assertThat(checks(false, "", "", null, english).check()).isEmpty();
        assertThat(checks(true, " ", "", null, english).check()).extracting(CheckProblem::location)
            .containsExactly("jabiz.mail.from", "spring.mail.host");
        assertThat(checks(true, "jabiz@example.com", "", new JavaMailSenderImpl(), english).check()).isEmpty();
        assertThat(checks(true, "jabiz@example.com", "", new JavaMailSenderImpl(), english, OK).check())
            .extracting(CheckProblem::location).containsExactly("jabiz.mail.base-url");
        assertThat(checks(true, "jabiz@example.com", "https://app.example.com", new JavaMailSenderImpl(), english,
            OK, NEWS).check()).isEmpty();
    }

    @Test
    void reportsMissingMessagesOfEveryLanguage() {
        List<CheckProblem> problems = checks(false, "", "", null, catalog(Locale.ENGLISH, Locale.JAPANESE), PARTIAL)
            .check();

        assertThat(problems).extracting(CheckProblem::category).containsOnly(MailChecks.CATEGORY);
        assertThat(problems).extracting(CheckProblem::message).containsExactly(
            "has no message mail.t.partial.subject [ja]", "has no message mail.t.partial.body [ja]");
    }

    @Test
    void reportsPlaceholdersThatDisagreeWithTheDeclaration() {
        List<CheckProblem> problems = checks(false, "", "", null, catalog(Locale.ENGLISH, Locale.CHINESE), OK, NEWS)
            .check();

        assertThat(problems).extracting(CheckProblem::location, CheckProblem::message).containsExactly(
            org.assertj.core.groups.Tuple.tuple("mail template t.news",
                "[zh] uses {stray}, which the template does not declare"),
            org.assertj.core.groups.Tuple.tuple("mail template t.news", "[zh] does not use the declared {name}"),
            org.assertj.core.groups.Tuple.tuple("mail template t.news",
                "[zh] is a notification but its body has no {unsubscribeUrl}"));
    }

    @Test
    void refusesParametersNamedLikeSecrets() {
        MailTemplate secret = MailTemplate.define("t.ok", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("userName", "newPassword").token("verify", Duration.ofHours(1)));
        assertThat(checks(false, "", "", null, catalog(Locale.ENGLISH), secret).check())
            .extracting(CheckProblem::message).contains("parameter newPassword is named like a secret or a masked "
                + "field; a mail carries no secret but its one-time tokens");
    }

    @Test
    void applicationsStayOutOfThePlatformsNamespace() {
        MailTemplate named = MailTemplate.define("jabiz.shop.news", t -> t.category(MailCategory.TRANSACTIONAL));
        MailTemplate drawing = MailTemplate.define("t.reset", t -> t.category(MailCategory.TRANSACTIONAL)
            .token("jabiz.reset", Duration.ofHours(1)));
        assertThat(checks(false, "", "", null, catalog(Locale.ENGLISH), named, drawing).check())
            .extracting(CheckProblem::message)
            .contains("is named in the platform's namespace jabiz., which applications do not use",
                "draws tokens of purpose jabiz.reset, in the platform's namespace jabiz., which applications do not "
                    + "use");
    }

    @Test
    void theEntriesNamedInLinksMustExist() {
        MailTemplate entry = MailTemplate.define("t.entry", t -> t.category(MailCategory.TRANSACTIONAL).param("entry"));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("template", entry);
        List<CheckProblem> problems = new MailChecks(true, "jabiz@example.com", "", beans.getBeanProvider(
            JavaMailSender.class), beans.getBeanProvider(MailTemplate.class), catalog(Locale.ENGLISH), name -> false,
            name -> name.equals("shop")).check();
        assertThat(problems).extracting(CheckProblem::location, CheckProblem::message).containsExactly(
            org.assertj.core.groups.Tuple.tuple("mail template t.entry", "[en] uses {entryUrl.gone-away}, but there "
                + "is no usable sign-in entry gone-away (jabiz.security.entries.gone-away)"),
            org.assertj.core.groups.Tuple.tuple("spring.mail.host", "mail is enabled but no mail server is configured"),
            // Links to an entry's frontend are links to the application.
            org.assertj.core.groups.Tuple.tuple("jabiz.mail.base-url", "mail is enabled and templates link to the "
                + "application, but its address is not configured"));
    }

    @Test
    void reportsATemplateDeclaredTwice() {
        assertThat(checks(false, "", "", null, catalog(Locale.ENGLISH), OK, OK).check())
            .extracting(CheckProblem::message).containsExactly("is declared more than once");
    }
}

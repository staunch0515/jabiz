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
    void warnsOfANotificationWithoutItsDisplayNameInALanguage() {
        MailTemplate unnamed = MailTemplate.define("t.partial", t -> t.category(MailCategory.NOTIFICATION));
        // t.news is named in both languages; t.partial (here a notification) in neither, and its texts are English
        // only and lack the unsubscribe link: the errors stay as they were.
        List<CheckProblem> problems = checks(false, "", "", null, catalog(Locale.ENGLISH, Locale.CHINESE), NEWS,
            unnamed).check();
        assertThat(problems).filteredOn(problem -> problem.severity() == CheckProblem.Severity.WARNING)
            .extracting(CheckProblem::location, CheckProblem::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple("mail template t.partial", "has no message mail.t.partial.name "
                    + "[en]: its users see the name of the template among their mail preferences"),
                org.assertj.core.groups.Tuple.tuple("mail template t.partial", "has no message mail.t.partial.name "
                    + "[zh]: its users see the name of the template among their mail preferences"));
        // Transactional mail is not among the preferences and needs no name.
        assertThat(checks(false, "", "", null, catalog(Locale.ENGLISH), OK).check()).isEmpty();
    }

    @Test
    void theEntriesATemplateIsDeclaredForMustExist() {
        MailTemplate shop = MailTemplate.define("t.news", t -> t.category(MailCategory.NOTIFICATION).param("name")
            .entries("shop", "gone-away"));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("template", shop);
        List<CheckProblem> problems = new MailChecks(false, "", "", beans.getBeanProvider(JavaMailSender.class),
            beans.getBeanProvider(MailTemplate.class), catalog(Locale.ENGLISH), name -> false,
            name -> name.equals("shop")).check();
        assertThat(problems).extracting(CheckProblem::severity, CheckProblem::location, CheckProblem::message)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "mail template t.news",
                "is declared for the sign-in entry gone-away, but there is no usable sign-in entry gone-away "
                    + "(jabiz.security.entries.gone-away)"));
    }

    @Test
    void warnsOfTransactionalMailDeclaringEntries() {
        MailTemplate declared = MailTemplate.define("t.ok", t -> t.category(MailCategory.TRANSACTIONAL)
            .param("userName").token("verify", Duration.ofHours(1)).entries("shop"));
        assertThat(checks(false, "", "", null, catalog(Locale.ENGLISH), declared).check())
            .extracting(CheckProblem::severity, CheckProblem::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.WARNING, "declares sign-in entries [shop], but "
                    + "it is transactional mail, which is in nobody's preferences: the entries change nothing"));
    }

    /** The unsubscribe page of an entry (decision D52 item 3) is a relative path of a usable entry, under an SPA. */
    @Test
    void theUnsubscribePagesOfEntriesAreRelativePathsOfUsableEntriesUnderAnSpa() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        java.util.Map<String, String> paths = new java.util.LinkedHashMap<>();
        paths.put("portal", "mail/unsubscribe");
        paths.put("app", "/mail/unsubscribe");
        paths.put("shop", "mail/unsubscribe");
        paths.put("gone-away", "mail/unsubscribe");
        paths.put("admin", "../admin/mail");
        paths.put("blank", " ");
        java.util.Map<String, String> roots = java.util.Map.of("portal", "/portal/", "app", "/app", "shop", "/shop/",
            "admin", "/", "blank", "/");
        List<CheckProblem> problems = new MailChecks(false, "", "", beans.getBeanProvider(JavaMailSender.class),
            beans.getBeanProvider(MailTemplate.class), catalog(Locale.ENGLISH), name -> false, name -> true, paths,
            name -> java.util.Optional.ofNullable(roots.get(name)),
            path -> path.startsWith("/portal/") || path.startsWith("/app/")).check();
        assertThat(problems).extracting(CheckProblem::severity, CheckProblem::location, CheckProblem::message)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "jabiz.mail.unsubscribe-paths.admin",
                    "'../admin/mail' is not a path relative to the entry's app-path (segments of letters, digits and "
                        + ". _ ~ -)"),
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "jabiz.mail.unsubscribe-paths.blank",
                    "' ' is not a path relative to the entry's app-path (segments of letters, digits and . _ ~ -)"),
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR,
                    "jabiz.mail.unsubscribe-paths.gone-away", "there is no usable sign-in entry gone-away "
                        + "(jabiz.security.entries.gone-away)"),
                org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "jabiz.mail.unsubscribe-paths.shop",
                    "'/shop/mail/unsubscribe' is under none of the SPAs served (jabiz.web.spa): the unsubscribe links "
                        + "of entry shop would lead nowhere"));
    }

    @Test
    void relativePathsAreSegmentsWithoutDotSegmentsOrSchemes() {
        assertThat(MailTemplates.validRelativePath("mail/unsubscribe")).isTrue();
        assertThat(MailTemplates.validRelativePath("/mail/unsubscribe/")).isTrue();
        assertThat(MailTemplates.validRelativePath("a.b/c~d_e-f")).isTrue();
        for (String bad : List.of("", " ", "/", "..", "a/../b", "./a", "//evil.example.com/x",
            "https://evil.example.com", "a?b=c", "a#b", "a b", "a//b", "a\\b")) {
            assertThat(MailTemplates.validRelativePath(bad)).as(bad).isFalse();
        }
    }

    @Test
    void reportsATemplateDeclaredTwice() {
        assertThat(checks(false, "", "", null, catalog(Locale.ENGLISH), OK, OK).check())
            .extracting(CheckProblem::message).containsExactly("is declared more than once");
    }

    /** Decision D55: the sign-in token is the one platform token an application template may carry, as declared. */
    @Test
    void anApplicationMayCarryASignInLinkWithinTheAllowedValidity() {
        MailTemplate invite = MailTemplate.define("t.invite", t -> t.category(MailCategory.NOTIFICATION)
            .signInLink(Duration.ofDays(7)));
        MailTemplate tooLong = MailTemplate.define("t.long", t -> t.category(MailCategory.NOTIFICATION)
            .signInLink(Duration.ofDays(31)));
        List<String> messages = checks(false, "", "", null, catalog(Locale.ENGLISH), invite, tooLong).check().stream()
            .map(CheckProblem::message).toList();
        assertThat(messages).noneMatch(message -> message.contains("draws tokens of purpose jabiz.signIn"));
        assertThat(messages).contains("its sign-in link is valid for PT744H, longer than "
            + "jabiz.security.email-link.max-ttl (PT720H)");
        assertThat(messages).noneMatch(message -> message.contains("valid for PT168H"));
    }
}

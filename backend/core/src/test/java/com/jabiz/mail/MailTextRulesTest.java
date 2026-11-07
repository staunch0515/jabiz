package com.jabiz.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules of mail texts (decision D72 item 2), each both ways: what passes and what is refused, with every problem
 * reported at once.
 */
class MailTextRulesTest {

    private static final MailTemplate RESET = MailTemplate.define("shop.reset", t -> t
        .category(MailCategory.TRANSACTIONAL).param("entry", "userName").token("reset", Duration.ofHours(1)));
    private static final MailTemplate NEWS = MailTemplate.define("shop.news", t -> t
        .category(MailCategory.NOTIFICATION).param("item"));
    private static final MailTemplate INVITE = MailTemplate.define("shop.invite", t -> t
        .category(MailCategory.NOTIFICATION).param("item").signInLink(Duration.ofDays(1)));
    private static final MailTextRules.Limits LIMITS = new MailTextRules.Limits(40, 400,
        Set.of("app.example.com"), Set.of("Help.Example.org"));

    private static final String RESET_BODY = "Hello {userName}, [reset]({entryUrl}auth/reset?entry={entry}&t={reset})";
    private static final String NEWS_BODY = "News of {item}. [Unsubscribe]({unsubscribeUrl})";

    private static List<String> codes(MailTemplate template, String subject, String body) {
        return MailTextRules.check(template, subject, body, LIMITS).stream().map(Violation::ruleCode).toList();
    }

    @Test
    void acceptsTextsThatKeepToTheRules() {
        assertThat(codes(RESET, "Reset, {userName}", RESET_BODY)).isEmpty();
        assertThat(codes(NEWS, "News: {item}", NEWS_BODY + " See [help](https://help.example.org/news) or "
            + "[us](mailto:help@example.org), [home](/), [shop]({entryUrl.portal}offers) and "
            + "[the site](https://APP.example.com:443/x).")).isEmpty();
        assertThat(codes(INVITE, "Join {item}", "[Open {item}]({entryUrl}auth/email-link?entry={entry}"
            + "&token={jabiz.signIn}&to={to}) [Unsubscribe]({unsubscribeUrl})")).isEmpty();
        // A reference link to the application carries the token as well.
        assertThat(codes(RESET, "Reset {userName}", "Hi {userName} [reset][r]\n\n[r]: {baseUrl}/r?e={entry}&t={reset}"))
            .isEmpty();
    }

    @Test
    void placeholdersMustBeDeclaredAndKept() {
        List<Violation> problems = MailTextRules.check(RESET, "Reset {stray}", "[reset]({baseUrl}?t={reset})",
            LIMITS);
        assertThat(problems).extracting(Violation::ruleCode).containsExactly(
            PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER, PlatformErrorCodes.MAIL_TEXT_REQUIRED_PLACEHOLDER,
            PlatformErrorCodes.MAIL_TEXT_REQUIRED_PLACEHOLDER);
        assertThat(problems.getFirst().params()).containsEntry("placeholder", "stray");
        assertThat(problems.getFirst().field()).isEqualTo(MailTextRules.SUBJECT);
        assertThat(problems).extracting(problem -> problem.params().get("placeholder"))
            .containsExactly("stray", "entry", "userName");
        // The token itself is required: dropping the link drops the mail's purpose.
        assertThat(codes(RESET, "Reset {userName}", "Hello {entry}"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_REQUIRED_PLACEHOLDER);
        // A notification keeps its unsubscribe link; {entryUrl} needs the parameter entry.
        assertThat(codes(NEWS, "News {item}", "News of {item}"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_REQUIRED_PLACEHOLDER);
        assertThat(MailTextRules.check(NEWS, "News {item}", NEWS_BODY + " [x]({entryUrl}a)", LIMITS))
            .singleElement().satisfies(problem -> {
                assertThat(problem.ruleCode()).isEqualTo(PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER);
                assertThat(problem.message()).contains("needs the parameter entry");
            });
    }

    @Test
    void secretsStandOnlyInLinksToTheApplication() {
        // In the text, where it could be copied.
        assertThat(codes(RESET, "Reset {userName}", RESET_BODY + " or paste {reset}"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        // In a link elsewhere: the token would leave for another host.
        assertThat(codes(RESET, "Reset {userName}", "Hi {userName} {entry} [reset](https://evil.example/?t={reset})"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT, PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        // In a link to the application, but not by its placeholder.
        assertThat(codes(RESET, "Reset {userName}", "Hi {userName} {entry} [r](https://app.example.com/r?t={reset})"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        assertThat(codes(RESET, "Reset {userName}", "Hi {userName} {entry} [r](/r?t={reset})"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        // In the subject or a link title.
        assertThat(codes(RESET, "Your code {reset}", RESET_BODY))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        assertThat(codes(RESET, "Reset {userName}", "Hi {userName} [r]({entryUrl}r?e={entry}&t={reset} \"{reset}\")"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        // The sign-in link and the unsubscribe link alike.
        assertThat(codes(INVITE, "Join {item}", "[Open {item}]({entryUrl}auth/email-link?entry={entry}"
            + "&token={jabiz.signIn}&to={to}) code {jabiz.signIn} [Unsubscribe]({unsubscribeUrl})"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " copy: {unsubscribeUrl}"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        assertThat(codes(NEWS, "Stop: {unsubscribeUrl} {item}", NEWS_BODY))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
    }

    @Test
    void linksAreWebOrMailAndLeadToAllowedHosts() {
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](javascript:alert(1))"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_NOT_ALLOWED);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](data:text/html,hi)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_NOT_ALLOWED);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](https://tracker.example.net/c)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        // Protocol-relative, credentials before the host, a placeholder choosing the host: all refused.
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](//tracker.example.net/c)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](https://app.example.com@tracker.example.net/)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](https://{item}.example.com/)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        // An address written out in the text, and an autolink, follow the same rule.
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " Visit https://tracker.example.net/x now"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " <https://tracker.example.net/x>"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " Visit https://help.example.org/x now")).isEmpty();
    }

    @Test
    void noPlaceholderOrSchemeTrickLeadsASecretOrALinkOffTheSite() {
        // The base address has no trailing slash: what follows {baseUrl} must not extend or replace its host.
        for (String target : new String[] {"{baseUrl}@evil.example/r?t={reset}", "{baseUrl}.evil.example/r?t={reset}",
            "{baseUrl}:x@evil.example/r?t={reset}", "<{baseUrl}@evil.example/r?t={reset}>", "{baseUrl}//evil.example?t={reset}"}) {
            assertThat(codes(RESET, "Reset", "Hello {userName}, [reset](" + target + ") {entry}")).as(target)
                .contains(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        }
        assertThat(codes(RESET, "Reset", "Hello {userName} {entry}\n\n[reset][r]\n\n[r]: {baseUrl}@evil.example/?t={reset}\n"))
            .contains(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT);
        // A path, query or fragment after it is the site.
        assertThat(codes(RESET, "Reset", "Hello {userName}, [reset]({baseUrl}/auth/reset?entry={entry}&t={reset})"))
            .isEmpty();
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x]({baseUrl}@evil.example/?u={unsubscribeUrl})"))
            .contains(PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT, PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x]({baseUrl}@evil.example/phish)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        // Web links without // (or with backslashes) lead to a host as browsers read them.
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](http:evil.example/login)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](https:/evil.example)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](https:\\\\evil.example/login)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_NOT_ALLOWED);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " [x](https:/\\evil.example)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_NOT_ALLOWED);
        // Addresses inside a word, and www. hosts, are linked by mail clients: checked too.
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " Loginhttps://evil.example/login"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " See www.evil.example today"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        // www. names a host of its own: not the allowed help.example.org.
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " See www.help.example.org today"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
    }

    @Test
    void noImagesAndNoHtml() {
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " ![pixel](https://app.example.com/p.gif)"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_IMAGE);
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " <b>bold</b>"))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_HTML);
        assertThat(codes(NEWS, "News {item}", "<div>\n" + NEWS_BODY + "\n</div>"))
            .contains(PlatformErrorCodes.MAIL_TEXT_HTML);
        // Text that only looks like a comparison is not HTML.
        assertThat(codes(NEWS, "News {item}", NEWS_BODY + " 1 < 2")).isEmpty();
    }

    @Test
    void lengthsLinesAndEmptiness() {
        assertThat(codes(NEWS, "News {item}\nBcc: x@example.com", NEWS_BODY))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_SUBJECT_LINE);
        assertThat(codes(NEWS, "News {item}\r", NEWS_BODY)).containsExactly(PlatformErrorCodes.MAIL_TEXT_SUBJECT_LINE);
        List<Violation> tooLong = MailTextRules.check(NEWS, "N {item}" + "x".repeat(40), NEWS_BODY + "y".repeat(400),
            LIMITS);
        assertThat(tooLong).extracting(Violation::ruleCode).containsExactly(PlatformErrorCodes.MAIL_TEXT_TOO_LONG,
            PlatformErrorCodes.MAIL_TEXT_TOO_LONG);
        assertThat(tooLong).extracting(Violation::field).containsExactly("subject", "body");
        assertThat(tooLong.getFirst().params()).containsEntry("limit", 40);
        // Characters, not UTF-16 units: forty emoji are forty characters.
        assertThat(codes(NEWS, "{item}" + "😀".repeat(34), NEWS_BODY)).isEmpty();
        assertThat(codes(NEWS, " ", "")).contains(PlatformErrorCodes.MAIL_TEXT_EMPTY)
            .filteredOn(PlatformErrorCodes.MAIL_TEXT_EMPTY::equals).hasSize(2);
        assertThat(codes(NEWS, null, null)).filteredOn(PlatformErrorCodes.MAIL_TEXT_EMPTY::equals).hasSize(2);
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThat(codes(RESET, "Reset {reset}\n{nope}", "<i>x</i> ![p](https://x.example/p.gif) "
            + "[a](ftp://x.example/)")).contains(PlatformErrorCodes.MAIL_TEXT_SUBJECT_LINE,
            PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER, PlatformErrorCodes.MAIL_TEXT_REQUIRED_PLACEHOLDER,
            PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT, PlatformErrorCodes.MAIL_TEXT_HTML,
            PlatformErrorCodes.MAIL_TEXT_IMAGE, PlatformErrorCodes.MAIL_TEXT_LINK_NOT_ALLOWED);
        assertThat(MailTextRules.describe(MailTextRules.check(NEWS, "News {item}", "x {item}", LIMITS)))
            .singleElement().asString().startsWith("body: ");
    }

    @Test
    void limitsAndHashes() {
        assertThatThrownBy(() -> new MailTextRules.Limits(0, 1, Set.of(), Set.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(LIMITS.allows("HELP.example.org")).isTrue();
        assertThat(LIMITS.allows("app.example.com")).isTrue();
        assertThat(LIMITS.allows("example.com")).isFalse();
        assertThat(MailTextRules.Limits.DEFAULT.maxSubjectLength()).isEqualTo(MailTextRules.DEFAULT_MAX_SUBJECT);
        assertThat(MailTextRules.hash("a", "b")).hasSize(64).isEqualTo(MailTextRules.hash("a", "b"))
            .isNotEqualTo(MailTextRules.hash("ab", "")).isNotEqualTo(MailTextRules.hash("a", "c"));
        assertThat(MailTextRules.hash(null, null)).isEqualTo(MailTextRules.hash("", ""));
    }
}

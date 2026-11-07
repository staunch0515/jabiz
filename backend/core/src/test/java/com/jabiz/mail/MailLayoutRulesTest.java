package com.jabiz.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of the mail layout (decision D72): each both ways, every problem at once. */
class MailLayoutRulesTest {

    private static final MailLayoutRules.Limits LIMITS = new MailLayoutRules.Limits(300,
        new MailTextRules.Limits(300, 20_000, Set.of("app.example.com"), Set.of("help.example.org")));

    private static List<String> codes(MailLayout layout) {
        return MailLayoutRules.check(layout, LIMITS).stream().map(Violation::ruleCode).toList();
    }

    private static MailLayout footer(String footer) {
        return new MailLayout("Shop", "", "", footer);
    }

    @Test
    void acceptsALayoutOfTheApplication() {
        assertThat(codes(MailLayout.EMPTY)).isEmpty();
        assertThat(codes(new MailLayout("Example Shop", "{baseUrl}/brand/logo.png", "Example Shop",
            "Example Ltd, 1 Main St. [Help](https://help.example.org) · [Account]({entryUrl.portal}account) · "
                + "[Home]({baseUrl}/) · [Mail](mailto:help@example.org)"))).isEmpty();
        assertThat(codes(new MailLayout("Shop", "/api/public/files/0193/logo", "Shop", ""))).isEmpty();
    }

    @Test
    void refusesALogoOfAnotherHost() {
        for (String logo : List.of("https://tracker.example.net/p.gif", "//tracker.example.net/p.gif",
            "{baseUrl}.evil.example/p.gif", "{baseUrl}@evil.example/p.gif", "{entryUrl.portal}logo.png",
            "/a/{baseUrl}", "/logo.png?u={userName}", "logo.png", "/a b.png", "/a\\b.png", "javascript:alert(1)")) {
            assertThat(codes(new MailLayout("Shop", logo, "Shop", ""))).as(logo)
                .contains(PlatformErrorCodes.MAIL_LAYOUT_LOGO);
        }
    }

    @Test
    void aLogoNeedsItsText() {
        assertThat(codes(new MailLayout("Shop", "/logo.png", " ", "")))
            .containsExactly(PlatformErrorCodes.MAIL_LAYOUT_LOGO_ALT);
    }

    @Test
    void namesAreOneShortLine() {
        assertThat(codes(new MailLayout("Shop\nEvil", "", "", ""))).containsExactly(PlatformErrorCodes.MAIL_LAYOUT_LINE);
        assertThat(codes(new MailLayout("S".repeat(MailLayoutRules.MAX_LINE + 1), "/l.png", "x\ry", "")))
            .containsExactly(PlatformErrorCodes.MAIL_LAYOUT_LINE, PlatformErrorCodes.MAIL_LAYOUT_LINE);
        assertThat(codes(new MailLayout("😀".repeat(MailLayoutRules.MAX_LINE), "", "", ""))).isEmpty();
    }

    @Test
    void theFooterKeepsToTheRulesOfTheTexts() {
        assertThat(codes(footer("[x](https://evil.example)"))).containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(footer("Visit www.evil.example"))).containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(footer("[x](javascript:alert(1))")))
            .containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_NOT_ALLOWED);
        assertThat(codes(footer("![p](https://app.example.com/p.gif)"))).containsExactly(PlatformErrorCodes.MAIL_TEXT_IMAGE);
        assertThat(codes(footer("<b>x</b>"))).containsExactly(PlatformErrorCodes.MAIL_TEXT_HTML);
        assertThat(codes(footer("[x]({baseUrl}@evil.example/)"))).containsExactly(PlatformErrorCodes.MAIL_TEXT_LINK_HOST);
        assertThat(codes(footer("x".repeat(301)))).containsExactly(PlatformErrorCodes.MAIL_TEXT_TOO_LONG);
    }

    @Test
    void theFooterHasNoSecretsOfAMail() {
        List<Violation> problems = MailLayoutRules.check(footer(
            "[Stop]({unsubscribeUrl}) [In]({entryUrl}x?t={signIn}) {userName}"), LIMITS);
        assertThat(problems).extracting(Violation::ruleCode).containsOnly(PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER);
        assertThat(problems).extracting(v -> v.params().get("placeholder"))
            .containsExactlyInAnyOrder("unsubscribeUrl", "entryUrl", "signIn", "userName");
        assertThat(problems).extracting(Violation::field).containsOnly(MailLayoutRules.FOOTER);
        assertThat(MailLayoutRules.describe(problems)).allMatch(line -> line.startsWith("footer: "));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThat(codes(new MailLayout("A\nB", "https://x.example/l.png", "", "<i>{secret}</i>")))
            .containsExactlyInAnyOrder(PlatformErrorCodes.MAIL_LAYOUT_LINE, PlatformErrorCodes.MAIL_LAYOUT_LOGO,
                PlatformErrorCodes.MAIL_LAYOUT_LOGO_ALT, PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER,
                PlatformErrorCodes.MAIL_TEXT_HTML);
    }

    @Test
    void layoutsAndLimits() {
        assertThat(new MailLayout(null, null, null, null)).isEqualTo(MailLayout.EMPTY);
        assertThat(MailLayout.EMPTY.isEmpty()).isTrue();
        assertThat(new MailLayout("", "", "alt only", "").isEmpty()).isTrue();
        assertThat(new MailLayout("", "", "", "x").isEmpty()).isFalse();
        assertThat(new MailLayout("A", "", "", "x").hash()).isNotEqualTo(new MailLayout("", "", "A", "x").hash())
            .hasSize(64);
        assertThatThrownBy(() -> new MailLayoutRules.Limits(0, MailTextRules.Limits.DEFAULT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(MailLayoutRules.Limits.DEFAULT.maxFooterLength()).isEqualTo(MailLayoutRules.DEFAULT_MAX_FOOTER);
    }
}

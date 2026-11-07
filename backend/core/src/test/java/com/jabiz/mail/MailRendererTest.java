package com.jabiz.mail;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MailRendererTest {

    private static final MailTemplate VERIFY = MailTemplate.define("jabiz.verify", t -> t
        .category(MailCategory.TRANSACTIONAL).param("userName", "next").token("verify", Duration.ofHours(1)));

    private static MailRenderer.Rendered render(String body, Map<String, String> values) {
        return MailRenderer.render("Hello {userName}", body, values, VERIFY.urlSafePlaceholders());
    }

    @Test
    void rendersMarkdownToHtmlAndPlainText() {
        MailRenderer.Rendered mail = render("# Welcome\n\nHello **{userName}**,\n\n- one\n- two\n",
            Map.of("userName", "Ann"));

        assertThat(mail.subject()).isEqualTo("Hello Ann");
        assertThat(mail.html()).contains("<h1>Welcome</h1>", "<strong>Ann</strong>", "<li>one</li>");
        assertThat(mail.text()).contains("Welcome", "Hello Ann,", "one", "two").doesNotContain("**", "<");
    }

    @Test
    void escapesRawHtmlOfTheBody() {
        MailRenderer.Rendered mail = render("Hi <script>alert(1)</script>\n\n<div onclick=\"x()\">block</div>\n",
            Map.of());

        assertThat(mail.html()).doesNotContain("<script>", "<div").contains("&lt;script&gt;", "&lt;div");
    }

    @Test
    void escapesValuesInHtmlAndKeepsThemAsTheyAreInPlainText() {
        String evil = "<img src=x onerror=alert(1)> & *not bold* [link](javascript:alert(1))";
        MailRenderer.Rendered mail = render("Dear {userName}", Map.of("userName", evil));

        assertThat(mail.html()).contains("&lt;img src=x onerror=alert(1)&gt; &amp; *not bold*")
            .doesNotContain("<img", "<em>", "<a ");
        assertThat(mail.text()).contains("Dear " + evil);
        assertThat(mail.subject()).isEqualTo("Hello " + evil);
    }

    @Test
    void removesLinksToOtherSchemes() {
        MailRenderer.Rendered mail = render("[a](javascript:alert(1)) [b](JaVaScRiPt:alert(2)) "
            + "[c](data:text/html,x) [d](https://example.com/x) [e](mailto:a@example.com) [f](/relative)\n"
            + "![img](vbscript:x)", Map.of());

        assertThat(mail.html()).doesNotContain("javascript", "JaVaScRiPt", "data:", "vbscript")
            .contains("href=\"https://example.com/x\"", "href=\"mailto:a@example.com\"", "href=\"/relative\"");
    }

    @Test
    void fillsLinksWithUrlSafeValuesAsTheyAreAndEncodesParameters() {
        MailRenderer.Rendered mail = render("[Verify]({baseUrl}/verify?token={verify}&next={next})",
            Map.of("baseUrl", "https://app.example.com", "verify", "abc-DEF_123", "next", "/a b&c"));

        assertThat(mail.html()).contains(
            "href=\"https://app.example.com/verify?token=abc-DEF_123&amp;next=%2Fa%20b%26c\"");
        assertThat(mail.text()).contains("https://app.example.com/verify?token=abc-DEF_123&next=%2Fa%20b%26c");
    }

    @Test
    void aParameterCannotMakeALinkScriptable() {
        MailRenderer.Rendered mail = render("[Go]({next})", Map.of("next", "javascript:alert(1)"));

        assertThat(mail.html()).doesNotContain("href=\"javascript:").contains("href=\"javascript%3Aalert%281%29\"");
        MailRenderer.Rendered direct = MailRenderer.render("s", "[Go]({baseUrl})",
            Map.of("baseUrl", "javascript:alert(1)"), VERIFY.urlSafePlaceholders());
        assertThat(direct.html()).doesNotContain("javascript");
    }

    @Test
    void fillsCodeAndTitlesAndLeavesUnknownPlaceholders() {
        MailRenderer.Rendered mail = render("Code `{userName}`\n\n    {userName}\n\n[t](/x \"for {userName}\") {other}",
            Map.of("userName", "<b>"));

        assertThat(mail.html()).contains("<code>&lt;b&gt;</code>", "title=\"for &lt;b&gt;\"", "{other}");
    }

    @Test
    void theSubjectIsOneLineAndLimited() {
        String subject = MailRenderer.render("Order {userName}\r\nBcc: x@example.com", "b",
            Map.of("userName", "1\n2"), Set.of()).subject();
        assertThat(subject).isEqualTo("Order 1 2 Bcc: x@example.com");
        assertThat(MailRenderer.render("{userName}", "b", Map.of("userName", "x".repeat(400)), Set.of()).subject())
            .hasSize(MailTextRules.DEFAULT_MAX_SUBJECT);
        // Cut on characters, not UTF-16 units: an emoji across the limit is kept whole, never halved.
        String emoji = "x".repeat(MailTextRules.DEFAULT_MAX_SUBJECT - 1) + "😀" + "tail";
        String cut = MailRenderer.render("{userName}", "b", Map.of("userName", emoji), Set.of()).subject();
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(MailTextRules.DEFAULT_MAX_SUBJECT);
        assertThat(cut).endsWith("😀");
    }

    @Test
    void urlsAreCheckedAfterBrowsersWouldDropControls() {
        assertThat(MailRenderer.safeUrl("java\tscript:alert(1)")).isEmpty();
        assertThat(MailRenderer.safeUrl(" https://example.com ")).isEqualTo("https://example.com");
        assertThat(MailRenderer.safeUrl("1x:y")).isEmpty();
        assertThat(MailRenderer.safeUrl("a/b:c")).isEqualTo("a/b:c");
        assertThat(MailRenderer.safeUrl(null)).isEmpty();
    }

    @Test
    void checksPlaceholdersAgainstTheDeclaration() {
        assertThat(problems(VERIFY, "Hi {userName}", "{next} [v]({baseUrl}?t={verify})"))
            .isEmpty();
        assertThat(problems(VERIFY, "Hi {userName} {stray}", "{baseUrl}"))
            .containsExactly("uses {stray}, which the template does not declare",
                "does not use the declared {next}", "does not use the declared {verify}");
        MailTemplate news = MailTemplate.define("shop.news", t -> t.category(MailCategory.NOTIFICATION));
        assertThat(problems(news, "News", "Body"))
            .containsExactly("is a notification but its body has no {unsubscribeUrl}");
        assertThat(problems(VERIFY, "s {unsubscribeUrl}", "{userName}{next} [v]({baseUrl}?t={verify})"))
            .containsExactly("uses {unsubscribeUrl}, which the template does not declare");
        assertThat(MailPlaceholders.of(null)).isEmpty();
        assertThat(List.copyOf(MailPlaceholders.of("{b} {a} {b}"))).containsExactly("b", "a");
    }

    /** The problems as their messages (MailTextRulesTest has the rules; these are the placeholders'). */
    private static List<String> problems(MailTemplate template, String subject, String body) {
        return MailTextRules.check(template, subject, body, MailTextRules.Limits.DEFAULT).stream()
            .map(com.jabiz.entity.Violation::message).toList();
    }

    private static final Map<String, String> SITE = Map.of("baseUrl", "https://app.example.com",
        "entryUrl.portal", "https://app.example.com/portal/");

    @Test
    void wrapsAMailInTheLayout() {
        MailRenderer.Rendered mail = render("Hello **{userName}**", Map.of("userName", "<Ann>"));
        MailLayout layout = new MailLayout("Shop & Co <b>", "{baseUrl}/logo.png", "Shop \"logo\"",
            "Shop Ltd · [Account]({entryUrl.portal}account) · [Home]({baseUrl}/)");

        MailRenderer.Rendered wrapped = MailRenderer.wrap(mail, layout, SITE);

        assertThat(wrapped.subject()).isEqualTo(mail.subject());
        assertThat(wrapped.html()).startsWith("<!DOCTYPE html>")
            .contains("Shop &amp; Co &lt;b&gt;", "src=\"https://app.example.com/logo.png\"",
                "alt=\"Shop &quot;logo&quot;\"", "<strong>&lt;Ann&gt;</strong>",
                "href=\"https://app.example.com/portal/account\"", "href=\"https://app.example.com/\"",
                "max-width:600px", "<title>Hello &lt;Ann&gt;</title>")
            .doesNotContain("<script", "<link", "<style", "Shop & Co <b>");
        assertThat(wrapped.text()).startsWith("Shop & Co <b>\n\nHello <Ann>")
            .contains("\n-- \nShop Ltd")
            .doesNotContain("<strong>");
    }

    @Test
    void anEmptyLayoutLeavesTheMail() {
        MailRenderer.Rendered mail = render("Hello", Map.of());
        assertThat(MailRenderer.wrap(mail, MailLayout.EMPTY, SITE)).isSameAs(mail);
        MailRenderer.Rendered nameOnly = MailRenderer.wrap(mail, new MailLayout("Shop", "", "", ""), SITE);
        assertThat(nameOnly.text()).isEqualTo("Shop\n\nHello\n");
        assertThat(nameOnly.html()).doesNotContain("<img", "border-top");
    }

    @Test
    void aLogoOnlyOfTheSiteAndAPathUnderTheBase() {
        MailRenderer.Rendered mail = render("Hello", Map.of());
        assertThat(MailRenderer.wrap(mail, new MailLayout("", "/api/public/files/1/logo", "L", ""), SITE).html())
            .contains("src=\"https://app.example.com/api/public/files/1/logo\"");
        // A layout that slipped past the rules still shows no foreign image.
        assertThat(MailRenderer.wrap(mail, new MailLayout("Shop", "https://t.example/p.gif", "L", ""), SITE).html())
            .doesNotContain("<img", "t.example");
        assertThat(MailRenderer.logoUrl("{baseUrl}/x.png", Map.of())).isEqualTo("/x.png");
    }

    @Test
    void footerValuesAreNotMarkdown() {
        MailRenderer.Rendered mail = render("Hello", Map.of());
        MailRenderer.Rendered wrapped = MailRenderer.wrap(mail, new MailLayout("", "", "", "[a]({baseUrl}/x)"),
            Map.of("baseUrl", "javascript:alert(1)//"));
        assertThat(wrapped.html()).doesNotContain("javascript:");
    }

    @Test
    void plainTextBecomesEscapedParagraphs() {
        MailRenderer.Rendered mail = MailRenderer.plain("Task", "Line <1>\nLine 2\n\n\nNext & last\n");
        assertThat(mail.html()).isEqualTo("<p>Line &lt;1&gt;<br>\nLine 2</p>\n<p>Next &amp; last</p>\n");
        assertThat(mail.text()).isEqualTo("Line <1>\nLine 2\n\n\nNext & last\n");
        assertThat(MailRenderer.escape("'\"")).isEqualTo("&#39;&quot;");
    }
}

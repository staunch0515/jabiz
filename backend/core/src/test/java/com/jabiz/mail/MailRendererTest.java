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
            .hasSize(MailRenderer.MAX_SUBJECT);
        // Cut on characters, not UTF-16 units: an emoji across the limit is kept whole, never halved.
        String emoji = "x".repeat(MailRenderer.MAX_SUBJECT - 1) + "😀" + "tail";
        String cut = MailRenderer.render("{userName}", "b", Map.of("userName", emoji), Set.of()).subject();
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(MailRenderer.MAX_SUBJECT);
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
        assertThat(MailPlaceholders.problems(VERIFY, "Hi {userName}", "{next} [v]({baseUrl}?t={verify})"))
            .isEmpty();
        assertThat(MailPlaceholders.problems(VERIFY, "Hi {userName} {stray}", "{baseUrl}"))
            .containsExactly("uses {stray}, which the template does not declare",
                "does not use the declared {next}", "does not use the declared {verify}");
        MailTemplate news = MailTemplate.define("shop.news", t -> t.category(MailCategory.NOTIFICATION));
        assertThat(MailPlaceholders.problems(news, "News {unsubscribeUrl}", "Body"))
            .containsExactly("is a notification but its body has no {unsubscribeUrl}");
        assertThat(MailPlaceholders.problems(VERIFY, "s {unsubscribeUrl}", "{userName}{next}{verify}"))
            .containsExactly("uses {unsubscribeUrl}, which the template does not declare");
        assertThat(MailPlaceholders.of(null)).isEmpty();
        assertThat(List.copyOf(MailPlaceholders.of("{b} {a} {b}"))).containsExactly("b", "a");
    }
}

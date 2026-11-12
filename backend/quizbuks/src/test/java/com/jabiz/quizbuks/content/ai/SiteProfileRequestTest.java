package com.jabiz.quizbuks.content.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The site job's request (F-C7): the address becomes {@code https://host/} or is refused; the provider may fetch only
 * from the host and its {@code www.} twin; the web reader's request carries those domains and no search.
 */
class SiteProfileRequestTest {

    @Test
    void anAddressBecomesTheHostsHomeOrIsRefused() {
        assertThat(SiteProfileProcesses.siteUrl("https://www.Aini-Helmet.com/ja/about?x=1#y"))
            .isEqualTo("https://www.aini-helmet.com/ja/about");
        assertThat(SiteProfileProcesses.siteUrl("aini-helmet.com")).isEqualTo("https://aini-helmet.com/");
        assertThat(SiteProfileProcesses.siteUrl("  https://shop.example.co.jp  ")).isEqualTo("https://shop.example.co.jp/");
        for (String refused : new String[] {"http://aini.com/", "https://user:pw@aini.com/", "https://aini.com:8443/",
            "https://localhost/", "https://192.168.1.10/", "https://10.0.0.1/", "https://printer.local/",
            "https://aini/", "ftp://aini.com/", "https://", "", "not a host", "https://a_b.com/", "https://[::1]/",
            "https://aini.com.internal/"}) {
            assertThat(SiteProfileProcesses.siteUrl(refused)).as(refused).isNull();
        }
        assertThat(SiteProfileProcesses.siteUrl(null)).isNull();
    }

    @Test
    void aPathIsKeptAndTheQueryIsNot() {
        assertThat(SiteProfileProcesses.siteUrl("https://example.com/shops/tokyo/?a=1#b"))
            .isEqualTo("https://example.com/shops/tokyo/");
        assertThat(SiteProfileProcesses.siteUrl("https://name.github.io/blog/")).isEqualTo("https://name.github.io/blog/");
        assertThat(SiteProfileProcesses.siteUrl("https://example.com/日本語/ページ"))
            .isEqualTo("https://example.com/%E6%97%A5%E6%9C%AC%E8%AA%9E/%E3%83%9A%E3%83%BC%E3%82%B8");
    }

    @Test
    void aNameAnybodyMayRegisterUnderIsRefusedAsItStandsButNotWhatIsBelowIt() {
        for (String refused : new String[] {"https://co.jp/", "co.jp", "https://github.io/x", "https://netlify.app/",
            "https://com.cn/", "https://co.uk/", "https://myshopify.com/"}) {
            assertThat(SiteProfileProcesses.siteUrl(refused)).as(refused).isNull();
        }
        assertThat(SiteProfileProcesses.siteUrl("https://shop.co.jp/")).isEqualTo("https://shop.co.jp/");
        assertThat(SiteProfileProcesses.siteUrl("https://name.myshopify.com/")).isEqualTo("https://name.myshopify.com/");
    }

    @Test
    void aUnicodeHostGoesToTheJobInPunycode() {
        assertThat(SiteProfileProcesses.siteUrl("https://例え.jp/")).isEqualTo("https://xn--r8jz45g.jp/");
        assertThat(SiteProfileProcesses.siteUrl("例え.jp/店")).isEqualTo("https://xn--r8jz45g.jp/%E5%BA%97");
        assertThat(SiteProfileProcesses.siteUrl("https://ＥＸＡＭＰＬＥ.com/")).isEqualTo("https://example.com/");
        assertThat(SiteProfileProcesses.siteUrl("https://-bad-.com/")).isNull();
        assertThat(SiteProfileProcesses.domainsOf("https://xn--r8jz45g.jp/")).containsExactly("xn--r8jz45g.jp",
            "www.xn--r8jz45g.jp");
    }

    @Test
    void theRecordedSummaryHoldsTheHostOnly() {
        String summary = new SiteProfileProcesses.RequestInput("https://www.Aini.com/secret/path?token=abc", "ja", null,
            null, null, null, true, null).toString();
        assertThat(summary).contains("host=www.aini.com").doesNotContain("secret").doesNotContain("token");
        assertThat(new SiteProfileProcesses.RequestInput("user:pw@evil token=1", "ja", null, null, null, null, true, null)
            .toString()).contains("host=***").doesNotContain("pw");
    }

    @Test
    void theProviderMayFetchFromTheHostAndItsWwwTwinOnly() {
        assertThat(SiteProfileProcesses.domainsOf("https://www.aini.com/")).containsExactly("www.aini.com", "aini.com");
        assertThat(SiteProfileProcesses.domainsOf("https://aini.com/")).containsExactly("aini.com", "www.aini.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWebReadersRequestHasTheDomainsAndNoSearch() {
        ClaudeWebResearch.Request request = new ClaudeWebResearch.Request("claude-opus-5-5", "medium", "s", "u", "k", 0,
            10, null, List.of("www.aini.com", "aini.com"), 32_000);
        Map<String, Object> body = ClaudeWebResearch.body(request, List.of());
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst()).containsEntry("name", "web_fetch").containsEntry("max_uses", 10)
            .containsEntry("allowed_domains", List.of("www.aini.com", "aini.com"));
        assertThat(body).containsEntry("max_tokens", 32_000);
        // Prospecting's request is as it was: any address the conversation names, the usual output.
        Map<String, Object> prospecting = ClaudeWebResearch.body(new ClaudeWebResearch.Request("m", "low", "s", "u",
            null, 2, 5, "JP"), List.of());
        assertThat((List<Map<String, Object>>) prospecting.get("tools")).allSatisfy(tool ->
            assertThat(tool).doesNotContainKey("allowed_domains"));
        assertThat(prospecting).containsEntry("max_tokens", ClaudeWebResearch.MAX_TOKENS);
    }

    @Test
    void theUserMessageHoldsTheRequestAndNothingOfTheSponsor() {
        SiteProfileJob.Input input = new SiteProfileJob.Input("claude-opus-5-5", "medium", "https://www.aini.com/",
            List.of("www.aini.com", "aini.com"), "ja", List.of("ja", "en"), true, true, true, 10, 50, 5, 5, 4, "hmac");
        String user = SiteProfilePrompt.user(input, JsonMapper.builder().build());
        assertThat(user).startsWith("<request>").endsWith("</request>").contains("https://www.aini.com/")
            .contains("\"maxPages\":10").contains("\"languages\":[\"ja\",\"en\"]").doesNotContain("hmac");
    }
}

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
            .isEqualTo("https://www.aini-helmet.com/");
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

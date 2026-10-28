package com.jabiz.runtime.web;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.web.JabizWebProperties.Spa;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Index pages with the deployment's values (phase 17g). */
class SpaIndexesTest {

    static final String PAGE = "<!doctype html><head><meta name=\"shop-key\" content=\"{{jabiz:shop-key}}\">"
        + "<meta name=\"env\" content=\"{{jabiz:env}}\"></head><div id=\"root\"></div>";

    static Spa spa(String path, Map<String, String> values) {
        return new Spa(path, null, null, null, null, null, values);
    }

    @Test
    void valuesAreWrittenIntoTheirAttributesEscaped() {
        SpaIndexes.Render render = SpaIndexes.render(PAGE, Map.of("shop-key", "abc123", "env", "a\"<b>&'c"));
        assertThat(render.problems()).isEmpty();
        assertThat(render.used()).containsExactlyInAnyOrder("shop-key", "env");
        assertThat(render.page()).contains("content=\"abc123\"")
            .contains("content=\"a&quot;&lt;b&gt;&amp;&#39;c\"").doesNotContain("{{jabiz:");
    }

    @Test
    void aPlaceholderAnywhereButAWholeDoubleQuotedAttributeIsRefused() {
        for (String page : List.of("<p>{{jabiz:env}}</p>", "<script>var k = \"{{jabiz:env}}\"</script>",
            "<script>var k=\"{{jabiz:env}}\"</script>", "<script>if (a<b) k=\"{{jabiz:env}}\"</script>",
            "<style>a<b{x=\"{{jabiz:env}}\"}</style>", "<p>a=\"{{jabiz:env}}\"</p>",
            "<meta content='{{jabiz:env}}'>", "<meta content={{jabiz:env}}>", "<meta content=\"x {{jabiz:env}}\">",
            "<meta content=\"{{jabiz:env}} x\">")) {
            SpaIndexes.Render render = SpaIndexes.render(page, Map.of("env", "test"));
            assertThat(render.problems()).as(page).singleElement().asString().contains("whole attribute value");
            assertThat(render.used()).as(page).isEmpty();
            assertThat(render.mentioned()).as(page).containsExactly("env");
        }
        // After a script ends, a tag's attribute is one again.
        assertThat(SpaIndexes.render("<script>a<b</script><meta content=\"{{jabiz:env}}\">", Map.of("env", "x"))
            .problems()).isEmpty();
        // A value that looks like a placeholder is only a value.
        assertThat(SpaIndexes.render(PAGE, Map.of("shop-key", "{{jabiz:x", "env", "y")).problems()).isEmpty();
        // An opening never closed is refused too.
        assertThat(SpaIndexes.render("<meta content=\"{{jabiz:env\">", Map.of("env", "x")).problems())
            .containsExactly("{{jabiz: is opened but not closed");
    }

    @Test
    void aPlaceholderWithoutAValueIsAnErrorAndAnUnusedValueAWarning() {
        JabizWebProperties properties = new JabizWebProperties(List.of(spa("/", null),
            spa("/shop", Map.of("shop-key", "k", "spare", "s"))));
        SpaIndexes indexes = new SpaIndexes(properties, index -> Optional.of(PAGE));
        assertThat(indexes.check()).extracting(CheckProblem::severity, CheckProblem::location).containsExactly(
            org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "jabiz.web.spa[1].index-values"),
            org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.WARNING, "jabiz.web.spa[1].index-values.spare"));
        assertThat(indexes.check().getFirst().message()).contains("{{jabiz:env}} has no value");
        // A page with errors is not served with values.
        assertThat(indexes.page(properties.spa().get(1))).isEmpty();
    }

    @Test
    void aValueWhosePlaceholderHasTheWrongFormIsNotAlsoCalledUnused() {
        JabizWebProperties properties = new JabizWebProperties(List.of(spa("/shop", Map.of("env", "x"))));
        SpaIndexes indexes = new SpaIndexes(properties, index -> Optional.of("<p>{{jabiz:env}}</p>"));
        assertThat(indexes.check()).singleElement().satisfies(problem ->
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.ERROR));
    }

    @Test
    void anIndexThatCannotBeReadIsReportedNotThrown() {
        JabizWebProperties properties = new JabizWebProperties(List.of(spa("/shop", Map.of("env", "x"))));
        SpaIndexes indexes = new SpaIndexes(properties, index -> {
            throw new java.io.UncheckedIOException(new java.io.IOException("disk gone"));
        });
        assertThat(indexes.check()).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.ERROR);
            assertThat(problem.message()).contains("cannot be read: disk gone");
        });
    }

    @Test
    void anIndexTheJarDoesNotHoldIsAWarningAndServedAsBuilt() {
        JabizWebProperties properties = new JabizWebProperties(List.of(spa("/shop", Map.of("env", "x"))));
        SpaIndexes indexes = new SpaIndexes(properties, index -> Optional.empty());
        assertThat(indexes.check()).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
            assertThat(problem.message()).contains("/shop/index.html is not in the application");
        });
        assertThat(indexes.page(properties.spa().getFirst())).isEmpty();
    }

    @Test
    void pagesAreReadFromTheFirstStaticLocationThatHoldsThem() {
        JabizWebProperties properties = new JabizWebProperties(List.of(spa("/", null),
            spa("/shop", Map.of("shop-key", "from-config", "env", "test"))));
        SpaIndexes indexes = new SpaIndexes(properties, List.of("classpath:/nowhere/", "classpath:/index-values"),
            new DefaultResourceLoader());
        assertThat(indexes.check()).isEmpty();
        assertThat(new String(indexes.page(properties.spa().get(1)).orElseThrow().body(), StandardCharsets.UTF_8))
            .contains("content=\"from-config\"");
        // An SPA without values is served as built.
        assertThat(indexes.page(properties.spa().getFirst())).isEmpty();
    }

    @Test
    void namesAndValuesAreCheckedWithTheConfiguration() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Bad Name", "x");
        values.put("blank", " ");
        values.put("two-lines", "a\nb");
        values.put("long", "x".repeat(SpaIndexes.MAX_VALUE + 1));
        values.put("fine", "ok");
        assertThat(new JabizWebProperties(List.of(spa("/", values))).problems()).containsExactly(
            "jabiz.web.spa[0].index-values: \"Bad Name\" is not a value's name (a lower-case letter, then lower-case"
                + " letters, digits and hyphens, up to 40)",
            "jabiz.web.spa[0].index-values.blank: must be a non-empty single line of at most 2000 characters",
            "jabiz.web.spa[0].index-values.two-lines: must be a non-empty single line of at most 2000 characters",
            "jabiz.web.spa[0].index-values.long: must be a non-empty single line of at most 2000 characters");
    }

    @Test
    void theFilterAnswersARouteWithThePageAndItsHeaders() {
        JabizWebProperties properties = new JabizWebProperties(List.of(spa("/", null),
            spa("/shop", Map.of("shop-key", "k1", "env", "test"))));
        SpaIndexes indexes = new SpaIndexes(properties, index -> Optional.of(PAGE));
        SpaFallbackFilter filter = new SpaFallbackFilter(properties, StaticPages.none(), SpaEmbedding.NONE, indexes);
        for (String path : List.of("/shop", "/shop/", "/shop/campaigns")) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            AtomicBoolean passedOn = new AtomicBoolean();
            filter.filter(exchange, e -> {
                passedOn.set(true);
                return e.getResponse().setComplete();
            }).block();
            assertThat(passedOn).as(path).isFalse();
            assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo(JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY);
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-cache");
            assertThat(exchange.getResponse().getHeaders().getContentType()).hasToString("text/html;charset=UTF-8");
            assertThat(exchange.getResponse().getBodyAsString().block()).contains("content=\"k1\"");
        }
        // Asked for by its own name (a Service Worker precaches it): the same page; a revalidation gets 304; HEAD no body.
        MockServerWebExchange direct = MockServerWebExchange.from(MockServerHttpRequest.get("/shop/index.html"));
        filter.filter(direct, e -> e.getResponse().setComplete()).block();
        assertThat(direct.getResponse().getBodyAsString().block()).contains("content=\"k1\"");
        String etag = direct.getResponse().getHeaders().getETag();
        assertThat(etag).startsWith("\"").endsWith("\"");
        MockServerWebExchange again = MockServerWebExchange.from(MockServerHttpRequest.get("/shop/orders")
            .ifNoneMatch(etag));
        filter.filter(again, e -> e.getResponse().setComplete()).block();
        assertThat(again.getResponse().getStatusCode().value()).isEqualTo(304);
        assertThat(again.getResponse().getHeaders().getCacheControl()).isEqualTo("no-cache");
        MockServerWebExchange head = MockServerWebExchange.from(MockServerHttpRequest.head("/shop/index.html"));
        filter.filter(head, e -> e.getResponse().setComplete()).block();
        assertThat(head.getResponse().getStatusCode().value()).isEqualTo(200);
        assertThat(head.getResponse().getHeaders().getContentLength()).isPositive();
        assertThat(head.getResponse().getBodyAsString().block()).isEmpty();
        // Its files and the other SPA go on as before.
        for (String path : List.of("/shop/assets/a.js", "/about")) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            AtomicBoolean passedOn = new AtomicBoolean();
            filter.filter(exchange, e -> {
                passedOn.set(true);
                return e.getResponse().setComplete();
            }).block();
            assertThat(passedOn).as(path).isTrue();
        }
    }
}

package com.jabiz.runtime.web;

import com.jabiz.runtime.web.JabizWebProperties.Spa;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SpaFallbackFilterTest {

    private static final JabizWebProperties DEFAULT = new JabizWebProperties(null);
    private static final JabizWebProperties TWO = new JabizWebProperties(List.of(
        new Spa("/", null, "default-src 'self'; frame-src https://video.example"),
        new Spa("/admin", null, null)));

    /** The default configuration behaves as the filter did before it was configurable. */
    @ParameterizedTest
    @CsvSource({
        "GET, /, /index.html",
        "GET, /data, /index.html",
        "GET, /data/carrier/1/history, /index.html",
        "GET, /admin/data, /index.html",
        "GET, /assets/index-abc.js, /assets/index-abc.js",
        "GET, /favicon.svg, /favicon.svg",
        "GET, /api/meta/datasets, /api/meta/datasets",
        "GET, /apix, /apix",
        "GET, /actuator/health, /actuator/health",
        "POST, /data, /data",
        "HEAD, /data, /data",
    })
    void defaultServesOneSpaAtTheRoot(String method, String path, String forwarded) {
        assertThat(run(DEFAULT, method, path).forwardedPath()).isEqualTo(forwarded);
    }

    @ParameterizedTest
    @CsvSource({
        "/, /index.html",
        "/about/team, /index.html",
        "/administrator, /index.html",
        "/admin, /admin/index.html",
        "/admin/, /admin/index.html",
        "/admin/data/carrier, /admin/index.html",
        "/admin/assets/a.js, /admin/assets/a.js",
        "/api/queries, /api/queries",
    })
    void longestPrefixOfWholeSegmentsWins(String path, String forwarded) {
        assertThat(run(TWO, "GET", path).forwardedPath()).isEqualTo(forwarded);
    }

    @Test
    void eachSpaCarriesItsOwnPolicy() {
        assertThat(run(TWO, "GET", "/about").csp()).isEqualTo("default-src 'self'; frame-src https://video.example");
        assertThat(run(TWO, "GET", "/assets/x.js").csp()).isEqualTo("default-src 'self'; frame-src https://video.example");
        assertThat(run(TWO, "GET", "/admin/data").csp()).isEqualTo(JabizWebProperties.ADMIN_CONTENT_SECURITY_POLICY);
        assertThat(run(TWO, "GET", "/admin/assets/a.js").csp())
            .isEqualTo(JabizWebProperties.ADMIN_CONTENT_SECURITY_POLICY);
    }

    @Test
    void anUnconfiguredPolicyIsTheAdminPolicy() {
        assertThat(run(DEFAULT, "GET", "/data").csp()).isEqualTo(JabizWebProperties.ADMIN_CONTENT_SECURITY_POLICY);
        assertThat(run(DEFAULT, "POST", "/data").csp()).isEqualTo(JabizWebProperties.ADMIN_CONTENT_SECURITY_POLICY);
    }

    @Test
    void apiAndActuatorResponsesCarryNoPagePolicy() {
        assertThat(run(TWO, "GET", "/api/meta/datasets").csp()).isNull();
        assertThat(run(TWO, "GET", "/actuator/health").csp()).isNull();
    }

    @Test
    void aPathOutsideEverySpaIsLeftAlone() {
        var onlyAdmin = new JabizWebProperties(List.of(new Spa("/admin", null, null)));
        Result result = run(onlyAdmin, "GET", "/about");
        assertThat(result.forwardedPath()).isEqualTo("/about");
        assertThat(result.csp()).isNull();
    }

    private static final JabizWebProperties REDIRECTED = new JabizWebProperties(List.of(
        new Spa("/taker", null, null, "app", JabizWebProperties.Pwa.ON),
        new Spa("/sponsor", null, null, "sponsor", null)), null, "/biz/", List.of(
        new JabizWebProperties.Redirect("/p", "/taker/p"), new JabizWebProperties.Redirect("/auth", "/taker/auth")));

    /** A link printed or mailed before the app moved still opens (301 to the same path under the new prefix). */
    @ParameterizedTest
    @CsvSource({
        "/p/abc, /taker/p/abc",
        "/p, /taker/p",
        "/auth/email-link?entry=app&token=t&to=%2Fp%2F1, /taker/auth/email-link?entry=app&token=t&to=%2Fp%2F1",
        "/?utm_source=x&ref=y, /biz/?utm_source=x&ref=y",
    })
    void aMovedPrefixAndTheRootKeepTheQuery(String request, String location) {
        // The request as the server receives it (the mock's template form would encode the query a second time).
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET,
            java.net.URI.create(request)));
        new SpaFallbackFilter(REDIRECTED).filter(exchange, e -> e.getResponse().setComplete()).block();
        boolean root = request.startsWith("/?");
        assertThat(exchange.getResponse().getStatusCode())
            .isEqualTo(root ? org.springframework.http.HttpStatus.FOUND : org.springframework.http.HttpStatus.MOVED_PERMANENTLY);
        assertThat(exchange.getResponse().getHeaders().getFirst("Location")).isEqualTo(location);
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-cache");
    }

    /** The bare address opens the configured path; everything else is untouched (phase 16zf-a). */
    @ParameterizedTest
    @CsvSource({"GET", "HEAD"})
    void theRootIsRedirectedWhenConfigured(String method) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), "/"));
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        new SpaFallbackFilter(REDIRECTED).filter(exchange, e -> {
            reached.set(true);
            return e.getResponse().setComplete();
        }).block();
        assertThat(reached.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString("/biz/");
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy")).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /taker, /taker/index.html",
        "GET, /taker/p/abc, /taker/index.html",
        "GET, /sponsor/quizzes, /sponsor/index.html",
        "GET, /biz/, /biz/",
        "GET, /index.html, /index.html",
        "GET, /profile, /profile",
        "GET, /pages, /pages",
        "POST, /, /",
        "POST, /p/abc, /p/abc",
    })
    void onlyTheBareRootIsRedirected(String method, String path, String forwarded) {
        Result result = run(REDIRECTED, method, path);
        assertThat(result.forwardedPath()).isEqualTo(forwarded);
    }

    @Test
    void withoutTheSettingTheRootIsServedAsBefore() {
        assertThat(run(TWO, "GET", "/").forwardedPath()).isEqualTo("/index.html");
        assertThat(new JabizWebProperties(null, null, " ").rootRedirect()).isNull();
    }

    /** A static site (no entry) has a page per directory; other routes fall back to its root page (16zf-b). */
    @ParameterizedTest
    @CsvSource({
        "/biz, /biz/index.html",
        "/biz/, /biz/index.html",
        "/biz/zh, /biz/zh/index.html",
        "/biz/zh/, /biz/zh/index.html",
        "/biz/zh/pricing/, /biz/zh/pricing/index.html",
        "/biz/zh/unknown, /biz/index.html",
        "/biz/assets/a.css, /biz/assets/a.css",
        "/taker/zh, /taker/index.html",
    })
    void aStaticSiteServesThePageOfADirectory(String path, String forwarded) {
        var site = new JabizWebProperties(List.of(new Spa("/biz", null, null),
            new Spa("/taker", null, null, "app", null)));
        var pages = new StaticPages(java.util.Map.of("/biz", java.util.Set.of("/biz/zh", "/biz/zh/pricing", "/biz/ja"),
            "/taker", java.util.Set.of("/taker/zh")));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        AtomicReference<String> seen = new AtomicReference<>();
        new SpaFallbackFilter(site, pages).filter(exchange, e -> {
            seen.set(e.getRequest().getPath().value());
            return e.getResponse().setComplete();
        }).block();
        assertThat(seen.get()).isEqualTo(forwarded);
        if (forwarded.endsWith("index.html")) {
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-cache");
        }
    }

    @Test
    void anExplicitIndexIsUsed() {
        var custom = new JabizWebProperties(List.of(new Spa("/admin", "/admin/app.html", null)));
        assertThat(run(custom, "GET", "/admin/x").forwardedPath()).isEqualTo("/admin/app.html");
    }

    private static final JabizWebProperties PWA = new JabizWebProperties(List.of(
        new Spa("/", null, null, "admin", null),
        new Spa("/portal", null, null, "portal", JabizWebProperties.Pwa.ON),
        new Spa("/old", null, null, "old", JabizWebProperties.Pwa.REMOVE)));

    @Test
    void theDefaultPolicyIsTightenedForEverySpa() {
        assertThat(JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY).contains("default-src 'self'",
            "base-uri 'self'", "object-src 'none'", "form-action 'self'", "worker-src 'self'",
            "manifest-src 'self'", "frame-ancestors 'none'").doesNotContain("script-src", "unsafe-eval");
        assertThat(run(PWA, "GET", "/portal/orders").csp()).isEqualTo(JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY);
        assertThat(run(PWA, "GET", "/data").csp()).isEqualTo(JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        // Pages and the files that say what the current version is: revalidated on every use.
        "/, 200, no-cache",
        "/data/carrier, 200, no-cache",
        "/index.html, 200, no-cache",
        "/portal, 200, no-cache",
        "/portal/orders, 200, no-cache",
        "/portal/index.html, 200, no-cache",
        "/portal/sw.js, 200, no-cache",
        "/portal/manifest.webmanifest, 200, no-cache",
        "/portal/index.html, 304, no-cache",
        // Content-hashed files never change.
        "/assets/index-abc.js, 200, 'public, max-age=31536000, immutable'",
        "/portal/assets/index-abc.js, 200, 'public, max-age=31536000, immutable'",
        // Not found is not cached for a year; other files keep what they have.
        "/portal/assets/missing.js, 404, -",
        "/portal/icon-192.png, 200, -",
        "/portal/sw.js, 404, -",
    })
    void cacheHeadersFollowWhatAFileIs(String path, int status, String cacheControl) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        new SpaFallbackFilter(PWA).filter(exchange, e -> {
            // As Spring Security writes them on every response.
            e.getResponse().getHeaders().setCacheControl("no-cache, no-store, max-age=0, must-revalidate");
            e.getResponse().getHeaders().set("Pragma", "no-cache");
            e.getResponse().getHeaders().set("Expires", "0");
            e.getResponse().setStatusCode(org.springframework.http.HttpStatusCode.valueOf(status));
            return e.getResponse().setComplete();
        }).block();
        var headers = exchange.getResponse().getHeaders();
        if (cacheControl == null) {
            assertThat(headers.getCacheControl()).isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        } else {
            assertThat(headers.getCacheControl()).as(path).isEqualTo(cacheControl);
            assertThat(headers.getFirst("Pragma")).isNull();
            assertThat(headers.getFirst("Expires")).isNull();
        }
    }

    @Test
    void theManifestIsAManifestAndNoScopeIsWidened() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/portal/manifest.webmanifest"));
        new SpaFallbackFilter(PWA).filter(exchange, e -> {
            e.getResponse().getHeaders().set("Content-Type", "application/octet-stream");
            e.getResponse().getHeaders().set("Service-Worker-Allowed", "/");
            return e.getResponse().setComplete();
        }).block();
        assertThat(exchange.getResponse().getHeaders().getContentType()).hasToString("application/manifest+json");
        assertThat(exchange.getResponse().getHeaders().getFirst("Service-Worker-Allowed")).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        // Only the declared Service Workers of PWAs (and of one being taken back).
        "/portal/sw.js, 200",
        "/old/sw.js, 200",
        // Not the API, not an SPA without a PWA, not any other file, not another place.
        "/sw.js, 404",
        "/api/files/x, 404",
        "/api/public/files/1, 404",
        "/portal/assets/sw.js, 404",
        "/portal/assets/index-abc.js, 404",
        "/portal/sw.js/, 404",
        "/elsewhere/sw.js, 404",
    })
    void onlyTheDeclaredServiceWorkerAnswersAServiceWorkerScriptRequest(String path, int status) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).header("Service-Worker", "script"));
        AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
        new ServiceWorkerScriptGuard(PWA).filter(exchange, e -> {
            passed.set(e);
            e.getResponse().setStatusCode(org.springframework.http.HttpStatus.OK);
            return e.getResponse().setComplete();
        }).block();
        assertThat(exchange.getResponse().getStatusCode().value()).as(path).isEqualTo(status);
        assertThat(passed.get() != null).isEqualTo(status == 200);
    }

    @Test
    void requestsThatFetchNoServiceWorkerPass() {
        for (String path : new String[] {"/sw.js", "/api/meta/datasets", "/portal/assets/a.js"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
            new ServiceWorkerScriptGuard(PWA).filter(exchange, e -> {
                passed.set(e);
                return e.getResponse().setComplete();
            }).block();
            assertThat(passed.get()).as(path).isNotNull();
        }
        // Without any PWA, every Service Worker script request is refused.
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/sw.js").header("Service-Worker", "script"));
        new ServiceWorkerScriptGuard(DEFAULT).filter(exchange, e -> e.getResponse().setComplete()).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(404);
    }

    private record Result(String forwardedPath, String csp) {}

    private static Result run(JabizWebProperties properties, String method, String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), path));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        new SpaFallbackFilter(properties).filter(exchange, e -> {
            forwarded.set(e);
            return e.getResponse().setComplete();
        }).block();
        return new Result(forwarded.get().getRequest().getPath().value(),
            exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"));
    }
}

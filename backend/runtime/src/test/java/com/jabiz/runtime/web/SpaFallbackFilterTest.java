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

    @Test
    void anExplicitIndexIsUsed() {
        var custom = new JabizWebProperties(List.of(new Spa("/admin", "/admin/app.html", null)));
        assertThat(run(custom, "GET", "/admin/x").forwardedPath()).isEqualTo("/admin/app.html");
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

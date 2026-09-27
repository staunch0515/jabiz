package com.jabiz.runtime.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Serves the single-page applications of {@link JabizWebProperties} (docs/design/17-apps-and-branches.md section
 * 3.2): a {@code GET} of a client-side route (no file extension) under an SPA's prefix is answered with that SPA's
 * index page, the longest prefix winning; every response under the prefix carries the SPA's
 * {@code Content-Security-Policy}. {@code /api} and {@code /actuator} are never SPA routes.
 */
@Component
class SpaFallbackFilter implements WebFilter {

    private final JabizWebProperties properties;

    SpaFallbackFilter(JabizWebProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var req = exchange.getRequest();
        String path = req.getPath().value();
        if (excluded(path)) {
            return chain.filter(exchange);
        }
        var spa = properties.spaFor(path).orElse(null);
        if (spa == null) {
            return chain.filter(exchange);
        }
        var response = exchange.getResponse();
        response.beforeCommit(() -> {
            response.getHeaders().set(CONTENT_SECURITY_POLICY, spa.contentSecurityPolicy());
            return Mono.empty();
        });
        if (HttpMethod.GET.equals(req.getMethod()) && !path.contains(".")) {
            return chain.filter(exchange.mutate().request(req.mutate().path(spa.index()).build()).build());
        }
        return chain.filter(exchange);
    }

    /** The API and the actuator: never rewritten, and their responses carry no page policy. */
    static boolean excluded(String path) {
        return path.startsWith("/api") || path.startsWith("/actuator");
    }

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
}

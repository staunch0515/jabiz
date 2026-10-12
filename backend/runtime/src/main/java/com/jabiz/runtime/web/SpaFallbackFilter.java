package com.jabiz.runtime.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
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
 *
 * <p>Caching (decision D43 item 6): the index page, the Service Worker and the manifest are revalidated on every use
 * ({@code no-cache}), so a new version reaches the browser; the content-hashed files under {@code <prefix>/assets/}
 * never change and are cached for a year ({@code immutable}). These replace the {@code no-store} Spring Security puts
 * on every response, which would also make each page load fetch every file again. A manifest is
 * {@code application/manifest+json}. Requests for a Service Worker script are answered by
 * {@link ServiceWorkerScriptGuard} first.
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
        boolean route = HttpMethod.GET.equals(req.getMethod()) && !path.contains(".");
        var response = exchange.getResponse();
        response.beforeCommit(() -> {
            HttpHeaders headers = response.getHeaders();
            headers.set(CONTENT_SECURITY_POLICY, spa.contentSecurityPolicy());
            // Only for the scope the browser derives from the script's own directory, never wider (decision D43 item 6).
            headers.remove(SERVICE_WORKER_ALLOWED);
            cacheHeaders(response, spa, route ? spa.index() : path);
            return Mono.empty();
        });
        if (route) {
            return chain.filter(exchange.mutate().request(req.mutate().path(spa.index()).build()).build());
        }
        return chain.filter(exchange);
    }

    /** Cache headers and the manifest's type of a file of {@code spa} that was answered successfully. */
    private static void cacheHeaders(ServerHttpResponse response, JabizWebProperties.Spa spa, String file) {
        HttpStatusCode status = response.getStatusCode();
        boolean ok = status == null || status.value() == 200;
        if (!ok && (status.value() != 304)) {
            return;
        }
        HttpHeaders headers = response.getHeaders();
        String cacheControl = null;
        if (file.equals(spa.index()) || file.equals(spa.base() + JabizWebProperties.SERVICE_WORKER)
            || file.equals(spa.base() + JabizWebProperties.MANIFEST)) {
            cacheControl = NO_CACHE;
        } else if (file.startsWith(spa.base() + JabizWebProperties.ASSETS) && ok) {
            cacheControl = IMMUTABLE;
        }
        if (cacheControl != null) {
            headers.setCacheControl(cacheControl);
            headers.remove(HttpHeaders.PRAGMA);
            headers.remove(HttpHeaders.EXPIRES);
        }
        if (ok && file.endsWith(MANIFEST_EXTENSION)) {
            headers.setContentType(MANIFEST_TYPE);
        }
    }

    /** The API and the actuator: never rewritten, and their responses carry no page policy. */
    static boolean excluded(String path) {
        return path.startsWith("/api") || path.startsWith("/actuator");
    }

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    static final String SERVICE_WORKER_ALLOWED = "Service-Worker-Allowed";
    static final String NO_CACHE = "no-cache";
    static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final String MANIFEST_EXTENSION = ".webmanifest";
    static final MediaType MANIFEST_TYPE = MediaType.parseMediaType("application/manifest+json");
}

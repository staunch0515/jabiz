package com.jabiz.runtime.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 *
 * <p>A static site (an SPA without a sign-in entry) may hold a page per directory ({@link StaticPages}, phase 16zf-b):
 * a route that names such a directory is answered with that directory's {@code index.html} instead of the site's root
 * page.
 *
 * <p>An SPA with values for its index page (phase 17g, {@link SpaIndexes}) is answered with the page those values
 * were written into, rendered once at startup, with the same headers.
 *
 * <p>Redirects (phase 16zf-a), before any SPA is looked up: a {@code GET} or {@code HEAD} of exactly {@code /} goes
 * (302) to {@code jabiz.web.root-redirect} when one is configured; one of a prefix in {@code jabiz.web.redirects}, or
 * of a path under it, goes (301) to the same path under its new prefix. Both keep the query string, carry no page
 * policy, and are revalidated on every use ({@code no-cache}, set before the response completes so that Spring
 * Security adds neither {@code no-store} nor {@code Pragma}), so a changed target reaches the browser.
 */
@Component
class SpaFallbackFilter implements WebFilter {

    private final JabizWebProperties properties;
    private final StaticPages pages;
    private final SpaEmbedding embedding;
    private final SpaIndexes indexes;

    @org.springframework.beans.factory.annotation.Autowired
    SpaFallbackFilter(JabizWebProperties properties, StaticPages pages,
        org.springframework.beans.factory.ObjectProvider<SpaEmbedding> embedding,
        org.springframework.beans.factory.ObjectProvider<SpaIndexes> indexes) {
        this(properties, pages, embedding.getIfAvailable(() -> SpaEmbedding.NONE),
            indexes.getIfAvailable(SpaIndexes::none));
    }

    SpaFallbackFilter(JabizWebProperties properties, StaticPages pages, SpaEmbedding embedding, SpaIndexes indexes) {
        this.properties = properties;
        this.pages = pages;
        this.embedding = embedding;
        this.indexes = indexes;
    }

    SpaFallbackFilter(JabizWebProperties properties, StaticPages pages, SpaEmbedding embedding) {
        this(properties, pages, embedding, SpaIndexes.none());
    }

    /** Never framed. */
    SpaFallbackFilter(JabizWebProperties properties, StaticPages pages) {
        this(properties, pages, SpaEmbedding.NONE);
    }

    /** Without static pages: every route of an SPA is answered with its index. */
    SpaFallbackFilter(JabizWebProperties properties) {
        this(properties, StaticPages.none(), SpaEmbedding.NONE);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var req = exchange.getRequest();
        String path = req.getPath().value();
        if (excluded(path)) {
            return chain.filter(exchange);
        }
        boolean read = HttpMethod.GET.equals(req.getMethod()) || HttpMethod.HEAD.equals(req.getMethod());
        if (read && properties.rootRedirect() != null && "/".equals(path)) {
            return redirect(exchange, HttpStatus.FOUND, properties.rootRedirect());
        }
        if (read) {
            var moved = properties.redirectFor(path).orElse(null);
            if (moved != null) {
                return redirect(exchange, HttpStatus.MOVED_PERMANENTLY, moved.target(path));
            }
        }
        var spa = properties.spaFor(path).orElse(null);
        if (spa == null) {
            return chain.filter(exchange);
        }
        boolean route = HttpMethod.GET.equals(req.getMethod()) && !path.contains(".");
        // A static site's own page for the directory asked for (phase 16zf-b), else the SPA's index.
        String page = route ? pages.pageFor(spa, path).orElse(spa.index()) : path;
        var response = exchange.getResponse();
        String ancestors = spa.embedding() == null ? null : embedding.tenantParameter(spa.embedding())
            .map(parameter -> req.getQueryParams().getFirst(parameter))
            .flatMap(tenant -> embedding.frameAncestors(spa.embedding(), tenant)).orElse(null);
        response.beforeCommit(() -> {
            HttpHeaders headers = response.getHeaders();
            if (ancestors == null) {
                headers.set(CONTENT_SECURITY_POLICY, spa.contentSecurityPolicy());
            } else {
                // Framed by the tenant that opened it (decision D59): the policy names its origins, and the older
                // X-Frame-Options Spring Security wrote (DENY; it cannot name an origin) goes.
                headers.set(CONTENT_SECURITY_POLICY, withFrameAncestors(spa.contentSecurityPolicy(), ancestors));
                headers.remove(X_FRAME_OPTIONS);
            }
            // Only for the scope the browser derives from the script's own directory, never wider (decision D43 item 6).
            headers.remove(SERVICE_WORKER_ALLOWED);
            cacheHeaders(response, spa, page);
            return Mono.empty();
        });
        // The index with this deployment's values (phase 17g), rendered once at startup: as a route's page, and when
        // asked for by its own name (as a Service Worker precaches it), never as built.
        boolean index = route ? page.equals(spa.index()) : read && path.equals(spa.index());
        var rendered = index ? indexes.page(spa).orElse(null) : null;
        if (rendered != null) {
            HttpHeaders headers = response.getHeaders();
            headers.setETag(rendered.etag());
            headers.setContentType(HTML);
            if (req.getHeaders().getIfNoneMatch().contains(rendered.etag())) {
                response.setStatusCode(HttpStatus.NOT_MODIFIED);
                return response.setComplete();
            }
            response.setStatusCode(HttpStatus.OK);
            headers.setContentLength(rendered.body().length);
            if (HttpMethod.HEAD.equals(req.getMethod())) {
                return response.setComplete();
            }
            return response.writeWith(Mono.just(response.bufferFactory().wrap(rendered.body())));
        }
        if (route) {
            return chain.filter(exchange.mutate().request(req.mutate().path(page).build()).build());
        }
        return chain.filter(exchange);
    }

    /**
     * Answers with a redirect to {@code target} (a path of this host, validated by the configuration), the request's
     * raw query kept. The header is written as text: a raw query may hold characters {@code URI.create} rejects, and
     * it cannot hold a line break (the server refuses such request lines), so no header is injected.
     */
    private static Mono<Void> redirect(ServerWebExchange exchange, HttpStatus status, String target) {
        String query = exchange.getRequest().getURI().getRawQuery();
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().set(HttpHeaders.LOCATION, query == null || query.isEmpty() ? target : target + "?" + query);
        response.getHeaders().setCacheControl(NO_CACHE);
        return response.setComplete();
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
        // Every page (the index and a static site's directory pages) says what the current version is.
        if (file.equals(spa.index()) || file.endsWith("/index.html") || file.equals(spa.base() + JabizWebProperties.SERVICE_WORKER)
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

    /** {@code policy} with its {@code frame-ancestors} directive replaced by (or, without one, added as) the given. */
    static String withFrameAncestors(String policy, String sources) {
        StringBuilder rewritten = new StringBuilder();
        for (String directive : policy.split(";")) {
            String trimmed = directive.strip();
            if (trimmed.isEmpty() || trimmed.toLowerCase(java.util.Locale.ROOT).matches("frame-ancestors(\\s.*)?")) {
                continue;
            }
            rewritten.append(trimmed).append("; ");
        }
        return rewritten.append("frame-ancestors ").append(sources).toString();
    }

    /** The API and the actuator: never rewritten, and their responses carry no page policy. */
    static boolean excluded(String path) {
        return path.startsWith("/api") || path.startsWith("/actuator") || NativeAppAssociations.PATHS.contains(path);
    }

    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    static final String X_FRAME_OPTIONS = "X-Frame-Options";
    static final String SERVICE_WORKER_ALLOWED = "Service-Worker-Allowed";
    static final String NO_CACHE = "no-cache";
    static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final String MANIFEST_EXTENSION = ".webmanifest";
    static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8);
    static final MediaType MANIFEST_TYPE = MediaType.parseMediaType("application/manifest+json");
}

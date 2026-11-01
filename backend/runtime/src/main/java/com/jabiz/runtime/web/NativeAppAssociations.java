package com.jabiz.runtime.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The association files of the store apps that open the SPAs' addresses (phase 17l, decision D68; docs/design/
 * 17-apps-and-branches.md section 3.2), from {@code jabiz.web.native-apps}, at the host's root where the systems look
 * for them: {@value #APPLE} lets an iOS app open the SPA's addresses as Universal Links (only that SPA's path), and
 * {@value #ANDROID} lets an Android app open them as App Links or show them in a trusted web activity. Public, JSON,
 * never redirected (the systems refuse that), cached for an hour. A filter, not an API: without an app of that system
 * the request goes on as before (a static file of the build, else 404), and the files are no part of the OpenAPI
 * document.
 */
@Component
public class NativeAppAssociations implements WebFilter {

    public static final String APPLE = "/.well-known/apple-app-site-association";
    public static final String ANDROID = "/.well-known/assetlinks.json";
    /** Answered here, never by an SPA's routes. */
    static final Set<String> PATHS = Set.of(APPLE, ANDROID);
    static final Duration CACHE = Duration.ofHours(1);

    private final JabizWebProperties properties;
    private final JsonMapper json;

    NativeAppAssociations(JabizWebProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        String path = request.getPath().value();
        boolean read = HttpMethod.GET.equals(request.getMethod()) || HttpMethod.HEAD.equals(request.getMethod());
        Optional<Object> body = !read ? Optional.empty()
            : APPLE.equals(path) ? apple() : ANDROID.equals(path) ? android() : Optional.empty();
        if (body.isEmpty()) {
            return chain.filter(exchange);
        }
        byte[] bytes = json.writeValueAsBytes(body.get());
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl(CacheControl.maxAge(CACHE).cachePublic());
        headers.setContentLength(bytes.length);
        if (HttpMethod.HEAD.equals(request.getMethod())) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /**
     * Apple's format: one entry per SPA with iOS apps, its apps opening the addresses under its path only (all of
     * them for an SPA at {@code /}); the empty {@code apps} that systems before iOS 13 require. Empty without iOS apps.
     */
    Optional<Object> apple() {
        List<Map<String, Object>> details = new ArrayList<>();
        for (JabizWebProperties.NativeApp app : properties.nativeApps()) {
            if (!app.ios().isEmpty()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("appIDs", app.ios());
                detail.put("components", List.of(Map.of("/", pattern(app.path()))));
                details.add(detail);
            }
        }
        if (details.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> applinks = new LinkedHashMap<>();
        applinks.put("apps", List.of());
        applinks.put("details", details);
        return Optional.of(Map.of("applinks", applinks));
    }

    /** Digital Asset Links: every Android app may handle this site's links (its manifest says which). */
    Optional<Object> android() {
        List<Map<String, Object>> statements = new ArrayList<>();
        for (JabizWebProperties.NativeApp app : properties.nativeApps()) {
            for (JabizWebProperties.AndroidApp android : app.android()) {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("namespace", "android_app");
                target.put("package_name", android.packageName());
                target.put("sha256_cert_fingerprints", android.fingerprints());
                Map<String, Object> statement = new LinkedHashMap<>();
                statement.put("relation", List.of("delegate_permission/common.handle_all_urls"));
                statement.put("target", target);
                if (!statements.contains(statement)) {
                    statements.add(statement);
                }
            }
        }
        return statements.isEmpty() ? Optional.empty() : Optional.of(statements);
    }

    /** The addresses of an SPA as Apple matches them: its prefix and everything under it. */
    static String pattern(String path) {
        return "/".equals(path) ? "/*" : path + "/*";
    }
}

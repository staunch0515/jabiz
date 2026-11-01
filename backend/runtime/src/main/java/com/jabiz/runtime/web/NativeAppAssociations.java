package com.jabiz.runtime.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The association files of the store apps that open the SPAs' addresses (phase 17l, decision D68; docs/design/
 * 17-apps-and-branches.md section 3.2), from {@code jabiz.web.native-apps}, at the host's root where the systems look
 * for them: {@value #APPLE} lets an iOS app open the SPA's addresses as Universal Links (only that SPA's path), and
 * {@value #ANDROID} lets an Android app open them as App Links or show them in a trusted web activity. Public, JSON,
 * never redirected (the systems refuse that), cached for an hour; 404 when no app of that system is configured.
 */
@RestController
public class NativeAppAssociations {

    public static final String APPLE = "/.well-known/apple-app-site-association";
    public static final String ANDROID = "/.well-known/assetlinks.json";
    /** Answered here, never by an SPA's routes. */
    static final Set<String> PATHS = Set.of(APPLE, ANDROID);
    static final Duration CACHE = Duration.ofHours(1);

    private final JabizWebProperties properties;

    NativeAppAssociations(JabizWebProperties properties) {
        this.properties = properties;
    }

    /**
     * Apple's format: one entry per SPA with iOS apps, its apps opening the addresses under its path only (all of
     * them for an SPA at {@code /}).
     */
    @GetMapping(APPLE)
    ResponseEntity<Object> apple() {
        List<Map<String, Object>> details = new ArrayList<>();
        for (JabizWebProperties.NativeApp app : properties.nativeApps()) {
            if (!app.ios().isEmpty()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("appIDs", app.ios());
                detail.put("components", List.of(Map.of("/", pattern(app.path()))));
                details.add(detail);
            }
        }
        return details.isEmpty() ? ResponseEntity.notFound().build()
            : json(Map.of("applinks", Map.of("details", details)));
    }

    /** Digital Asset Links: every Android app may handle this site's links (its manifest says which). */
    @GetMapping(ANDROID)
    ResponseEntity<Object> android() {
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
        return statements.isEmpty() ? ResponseEntity.notFound().build() : json(statements);
    }

    /** The addresses of an SPA as Apple matches them: its prefix and everything under it. */
    static String pattern(String path) {
        return "/".equals(path) ? "/*" : path + "/*";
    }

    private static ResponseEntity<Object> json(Object body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.maxAge(CACHE).cachePublic()).body(body);
    }
}

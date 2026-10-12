package com.jabiz.runtime.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The single-page applications served by this application (docs/design/17-apps-and-branches.md section 3.2):
 * {@code jabiz.web.spa[i].path}, {@code .index}, {@code .content-security-policy}, {@code .entry} and {@code .pwa}.
 * The build writes the SPAs of {@code jabizApp { spa(...) }} into the jar ({@code META-INF/jabiz-app.properties}) as the
 * lowest-priority default; an application's own {@code jabiz.web.spa} replaces that list as a whole. Without either,
 * the admin frontend is served at {@code /} as before. {@link SpaConfigCheck} reports invalid entries at startup.
 */
@ConfigurationProperties("jabiz.web")
public record JabizWebProperties(List<Spa> spa) {

    /**
     * The policy of every SPA that sets none (decision D43 item 7). Inline styles stay allowed (Radix's scroll lock and
     * the toasts insert {@code <style>}); images may come from blobs and data URLs (previews); scripts, workers and the
     * manifest only from the origin, never inline; no plugins, no foreign base URL or form target, and no page may
     * frame it. {@code connect-src} falls back to {@code default-src 'self'}.
     */
    public static final String DEFAULT_CONTENT_SECURITY_POLICY =
        "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; "
            + "base-uri 'self'; object-src 'none'; form-action 'self'; worker-src 'self'; manifest-src 'self'";

    /** The default policy under its earlier name: the admin frontend's, which every SPA now has by default. */
    public static final String ADMIN_CONTENT_SECURITY_POLICY = DEFAULT_CONTENT_SECURITY_POLICY;

    /** The Service Worker script of an SPA with a PWA, under its prefix (decision D43 item 6). */
    public static final String SERVICE_WORKER = "sw.js";
    /** The web app manifest of an SPA with a PWA, under its prefix. */
    public static final String MANIFEST = "manifest.webmanifest";
    /** The directory of an SPA's content-hashed files (Vite's {@code assets/}). */
    public static final String ASSETS = "assets/";

    public JabizWebProperties {
        spa = spa == null || spa.isEmpty()
            ? List.of(new Spa("/", null, null))
            : List.copyOf(spa.stream().map(s -> s == null ? new Spa(null, null, null) : s).toList());
    }

    /** The SPA that owns a request path: the longest prefix that matches whole path segments. */
    public Optional<Spa> spaFor(String requestPath) {
        return spa.stream()
            .filter(s -> s.path() != null && s.owns(requestPath))
            .max(Comparator.comparingInt(s -> s.path().length()));
    }

    /**
     * Whether a request path is the Service Worker script of an SPA that declares a PWA ({@code ON} or
     * {@code REMOVE}): the only answer to a request that says it fetches one ({@code Service-Worker: script}).
     */
    public boolean serviceWorkerScript(String requestPath) {
        return spa.stream().anyMatch(s -> s.path() != null && s.pwa() != Pwa.OFF
            && requestPath.equals(s.base() + SERVICE_WORKER));
    }

    /** Whether an SPA is a progressive web app (decision D43 item 6). */
    public enum Pwa {
        /** No manifest, no Service Worker (the default). */
        OFF,
        /** A manifest and a Service Worker that caches the application's shell. */
        ON,
        /** A Service Worker that unregisters itself and deletes its caches: how an installed PWA is taken back. */
        REMOVE;

        /** As written in configuration and by the build: {@code off}, {@code on}, {@code remove}. */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One SPA.
     *
     * @param path                  its URL prefix: {@code /} or like {@code /admin}
     * @param index                 the page served for its client-side routes; defaults to {@code <path>/index.html}
     * @param contentSecurityPolicy the {@code Content-Security-Policy} of its responses; defaults to
     *                              {@link #DEFAULT_CONTENT_SECURITY_POLICY}
     * @param entry                 the sign-in entry it signs in to (decision D43 item 1); none for a site without
     *                              sign-in
     * @param pwa                   whether it is a progressive web app; {@link Pwa#OFF} by default
     */
    public record Spa(String path, String index, String contentSecurityPolicy, String entry, Pwa pwa) {

        @ConstructorBinding
        public Spa {
            if (index == null && path != null) {
                index = ("/".equals(path) ? "" : path) + "/index.html";
            }
            if (contentSecurityPolicy == null) {
                contentSecurityPolicy = DEFAULT_CONTENT_SECURITY_POLICY;
            }
            if (entry != null && entry.isBlank()) {
                entry = null;
            }
            if (pwa == null) {
                pwa = Pwa.OFF;
            }
        }

        /** An SPA without an entry and without a PWA. */
        public Spa(String path, String index, String contentSecurityPolicy) {
            this(path, index, contentSecurityPolicy, null, null);
        }

        boolean owns(String requestPath) {
            return "/".equals(path) || requestPath.equals(path) || requestPath.startsWith(path + "/");
        }

        /** The prefix with a trailing slash ({@code /}, {@code /portal/}): Vite's base and the PWA's scope. */
        public String base() {
            return "/".equals(path) ? "/" : path + "/";
        }
    }

    /** Every problem of the configuration, as {@code "location: message"} texts; empty when it is valid. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < spa.size(); i++) {
            Spa s = spa.get(i);
            String where = "jabiz.web.spa[" + i + "]";
            boolean validPath = s.path() != null && PATH.matcher(s.path()).matches();
            if (!validPath) {
                problems.add(where + ".path: must be \"/\" or like \"/admin\" (lower-case segments, no trailing "
                    + "slash), was " + quoted(s.path()));
            } else {
                if (seen.contains(s.path())) {
                    problems.add(where + ".path: " + quoted(s.path()) + " is configured more than once");
                }
                seen.add(s.path());
                if (SpaFallbackFilter.excluded(s.path())) {
                    problems.add(where + ".path: " + quoted(s.path()) + " lies under /api or /actuator, which are never "
                        + "single-page application routes");
                }
            }
            // A default index follows from the path, so it is only wrong when the path is.
            if (validPath && !INDEX.matcher(s.index()).matches()) {
                problems.add(where + ".index: must be an absolute path to an .html file, was " + quoted(s.index()));
            }
            if (s.contentSecurityPolicy().isBlank()
                || s.contentSecurityPolicy().chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                problems.add(where + ".content-security-policy: must be a non-empty single line");
            }
            if (s.entry() != null && !ENTRY.matcher(s.entry()).matches()) {
                problems.add(where + ".entry: must be the name of a sign-in entry (lower-case letters, digits, "
                    + "hyphens), was " + quoted(s.entry()));
            }
        }
        return problems;
    }

    private static String quoted(String value) {
        return value == null ? "nothing" : "\"" + value + "\"";
    }

    private static final Pattern PATH = Pattern.compile("/|(/[a-z0-9][a-z0-9-]*)+");
    private static final Pattern INDEX =
        Pattern.compile("(/[A-Za-z0-9][A-Za-z0-9._-]*)*/[A-Za-z0-9._-]+\\.html");
    private static final Pattern ENTRY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
}

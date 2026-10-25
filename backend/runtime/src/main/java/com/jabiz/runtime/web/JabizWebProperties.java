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
 *
 * <p>{@code jabiz.web.environment} (phase 16zd) names the system the pages run on: {@value #PRODUCTION} is the real
 * one; any other name is a test system, which every page shows in a banner so that nobody mistakes it for the real one.
 * Unset means {@value #TEST}: a system that does not say it is production never looks like it. The front ends read it
 * before signing in ({@code GET /api/auth/entries/{name}}).
 *
 * <p>{@code jabiz.web.root-redirect} (phase 16zf-a) sends a {@code GET} or {@code HEAD} of exactly {@code /} to another
 * path of the same host with a 302, before any SPA is looked up: a host whose front ends all live under prefixes
 * ({@code /taker/}, {@code /biz/}) names the one a bare address opens; the query string is kept. Unset, the root is
 * served as before (by the SPA at {@code /} when there is one).
 *
 * <p>{@code jabiz.web.redirects[i].from} / {@code .to} (phase 16zf-a) move a prefix for good: a {@code GET} or
 * {@code HEAD} of {@code from} or of a path under it is answered with a 301 to the same path under {@code to}, the
 * query string kept, before any SPA is looked up. This is how links that were printed or mailed before a front end
 * moved ({@code /p/<id>} → {@code /taker/p/<id>}) keep working; it is not a general rewriting facility.
 *
 * @param spa          the single-page applications served
 * @param environment  the system's name: {@value #PRODUCTION} or a test system's
 * @param rootRedirect the path a request for exactly {@code /} is redirected to; none by default
 * @param redirects    the prefixes moved for good, each to another prefix of this host; none by default
 */
@ConfigurationProperties("jabiz.web")
public record JabizWebProperties(List<Spa> spa, String environment, String rootRedirect, List<Redirect> redirects) {

    /** The environment of the real system: the only one the pages show no banner for. */
    public static final String PRODUCTION = "production";
    /** The environment when none is configured. */
    public static final String TEST = "test";

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

    @ConstructorBinding
    public JabizWebProperties {
        spa = spa == null || spa.isEmpty()
            ? List.of(new Spa("/", null, null))
            : List.copyOf(spa.stream().map(s -> s == null ? new Spa(null, null, null) : s).toList());
        environment = environment == null || environment.isBlank() ? TEST : environment.trim();
        rootRedirect = rootRedirect == null || rootRedirect.isBlank() ? null : rootRedirect.trim();
        redirects = redirects == null ? List.of()
            : List.copyOf(redirects.stream().map(r -> r == null ? new Redirect(null, null) : r).toList());
    }

    /** The SPAs, on a test system, without redirects. */
    public JabizWebProperties(List<Spa> spa) {
        this(spa, null, null, null);
    }

    /** The SPAs on the named system, without redirects. */
    public JabizWebProperties(List<Spa> spa, String environment) {
        this(spa, environment, null, null);
    }

    /** The SPAs with a root redirect, on a test system, without moved prefixes. */
    public JabizWebProperties(List<Spa> spa, String environment, String rootRedirect) {
        this(spa, environment, rootRedirect, null);
    }

    /**
     * The redirect a request path falls under, if any: the longest {@code from} that matches whole path segments.
     */
    public Optional<Redirect> redirectFor(String requestPath) {
        return redirects.stream()
            .filter(r -> r.from() != null && (requestPath.equals(r.from()) || requestPath.startsWith(r.from() + "/")))
            .max(Comparator.comparingInt(r -> r.from().length()));
    }

    /**
     * A prefix moved for good (phase 16zf-a).
     *
     * @param from the old prefix, like {@code /p} (no trailing slash; never {@code /})
     * @param to   the new prefix of this host, like {@code /taker/p}
     */
    public record Redirect(String from, String to) {

        @ConstructorBinding
        public Redirect {
            from = from == null || from.isBlank() ? null : from.trim();
            to = to == null || to.isBlank() ? null : to.trim();
        }

        /** The address a request path under {@link #from} moves to. */
        public String target(String requestPath) {
            return to + requestPath.substring(from.length());
        }
    }

    /** Whether this is the real system ({@value #PRODUCTION}, whatever its case). */
    public boolean production() {
        return PRODUCTION.equalsIgnoreCase(environment);
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
        if (!ENVIRONMENT.matcher(environment).matches()) {
            problems.add("jabiz.web.environment: must be \"" + PRODUCTION + "\" or a short name of a test system "
                + "(letters, digits, spaces, hyphens, up to 32 characters), was " + quoted(environment));
        }
        if (rootRedirect != null && (!ROOT_REDIRECT.matcher(rootRedirect).matches()
            || SpaFallbackFilter.excluded(rootRedirect))) {
            problems.add("jabiz.web.root-redirect: must be an absolute path of this host other than \"/\", like "
                + "\"/biz/\" (lower-case segments, no scheme or host, not under /api or /actuator), was "
                + quoted(rootRedirect));
        }
        List<String> moved = new ArrayList<>();
        for (int i = 0; i < redirects.size(); i++) {
            Redirect r = redirects.get(i);
            String where = "jabiz.web.redirects[" + i + "]";
            boolean validFrom = r.from() != null && !"/".equals(r.from()) && PATH.matcher(r.from()).matches()
                && !SpaFallbackFilter.excluded(r.from());
            if (!validFrom) {
                problems.add(where + ".from: must be a prefix like \"/p\" (lower-case segments, no trailing slash, not "
                    + "\"/\", not under /api or /actuator), was " + quoted(r.from()));
            } else if (moved.contains(r.from())) {
                problems.add(where + ".from: " + quoted(r.from()) + " is configured more than once");
            } else {
                moved.add(r.from());
            }
            boolean validTo = r.to() != null && PATH.matcher(r.to()).matches() && !"/".equals(r.to())
                && !SpaFallbackFilter.excluded(r.to());
            if (!validTo) {
                problems.add(where + ".to: must be a prefix of this host like \"/taker/p\" (lower-case segments, no "
                    + "trailing slash, no scheme or host, not under /api or /actuator), was " + quoted(r.to()));
            } else if (validFrom && (r.to().equals(r.from()) || r.to().startsWith(r.from() + "/"))) {
                problems.add(where + ".to: " + quoted(r.to()) + " lies under its own from " + quoted(r.from())
                    + ": the redirect would loop");
            }
        }
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
    /** A path of this host (so never a scheme, a host or {@code //}), with or without a trailing slash. */
    private static final Pattern ROOT_REDIRECT = Pattern.compile("(/[a-z0-9][a-z0-9._-]*)+/?");
    private static final Pattern INDEX =
        Pattern.compile("(/[A-Za-z0-9][A-Za-z0-9._-]*)*/[A-Za-z0-9._-]+\\.html");
    private static final Pattern ENTRY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    /** A system's name as the banner shows it: a word or a few, nothing that could be markup. */
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 -]{0,31}");
}

package com.jabiz.runtime.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The single-page applications served by this application (docs/design/17-apps-and-branches.md section 3.2):
 * {@code jabiz.web.spa[i].path}, {@code .index} and {@code .content-security-policy}. Without any, the admin
 * frontend is served at {@code /} as before. {@link SpaConfigCheck} reports invalid entries at startup.
 */
@ConfigurationProperties("jabiz.web")
public record JabizWebProperties(List<Spa> spa) {

    /**
     * The admin frontend's policy. antd's CSS-in-JS needs inline styles; images may come from blobs and data URLs
     * (previews); nothing else leaves the origin and no page may frame it.
     */
    public static final String ADMIN_CONTENT_SECURITY_POLICY =
        "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'";

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
     * One SPA.
     *
     * @param path                  its URL prefix: {@code /} or like {@code /admin}
     * @param index                 the page served for its client-side routes; defaults to {@code <path>/index.html}
     * @param contentSecurityPolicy the {@code Content-Security-Policy} of its responses; defaults to
     *                              {@link #ADMIN_CONTENT_SECURITY_POLICY}
     */
    public record Spa(String path, String index, String contentSecurityPolicy) {

        public Spa {
            if (index == null && path != null) {
                index = ("/".equals(path) ? "" : path) + "/index.html";
            }
            if (contentSecurityPolicy == null) {
                contentSecurityPolicy = ADMIN_CONTENT_SECURITY_POLICY;
            }
        }

        boolean owns(String requestPath) {
            return "/".equals(path) || requestPath.equals(path) || requestPath.startsWith(path + "/");
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
        }
        return problems;
    }

    private static String quoted(String value) {
        return value == null ? "nothing" : "\"" + value + "\"";
    }

    private static final Pattern PATH = Pattern.compile("/|(/[a-z0-9][a-z0-9-]*)+");
    private static final Pattern INDEX =
        Pattern.compile("(/[A-Za-z0-9][A-Za-z0-9._-]*)*/[A-Za-z0-9._-]+\\.html");
}

package com.jabiz.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Whether a {@link MailLayout} is acceptable (decision D72 items 1 and 2), every problem at once. One rule set for the
 * defaults in configuration ({@code jabiz.mail.layout.*}, the startup check) and the layouts administrators write
 * (the controlled change {@code mail-layout}, the delivery, which falls back to the default rather than send a layout
 * that fails them):
 * <ol>
 *   <li>the display name and the logo's alternative text are one line each, within
 *       {@value #MAX_LINE} characters ({@value PlatformErrorCodes#MAIL_LAYOUT_LINE}); a logo has an alternative text
 *       ({@value PlatformErrorCodes#MAIL_LAYOUT_LOGO_ALT});</li>
 *   <li>the logo is the application's own: {@code {baseUrl}/…} or a path starting with a single {@code /}, without
 *       other placeholders, spaces or backslashes ({@value PlatformErrorCodes#MAIL_LAYOUT_LOGO}): no image from
 *       another host, which could track who opens a mail;</li>
 *   <li>the footer is within {@link Limits#maxFooterLength()}; it uses only {@code {baseUrl}} and
 *       {@code {entryUrl.<entry>}}: tokens, the sign-in link and the unsubscribe link belong to each mail
 *       ({@value PlatformErrorCodes#MAIL_TEXT_UNKNOWN_PLACEHOLDER}); its links, images and HTML follow the rules of
 *       the texts ({@link MailTextRules} items 5 and 6).</li>
 * </ol>
 */
public final class MailLayoutRules {

    /** Longest display name and alternative text. */
    public static final int MAX_LINE = 200;
    /** {@code jabiz.mail.layout.max-footer-length} when not configured. */
    public static final int DEFAULT_MAX_FOOTER = 2_000;

    /** Fields of the violations. */
    public static final String DISPLAY_NAME = "displayName";
    public static final String LOGO_URL = "logoUrl";
    public static final String LOGO_ALT = "logoAlt";
    public static final String FOOTER = "footer";

    /**
     * @param maxFooterLength longest footer, in characters, as written
     * @param links           the hosts links may lead to ({@link MailTextRules.Limits}; its lengths are not used)
     */
    public record Limits(int maxFooterLength, MailTextRules.Limits links) {

        public static final Limits DEFAULT = new Limits(DEFAULT_MAX_FOOTER, MailTextRules.Limits.DEFAULT);

        public Limits {
            if (maxFooterLength <= 0) {
                throw new IllegalArgumentException("The longest footer must be positive");
            }
            Objects.requireNonNull(links, "links must not be null");
        }
    }

    private MailLayoutRules() {}

    /** The problems of {@code layout}; empty when mail may be sent on it. */
    public static List<Violation> check(MailLayout layout, Limits limits) {
        Objects.requireNonNull(layout, "layout must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        List<Violation> problems = new ArrayList<>();
        line(DISPLAY_NAME, layout.displayName(), problems);
        line(LOGO_ALT, layout.logoAlt(), problems);
        String logo = layout.logoUrl().strip();
        if (!logo.isEmpty()) {
            if (!siteImage(logo)) {
                problems.add(new Violation(LOGO_URL, PlatformErrorCodes.MAIL_LAYOUT_LOGO, "The logo " + logo
                    + " is not the application's own: give {" + MailTemplate.BASE_URL + "}/… or a path such as "
                    + "/api/public/files/…", Map.of("url", logo)));
            }
            if (layout.logoAlt().isBlank()) {
                problems.add(new Violation(LOGO_ALT, PlatformErrorCodes.MAIL_LAYOUT_LOGO_ALT,
                    "A logo needs an alternative text", Map.of()));
            }
        }
        String footer = layout.footer();
        if (footer.codePointCount(0, footer.length()) > limits.maxFooterLength()) {
            problems.add(new Violation(FOOTER, PlatformErrorCodes.MAIL_TEXT_TOO_LONG, "The footer is longer than "
                + limits.maxFooterLength() + " characters", Map.of("limit", limits.maxFooterLength())));
        }
        for (String name : MailPlaceholders.of(footer)) {
            if (!MailTemplate.BASE_URL.equals(name) && !MailTemplate.namedEntryUrl(name)) {
                problems.add(new Violation(FOOTER, PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER, "The footer "
                    + "uses {" + name + "}; it may use only {" + MailTemplate.BASE_URL + "} and {"
                    + MailTemplate.NAMED_ENTRY_URL_PREFIX + "<entry>}", Map.of("placeholder", name)));
            }
        }
        problems.addAll(MailTextRules.markdown(FOOTER, footer, Set.of(), limits.links()));
        return List.copyOf(problems);
    }

    /** The problems as one line each, for the startup check and logs. */
    public static List<String> describe(List<Violation> problems) {
        return MailTextRules.describe(problems);
    }

    private static void line(String field, String value, List<Violation> problems) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
            || value.codePointCount(0, value.length()) > MAX_LINE) {
            problems.add(new Violation(field, PlatformErrorCodes.MAIL_LAYOUT_LINE, "The " + field
                + " is more than one line or longer than " + MAX_LINE + " characters", Map.of("limit", MAX_LINE)));
        }
    }

    /**
     * Whether a logo address is the application's own: {@code {baseUrl}} followed by a path, or a path of the
     * application; never another host ({@code //host}, {@code {baseUrl}.evil}), another placeholder, a space or a
     * backslash.
     */
    static boolean siteImage(String url) {
        if (url.chars().anyMatch(c -> c <= 0x20 || c == 0x7f || c == '\\')) {
            return false;
        }
        String path = url;
        Matcher placeholder = MailPlaceholders.PLACEHOLDER.matcher(url);
        if (placeholder.lookingAt()) {
            if (!MailTemplate.BASE_URL.equals(placeholder.group(1))) {
                return false;
            }
            path = url.substring(placeholder.end());
        }
        return path.startsWith("/") && !path.startsWith("//") && path.indexOf('{') < 0 && path.indexOf('}') < 0;
    }
}

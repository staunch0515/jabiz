package com.jabiz.file;

import com.jabiz.i18n.PlatformErrorCodes;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A link to an image the server is to download once (decision D75), checked before anything is resolved or sent:
 * {@code https} only; no user name or password in it; a port of {@code allowedPorts} (by default 443 only); a host that
 * is a DNS name of at least two labels whose last label is not all digits (so {@code 127.1}, {@code 2130706433} and
 * {@code 0177.0.0.1}, which resolvers read as IPv4 addresses, are refused), a dotted-quad IPv4 address or a bracketed
 * IPv6 address without a zone. Internationalized names are converted to their ASCII form. The fragment is dropped
 * (never sent); a backslash, a space or a control character anywhere refuses the link, since clients disagree on
 * what they mean.
 *
 * <p>Whether the host's addresses may be connected to is decided later, on every hop, by
 * {@link com.jabiz.net.PublicAddresses}. {@link #toString()} and {@link #shown()} never include the query: links
 * signed for one download often carry their credentials there.
 *
 * @param host          the host in ASCII lower case, without brackets for an IPv6 address
 * @param port          the port connected to
 * @param requestTarget what the request line asks for: the path ({@code /} when empty) and the query, ASCII
 * @param literal       whether the host is an IP address rather than a name
 */
public record RemoteImageLink(String host, int port, String requestTarget, boolean literal) {

    /** The longest link accepted, in characters. */
    public static final int MAX_LENGTH = 2048;

    /** The port of https. */
    public static final int HTTPS_PORT = 443;

    /** Why a link is refused: the error code (decision D75) and a message without the link. */
    public static final class Refused extends RuntimeException {

        private final String code;

        public Refused(String code, String message) {
            super(message, null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    /** Not https. */
    public static final String NOT_HTTPS = PlatformErrorCodes.FILE_FETCH_NOT_HTTPS;
    /** Not a link the server follows: malformed, with credentials, an unusual host or port. */
    public static final String LINK_INVALID = PlatformErrorCodes.FILE_FETCH_LINK_INVALID;

    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern DOTTED_QUAD = Pattern.compile(
        "(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-f:.]{2,45}");

    public RemoteImageLink {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(requestTarget, "requestTarget");
        // Built by parse; anything else must not smuggle a header or a second request line through.
        if (host.isEmpty() || !host.chars().allMatch(c -> c > 0x20 && c < 0x7f && c != '/' && c != '@')
            || !requestTarget.startsWith("/") || !requestTarget.chars().allMatch(c -> c > 0x20 && c < 0x7f)
            || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Not a checked link");
        }
    }

    /**
     * The link {@code text} names, or {@link Refused} with {@value #NOT_HTTPS} or {@value #LINK_INVALID}.
     *
     * @param allowedPorts the ports a link may name; an empty set allows 443 only
     */
    public static RemoteImageLink parse(String text, Set<Integer> allowedPorts) {
        if (text == null || text.isBlank()) {
            throw invalid("The link is empty");
        }
        String link = text.strip();
        if (link.length() > MAX_LENGTH) {
            throw invalid("The link is longer than " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < link.length(); i++) {
            char c = link.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '\\' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw invalid("The link holds a space, a backslash or a control character");
            }
        }
        int colon = link.indexOf(':');
        if (colon <= 0 || !SCHEME.matcher(link.substring(0, colon)).matches()) {
            throw invalid("The link is not an absolute link");
        }
        if (!link.substring(0, colon).equalsIgnoreCase("https")) {
            throw new Refused(NOT_HTTPS, "Only https links are followed");
        }
        if (!link.startsWith("//", colon + 1)) {
            throw invalid("The link has no host");
        }
        int start = colon + 3;
        int end = start;
        while (end < link.length() && "/?#".indexOf(link.charAt(end)) < 0) {
            end++;
        }
        String authority = link.substring(start, end);
        if (authority.indexOf('@') >= 0) {
            throw invalid("The link carries a user name or password");
        }
        String hostPart;
        String portPart = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw invalid("The link's IPv6 address is not closed");
            }
            hostPart = authority.substring(0, close + 1);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) {
                    throw invalid("The link's host is malformed");
                }
                portPart = after.substring(1);
            }
        } else {
            int portColon = authority.lastIndexOf(':');
            hostPart = portColon < 0 ? authority : authority.substring(0, portColon);
            portPart = portColon < 0 ? null : authority.substring(portColon + 1);
        }
        int port = port(portPart, allowedPorts);
        Host host = host(hostPart);
        String rest = link.substring(end);
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        URI uri;
        try {
            uri = new URI("https://" + host.inUri() + (port == HTTPS_PORT ? "" : ":" + port) + rest);
        } catch (URISyntaxException e) {
            throw invalid("The link's path or query is malformed");
        }
        // Characters outside ASCII in the path or query are sent percent-encoded (UTF-8).
        URI ascii = URI.create(uri.toASCIIString());
        String path = ascii.getRawPath() == null || ascii.getRawPath().isEmpty() ? "/" : ascii.getRawPath();
        String target = ascii.getRawQuery() == null ? path : path + "?" + ascii.getRawQuery();
        return new RemoteImageLink(host.name(), port, target, host.literal());
    }

    /**
     * The link a redirect's {@code Location} names, relative to this one, checked as {@link #parse} checks a link
     * (https only, the ports allowed ...).
     */
    public RemoteImageLink redirect(String location, Set<Integer> allowedPorts) {
        if (location == null || location.isBlank()) {
            throw invalid("The redirect names no location");
        }
        URI next;
        try {
            next = uri().resolve(new URI(location.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw invalid("The redirect's location is malformed");
        }
        return parse(next.toString(), allowedPorts);
    }

    /** The link as sent, query included: for the request only, never for records or logs. */
    public URI uri() {
        return URI.create("https://" + hostInUri() + (port == HTTPS_PORT ? "" : ":" + port) + requestTarget);
    }

    /** The value of the {@code Host} request header. */
    public String hostHeader() {
        return hostInUri() + (port == HTTPS_PORT ? "" : ":" + port);
    }

    /** The link without its query: what records and messages may show. */
    public String shown() {
        int query = requestTarget.indexOf('?');
        String path = query < 0 ? requestTarget : requestTarget.substring(0, query) + "?…";
        return "https://" + hostInUri() + (port == HTTPS_PORT ? "" : ":" + port) + path;
    }

    /**
     * {@code text} as records may show a link: without user information, query and fragment, the rest as given; not a
     * link at all, {@code ***}. For input summaries of processes that take links ({@code MaskedLink}).
     */
    public static String shown(String text) {
        if (text == null) {
            return null;
        }
        try {
            URI uri = new URI(text.strip());
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return "***";
            }
            String authority = uri.getRawAuthority();
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                authority = authority.substring(at + 1);
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return uri.getScheme() + "://" + authority + path + (uri.getRawQuery() == null ? "" : "?…");
        } catch (URISyntaxException e) {
            return "***";
        }
    }

    @Override
    public String toString() {
        return "RemoteImageLink[" + shown() + "]";
    }

    private String hostInUri() {
        return literal && host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private record Host(String name, boolean literal) {
        String inUri() {
            return literal && name.indexOf(':') >= 0 ? "[" + name + "]" : name;
        }
    }

    private static int port(String text, Set<Integer> allowedPorts) {
        int port = HTTPS_PORT;
        if (text != null) {
            if (text.isEmpty() || text.length() > 5 || !DIGITS.matcher(text).matches()) {
                throw invalid("The link's port is malformed");
            }
            port = Integer.parseInt(text);
        }
        Set<Integer> allowed = allowedPorts == null || allowedPorts.isEmpty() ? Set.of(HTTPS_PORT) : allowedPorts;
        if (!allowed.contains(port)) {
            throw invalid("The link names port " + port + ", which is not followed");
        }
        return port;
    }

    private static Host host(String text) {
        if (text.isEmpty()) {
            throw invalid("The link has no host");
        }
        if (text.startsWith("[")) {
            String address = text.substring(1, text.length() - 1).toLowerCase(Locale.ROOT);
            // No zone (fe80::1%eth0) and nothing but hex digits, colons and dots: never a name to look up.
            if (!IPV6.matcher(address).matches() || address.indexOf(':') < 0) {
                throw invalid("The link's IPv6 address is malformed");
            }
            return new Host(address, true);
        }
        String name = text;
        if (!name.chars().allMatch(c -> c < 0x80)) {
            try {
                name = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES);
            } catch (IllegalArgumentException e) {
                throw invalid("The link's host is not a valid name");
            }
        }
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (DOTTED_QUAD.matcher(name).matches()) {
            return new Host(name, true);
        }
        if (name.isEmpty() || name.length() > 253) {
            throw invalid("The link's host is not a valid name");
        }
        String[] labels = name.split("\\.", -1);
        if (labels.length < 2) {
            throw invalid("The link's host is not a public name");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw invalid("The link's host is not a valid name");
            }
        }
        if (DIGITS.matcher(labels[labels.length - 1]).matches()) {
            // 127.1, 0177.0.0.1, 1.2.3.04: what resolvers would read as an IPv4 address in another notation.
            throw invalid("The link's host is an address in an unusual notation");
        }
        return new Host(name, false);
    }

    private static Refused invalid(String message) {
        return new Refused(LINK_INVALID, message);
    }
}

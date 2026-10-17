package com.jabiz.runtime.web;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The address of the client of a request (docs/design/10-security.md section 15; decision D36 item 7), for login
 * records and the rate limit of the public API (docs/design/15-public-access.md section 6).
 *
 * <p>{@code X-Forwarded-For} is believed only from the proxies listed in {@code jabiz.security.trusted-proxies}
 * (addresses or CIDR blocks): when the connection comes from one of them, the header is read from right to left,
 * skipping trusted addresses, and the first untrusted one is the client (all trusted: the leftmost). Hops may carry a
 * port. Only hops the walk reaches are parsed; a malformed one reached ends it at the last valid hop seen (see
 * {@link #walk}). A connection from anywhere else is the client itself, whatever headers it sends. Without trusted
 * proxies the connection's address is taken as before (which Spring's {@code server.forward-headers-strategy} may
 * have rewritten already); configuring both is a startup error, since the headers would be applied twice and could
 * then be forged.
 */
@Component
public class ClientAddresses implements PlatformCheck {

    static final String PROPERTY = "jabiz.security.trusted-proxies";
    static final String FORWARD_HEADERS_STRATEGY = "server.forward-headers-strategy";
    public static final String FORWARDED_FOR = "X-Forwarded-For";
    static final String UNKNOWN = "unknown";

    /** At most this many trusted hops are walked; the client is then the last of them. */
    private static final int MAX_HOPS = 20;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    /** A block of addresses: {@code prefix} leading bits of {@code network}. */
    record Block(byte[] network, int prefix) {
        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (bytes[full] & mask) == (network[full] & mask);
        }
    }

    private final List<String> configured;
    private final String forwardHeadersStrategy;
    private final boolean dev;
    private final List<Block> trusted = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    @Autowired
    public ClientAddresses(Environment environment) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.listOf(String.class)).orElse(List.of()),
            environment.getProperty(FORWARD_HEADERS_STRATEGY), environment.acceptsProfiles(Profiles.of("dev")));
    }

    ClientAddresses(List<String> trustedProxies, String forwardHeadersStrategy) {
        this(trustedProxies, forwardHeadersStrategy, false);
    }

    /** @param dev whether the {@code dev} profile is active: Spring's forwarded-header handling only warns there */
    ClientAddresses(List<String> trustedProxies, String forwardHeadersStrategy, boolean dev) {
        this.dev = dev;
        this.configured = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        this.forwardHeadersStrategy = forwardHeadersStrategy;
        for (String entry : configured) {
            try {
                trusted.add(block(entry));
            } catch (IllegalArgumentException e) {
                problems.add("'" + entry + "' is not an address or CIDR block: " + e.getMessage());
            }
        }
    }

    /** The client's address as text; {@value #UNKNOWN} when the connection has none (a key for rate limits). */
    public String clientOf(ServerHttpRequest request) {
        String address = addressOf(request);
        return address == null ? UNKNOWN : address;
    }

    /** The client's address as text, or null when the connection has none (as login records keep it). */
    public String addressOf(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        InetAddress connection = remote == null ? null : remote.getAddress();
        if (connection == null) {
            return remote == null ? null : remote.getHostString();
        }
        if (trusted.isEmpty() || !trusted(connection)) {
            return text(connection);
        }
        List<String> headers;
        try {
            headers = request.getHeaders().getOrEmpty(FORWARDED_FOR);
        } catch (RuntimeException rejected) {
            // A value the firewall refuses (control characters): malformed, so ignored.
            return text(connection);
        }
        return text(walk(hops(headers), connection));
    }

    /**
     * The client among the hops (left to right, as the proxies appended them), reached through the trusted proxy
     * {@code connection}: walking from the right, trusted hops are skipped and the first untrusted one is the client.
     * Only what the walk reaches counts: whatever the client wrote left of its own address is never parsed. A malformed
     * hop that is reached ends the walk, as does the {@value #MAX_HOPS}th trusted hop; the client is then the last valid
     * hop seen (the connection if none). A chain of trusted hops only: its leftmost.
     */
    private InetAddress walk(List<String> hops, InetAddress connection) {
        InetAddress last = connection;
        int walked = 0;
        for (int i = hops.size() - 1; i >= 0 && walked < MAX_HOPS; i--) {
            InetAddress hop = hop(hops.get(i));
            if (hop == null) {
                return last;
            }
            if (!trusted(hop)) {
                return hop;
            }
            last = hop;
            walked++;
        }
        return last;
    }

    /**
     * The network a client address stands for in per-address rate limits: an IPv6 address's /64 (one subscriber
     * usually has a whole /64, so counting single addresses would let it rotate through them), any other text as it
     * is. {@code 2001:db8:1:2:a:b:c:d} → {@code 2001:db8:1:2::/64}. A link-local address with a scope
     * ({@code fe80::1%eth0}) counts without its scope, by its /64 as well. A request without an address counts as
     * {@code unknown}: all such requests share one key (in practice only in tests and behind misconfigured proxies).
     */
    public static String rateLimitKey(String client) {
        if (client == null || client.indexOf(':') < 0) {
            return client;
        }
        InetAddress address = literal(withoutScope(client));
        if (!(address instanceof java.net.Inet6Address)) {
            return address == null ? client : address.getHostAddress();
        }
        return prefix(address.getAddress(), 4) + ":/64";
    }

    /**
     * The site an IPv6 client address belongs to, its /48, for a second, looser per-network limit (decision D47); null
     * for anything that is not an IPv6 address. {@code 2001:db8:1:2::9} → {@code 2001:db8:1::/48}.
     */
    public static String networkKey(String client) {
        if (client == null || client.indexOf(':') < 0) {
            return null;
        }
        InetAddress address = literal(withoutScope(client));
        return address instanceof java.net.Inet6Address ? prefix(address.getAddress(), 3) + ":/48" : null;
    }

    private static String withoutScope(String client) {
        int percent = client.indexOf('%');
        return percent < 0 ? client : client.substring(0, percent);
    }

    /** The first {@code groups} 16-bit groups of an IPv6 address, each followed by a colon. */
    private static String prefix(byte[] bytes, int groups) {
        StringBuilder network = new StringBuilder();
        for (int i = 0; i < groups * 2; i += 2) {
            network.append(Integer.toHexString(((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF))).append(':');
        }
        return network.toString();
    }

    /** Whether any proxy is trusted. */
    public boolean trustsProxies() {
        return !trusted.isEmpty();
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> found = new ArrayList<>();
        problems.forEach(problem -> found.add(CheckProblem.error("SECURITY", PROPERTY, problem)));
        boolean springForwarding = forwardHeadersStrategy != null && !forwardHeadersStrategy.isBlank()
            && !"none".equalsIgnoreCase(forwardHeadersStrategy.trim());
        if (springForwarding && !configured.isEmpty()) {
            found.add(CheckProblem.error("SECURITY", PROPERTY, PROPERTY + " and " + FORWARD_HEADERS_STRATEGY + "="
                + forwardHeadersStrategy + " are both set: forwarded headers would be applied twice and could be "
                + "forged; keep only " + PROPERTY));
        } else if (springForwarding) {
            // Spring believes the headers of every connection, so any client could name its own address and escape
            // the per-address limits (decision D47); only the trusted proxies' headers may count.
            String message = FORWARD_HEADERS_STRATEGY + "=" + forwardHeadersStrategy + " believes forwarding headers "
                + "from every client, which could then forge its address for login records and rate limits; list the "
                + "proxies in " + PROPERTY + " and leave " + FORWARD_HEADERS_STRATEGY + " unset (or none)";
            found.add(dev ? CheckProblem.warning("SECURITY", FORWARD_HEADERS_STRATEGY, message)
                : CheckProblem.error("SECURITY", FORWARD_HEADERS_STRATEGY, message));
        }
        return found;
    }

    private boolean trusted(InetAddress address) {
        return trusted.stream().anyMatch(block -> block.contains(address));
    }

    /** The hops of the header(s) from left to right, unparsed: several headers count as one list, in order. */
    private static List<String> hops(List<String> headers) {
        List<String> hops = new ArrayList<>();
        for (String header : headers) {
            for (String part : header.split(",", -1)) {
                hops.add(part.trim());
            }
        }
        return hops;
    }

    /**
     * One hop: an address, or an address with a port as some proxies write it ({@code 192.0.2.1:443},
     * {@code [2001:db8::1]:443}; the port is dropped). Null for anything else.
     */
    static InetAddress hop(String text) {
        if (text == null) {
            return null;
        }
        String value = text;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0 || !(close == value.length() - 1 || port(value.substring(close + 1)))) {
                return null;
            }
            value = value.substring(0, close + 1);
        } else if (value.indexOf(':') == value.lastIndexOf(':') && value.indexOf(':') > 0) {
            // One colon: IPv4 with a port (bare IPv6 has at least two).
            int colon = value.indexOf(':');
            if (!port(value.substring(colon))) {
                return null;
            }
            value = value.substring(0, colon);
            if (!IPV4.matcher(value).matches()) {
                return null;
            }
        }
        return literal(value);
    }

    /** {@code :<1–65535>}. */
    private static boolean port(String text) {
        if (!text.matches(":\\d{1,5}")) {
            return false;
        }
        int port = Integer.parseInt(text.substring(1));
        return port >= 1 && port <= 65535;
    }

    /**
     * An IP address literal; null for anything else. Only literals reach {@link InetAddress#getByName}, which then
     * never asks DNS.
     */
    static InetAddress literal(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String value = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        boolean v4 = IPV4.matcher(value).matches();
        if (!v4 && !(value.indexOf(':') >= 0 && IPV6.matcher(value).matches())) {
            return null;
        }
        if (v4) {
            for (String octet : value.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    static Block block(String entry) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("empty");
        }
        String value = entry.trim();
        int slash = value.indexOf('/');
        InetAddress address = literal(slash < 0 ? value : value.substring(0, slash));
        if (address == null) {
            throw new IllegalArgumentException("not an IP address");
        }
        int bits = address.getAddress().length * 8;
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(value.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("the prefix length is not a number");
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("the prefix length must be 0 to " + bits);
            }
        }
        return new Block(address.getAddress(), prefix);
    }

    private static String text(InetAddress address) {
        return address.getHostAddress().toLowerCase(Locale.ROOT);
    }
}

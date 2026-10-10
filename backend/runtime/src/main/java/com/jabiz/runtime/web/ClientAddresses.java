package com.jabiz.runtime.web;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
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
 * skipping trusted addresses, and the first untrusted one is the client (all trusted: the leftmost). A connection
 * from anywhere else is the client itself, whatever headers it sends; a malformed header is ignored. Without trusted
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

    /** At most this many hops are read from the header; a longer chain is malformed. */
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
    private final List<Block> trusted = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    @Autowired
    public ClientAddresses(Environment environment) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.listOf(String.class)).orElse(List.of()),
            environment.getProperty(FORWARD_HEADERS_STRATEGY));
    }

    ClientAddresses(List<String> trustedProxies, String forwardHeadersStrategy) {
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
        List<InetAddress> chain = chain(headers);
        if (chain == null || chain.isEmpty()) {
            return text(connection);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (!trusted(chain.get(i))) {
                return text(chain.get(i));
            }
        }
        return text(chain.getFirst());
    }

    /** Whether any proxy is trusted. */
    public boolean trustsProxies() {
        return !trusted.isEmpty();
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> found = new ArrayList<>();
        problems.forEach(problem -> found.add(CheckProblem.error("SECURITY", PROPERTY, problem)));
        if (!configured.isEmpty() && forwardHeadersStrategy != null && !forwardHeadersStrategy.isBlank()
            && !"none".equalsIgnoreCase(forwardHeadersStrategy.trim())) {
            found.add(CheckProblem.error("SECURITY", PROPERTY, PROPERTY + " and " + FORWARD_HEADERS_STRATEGY + "="
                + forwardHeadersStrategy + " are both set: forwarded headers would be applied twice and could be "
                + "forged; keep only " + PROPERTY));
        }
        return found;
    }

    private boolean trusted(InetAddress address) {
        return trusted.stream().anyMatch(block -> block.contains(address));
    }

    /** The addresses of the header(s) from left to right; null when any part is malformed. */
    private static List<InetAddress> chain(List<String> headers) {
        List<InetAddress> chain = new ArrayList<>();
        for (String header : headers) {
            for (String part : header.split(",", -1)) {
                InetAddress address = literal(part.trim());
                if (address == null || chain.size() >= MAX_HOPS) {
                    return null;
                }
                chain.add(address);
            }
        }
        return chain;
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

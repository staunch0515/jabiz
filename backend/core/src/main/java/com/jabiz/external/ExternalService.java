package com.jabiz.external;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A service of another system that blocking steps may call (decision D38, docs/design/06-process.md section 2.3),
 * declared as a bean. Calls go through {@link ExternalServices#http} only, to the hosts named here only; the secrets
 * come from environment variables, read once at startup.
 *
 * <p>The declaration is not validated when it is built: the startup check {@code EXTERNAL} reports every problem of
 * every service at once ({@link #problems()} and the address rules of the runtime). The address and the hosts can
 * be replaced per environment ({@code jabiz.external-services.<name>.base-url | allowed-hosts}), so that tests and
 * development point at a local stub.
 *
 * @param name              {@value #NAME_PATTERN}, unique within the application
 * @param baseUrl           what relative request targets are resolved against (RFC 3986)
 * @param allowedHosts      the only hosts requests may go to (lower case); every request is checked
 * @param secrets           secret name to the environment variable holding it, in declaration order
 * @param connectTimeout    the longest wait for a connection
 * @param requestTimeout    the longest wait for a whole exchange, and the upper bound of a request's own timeout
 * @param idempotencyHeader the header that carries a request's idempotency key, or null when the provider takes none
 * @param maxResponseSize   the largest response body accepted, in bytes; a larger one fails the call
 * @param allowInDryRun     whether calls are made in a dry run too: only for read-only, idempotent calls
 */
public record ExternalService(String name, URI baseUrl, Set<String> allowedHosts, Map<String, String> secrets,
    Duration connectTimeout, Duration requestTimeout, String idempotencyHeader, long maxResponseSize,
    boolean allowInDryRun) {

    static final String NAME_PATTERN = "[a-z][a-z0-9-]{0,39}";
    public static final Pattern NAME = Pattern.compile(NAME_PATTERN);
    /** Environment variable names: upper case, digits and underscores. */
    public static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    /** Secret names, as {@link ExternalServices#secret} takes them. */
    public static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    /** Header names (RFC 9110 tokens, without the rarer characters). */
    public static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9!#$%&'*+.^_`|~-]*");
    /** Host names and bracketed IPv6 literals, lower case; no scheme, port or path. */
    static final Pattern HOST = Pattern.compile(
        "\\[[0-9a-f:.]+]|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** A blocking step holds its process's transaction while it waits; longer work is an external job (D38). */
    public static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration MAX_REQUEST_TIMEOUT = Duration.ofMinutes(2);
    public static final long DEFAULT_MAX_RESPONSE_SIZE = 1024 * 1024;
    public static final long MAX_RESPONSE_SIZE = 16 * 1024 * 1024;

    public ExternalService {
        allowedHosts = allowedHosts == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(allowedHosts));
        secrets = secrets == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(secrets));
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }

    public static ExternalService define(String name, Consumer<Builder> configuration) {
        Builder builder = new Builder(name);
        configuration.accept(builder);
        return builder.build();
    }

    /** Whether requests may go to {@code host} (case ignored). */
    public boolean allowsHost(String host) {
        return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * This service with the address and the hosts of the environment's configuration; what is null or empty there
     * keeps the declared value.
     */
    public ExternalService withOverrides(URI url, Collection<String> hosts) {
        return new ExternalService(name, url == null ? baseUrl : url,
            hosts == null || hosts.isEmpty() ? allowedHosts : normalise(hosts), secrets, connectTimeout,
            requestTimeout, idempotencyHeader, maxResponseSize, allowInDryRun);
    }

    /**
     * Every problem of the declaration that the core can tell, as {@code "location: message"}: the name, the hosts'
     * form, the secrets' names, the timeouts and the size limit. Whether the address is allowed is the runtime's
     * rule for every outbound address, checked with it.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) {
            problems.add("name: '" + name + "' must match " + NAME_PATTERN);
        }
        if (allowedHosts.isEmpty()) {
            problems.add("allowedHosts: name at least one host");
        }
        for (String host : allowedHosts) {
            if (!HOST.matcher(host).matches()) {
                problems.add("allowedHosts: '" + host + "' is not a host name");
            }
        }
        if (baseUrl == null) {
            problems.add("baseUrl: missing");
        }
        secrets.forEach((secret, variable) -> {
            String at = "secret " + (secret != null && SECRET_NAME.matcher(secret).matches() ? secret
                : "'" + secret + "'");
            if (secret == null || !SECRET_NAME.matcher(secret).matches()) {
                problems.add(at + ": name must match " + SECRET_NAME.pattern());
            }
            if (variable == null || !ENV_NAME.matcher(variable).matches()) {
                problems.add(at + ": environment variable '" + variable + "' must match " + ENV_NAME.pattern());
            }
        });
        if (!within(connectTimeout, MAX_CONNECT_TIMEOUT)) {
            problems.add("connectTimeout: must be positive and at most " + MAX_CONNECT_TIMEOUT);
        }
        if (!within(requestTimeout, MAX_REQUEST_TIMEOUT)) {
            problems.add("requestTimeout: must be positive and at most " + MAX_REQUEST_TIMEOUT);
        }
        if (idempotencyHeader != null && (!HEADER_NAME.matcher(idempotencyHeader).matches()
            || ExternalHttp.Request.isPlatformHeader(idempotencyHeader))) {
            problems.add("idempotencyHeader: '" + idempotencyHeader + "' is not a header name a request may set");
        }
        if (maxResponseSize <= 0 || maxResponseSize > MAX_RESPONSE_SIZE) {
            problems.add("maxResponseSize: must be between 1 and " + MAX_RESPONSE_SIZE + " bytes");
        }
        return List.copyOf(problems);
    }

    /** The declaration without the base address's credentials, query or fragment, and the secrets' names only. */
    @Override
    public String toString() {
        String url = baseUrl == null ? "null" : baseUrl.getScheme() + "://" + baseUrl.getHost()
            + (baseUrl.getPort() < 0 ? "" : ":" + baseUrl.getPort()) + (baseUrl.getRawPath() == null ? ""
            : baseUrl.getRawPath());
        return "ExternalService[name=" + name + ", baseUrl=" + url + ", allowedHosts=" + allowedHosts
            + ", secrets=" + secrets.keySet() + ", connectTimeout=" + connectTimeout + ", requestTimeout="
            + requestTimeout + ", idempotencyHeader=" + idempotencyHeader + ", maxResponseSize=" + maxResponseSize
            + ", allowInDryRun=" + allowInDryRun + "]";
    }

    private static boolean within(Duration duration, Duration max) {
        return duration.isPositive() && duration.compareTo(max) <= 0;
    }

    private static Set<String> normalise(Collection<String> hosts) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String host : hosts) {
            if (host != null && !host.isBlank()) {
                normalised.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalised;
    }

    public static final class Builder {
        private final String name;
        private URI baseUrl;
        private final Set<String> allowedHosts = new LinkedHashSet<>();
        private final Map<String, String> secrets = new LinkedHashMap<>();
        private Duration connectTimeout;
        private Duration requestTimeout;
        private String idempotencyHeader;
        private long maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE;
        private boolean allowInDryRun;

        private Builder(String name) {
            this.name = name;
        }

        /** @throws IllegalArgumentException when {@code url} is not a URI at all */
        public Builder baseUrl(String url) {
            this.baseUrl = URI.create(url);
            return this;
        }

        public Builder allowedHosts(String... hosts) {
            allowedHosts.addAll(normalise(List.of(hosts)));
            return this;
        }

        /** A secret read at startup from {@code environmentVariable}, for {@link ExternalServices#secret}. */
        public Builder secret(String secretName, String environmentVariable) {
            secrets.put(secretName, environmentVariable);
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        public Builder idempotencyHeader(String header) {
            this.idempotencyHeader = header;
            return this;
        }

        public Builder maxResponseSize(long bytes) {
            this.maxResponseSize = bytes;
            return this;
        }

        /** Calls are made in dry runs (an import's preview) too: declare it for read-only, idempotent calls only. */
        public Builder allowInDryRun() {
            this.allowInDryRun = true;
            return this;
        }

        public ExternalService build() {
            return new ExternalService(name, baseUrl, allowedHosts, secrets, connectTimeout, requestTimeout,
                idempotencyHeader, maxResponseSize, allowInDryRun);
        }
    }
}

package com.jabiz.security;

import com.jabiz.context.RequestContext;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Who runs a process through an app proxy (decision D60; docs/design/10-security.md section 15.13): nobody signed in,
 * but a request another platform forwarded and signed for one of its tenants, from an anonymous visitor of that tenant
 * or one the other platform says is its signed-in customer. A process declared {@code appProxy(proxy)} reads it with
 * {@link #of}; the tenant is the request's {@link RequestContext#tenantId()}, the customer (if any) is in the actor id.
 * The customer is the other platform's, not a user of this one, and identifies nobody here.
 *
 * @param proxy      the proxy's name ({@code jabiz.app-proxies.<proxy>})
 * @param tenant     the tenant the other platform forwarded for (a shop's address), checked against the proxy's pattern
 * @param customerId the other platform's id of its signed-in customer, or null for an anonymous visitor
 */
public record AppProxyCaller(String proxy, String tenant, String customerId) {

    /** A proxy's name: as the path segment it is. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    /** A customer id as it may be carried: letters, digits, '-' and '_'. */
    public static final Pattern CUSTOMER = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    static final String ACTOR_PREFIX = "app-proxy:";
    static final String PERMISSION_PREFIX = "app-proxy.";

    public AppProxyCaller {
        Objects.requireNonNull(proxy, "proxy must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
    }

    /** The permission only the callers of {@code proxy} hold: the one a process declared {@code appProxy(proxy)} has. */
    public static String permission(String proxy) {
        if (proxy == null || !NAME.matcher(proxy).matches()) {
            throw new IllegalArgumentException("Not an app proxy's name: " + proxy);
        }
        return PERMISSION_PREFIX + proxy;
    }

    /** The proxy a permission belongs to, if it is a proxy's. */
    public static Optional<String> proxyOf(String permission) {
        if (permission == null || !permission.startsWith(PERMISSION_PREFIX)) {
            return Optional.empty();
        }
        String proxy = permission.substring(PERMISSION_PREFIX.length());
        return NAME.matcher(proxy).matches() ? Optional.of(proxy) : Optional.empty();
    }

    /** The actor id the operation is recorded under: the proxy, and the customer when there is one. */
    public String actorId() {
        return ACTOR_PREFIX + proxy + (customerId == null ? "" : ":" + customerId);
    }

    /** The caller of a request run through an app proxy; empty for any other request. */
    public static Optional<AppProxyCaller> of(RequestContext request) {
        if (request == null || request.actorId() == null || request.tenantId() == null
            || !request.actorId().startsWith(ACTOR_PREFIX)) {
            return Optional.empty();
        }
        String rest = request.actorId().substring(ACTOR_PREFIX.length());
        int colon = rest.indexOf(':');
        String proxy = colon < 0 ? rest : rest.substring(0, colon);
        String customer = colon < 0 ? null : rest.substring(colon + 1);
        if (!NAME.matcher(proxy).matches() || customer != null && !CUSTOMER.matcher(customer).matches()
            || !request.permissions().contains(permission(proxy))) {
            return Optional.empty();
        }
        return Optional.of(new AppProxyCaller(proxy, request.tenantId(), customer));
    }
}

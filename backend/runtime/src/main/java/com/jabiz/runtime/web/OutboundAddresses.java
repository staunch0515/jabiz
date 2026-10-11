package com.jabiz.runtime.web;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * The one rule for every address the platform sends to (webhooks, decision D33; external services, decision D38):
 * absolute, https (http only to this machine), no credentials in the address, and a host on the caller's list of
 * allowed hosts (exact names, case ignored). Checked at startup and again before every request.
 */
public final class OutboundAddresses {

    /** The names of this machine, the only hosts plain http may go to. */
    public static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private OutboundAddresses() {}

    /**
     * Why the platform will not send to {@code url}, or null when it may.
     *
     * @param allowedHosts the hosts allowed, lower case
     * @param hostsSetting where the list is set, named in the refusal
     */
    public static String refusal(URI url, Collection<String> allowedHosts, String hostsSetting) {
        if (url == null || url.getHost() == null) {
            return "missing or not an absolute address";
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && LOOPBACK.contains(host))) {
            return "not https (http only to this machine): " + origin(url);
        }
        if (url.getRawUserInfo() != null) {
            return "carries credentials";
        }
        if (!allowedHosts.contains(host)) {
            return "host " + host + " is not in " + hostsSetting;
        }
        return null;
    }

    /**
     * Only the scheme, host and port of an address, for messages and logs: paths and queries often carry a token.
     */
    public static String origin(URI url) {
        if (url == null || url.getHost() == null) {
            return url == null ? "null" : "(not an absolute address)";
        }
        return url.getScheme() + "://" + url.getHost() + (url.getPort() < 0 ? "" : ":" + url.getPort());
    }
}

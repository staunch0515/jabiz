package com.jabiz.runtime.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Passkeys ({@code jabiz.security.passkeys}, decision D71; docs/design/10-security.md section 15.16): the relying
 * party a passkey is bound to ({@code rp-id}, a domain; changing it makes every passkey useless), the name the
 * system's dialog shows, the web origins whose pages may use them, how long a challenge lasts and how many passkeys a
 * user keeps. Entries turn passkeys on one by one ({@code jabiz.security.entries.<name>.passkeys}).
 *
 * @param rpId         the relying party: the host of every origin, or a domain above it
 * @param rpName       shown by the system's dialog; {@code rpId} when not set
 * @param origins      the pages' origins ({@code https://host[:port]}; {@code http://localhost} for development)
 * @param challengeTtl how long a challenge may be answered
 * @param maxPerUser   the most passkeys a user keeps
 */
@ConfigurationProperties("jabiz.security.passkeys")
public record PasskeyProperties(String rpId, String rpName, List<String> origins, Duration challengeTtl,
    Integer maxPerUser) {

    public static final Duration DEFAULT_CHALLENGE_TTL = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_PER_USER = 10;

    @ConstructorBinding
    public PasskeyProperties {
        rpId = rpId == null || rpId.isBlank() ? null : rpId.trim().toLowerCase(Locale.ROOT);
        rpName = rpName == null || rpName.isBlank() ? rpId : rpName.trim();
        origins = origins == null ? List.of() : origins.stream().filter(o -> o != null && !o.isBlank())
            .map(String::trim).toList();
        challengeTtl = challengeTtl == null ? DEFAULT_CHALLENGE_TTL : challengeTtl;
        maxPerUser = maxPerUser == null ? DEFAULT_MAX_PER_USER : maxPerUser;
    }

    /** No passkeys: nothing configured. */
    public static PasskeyProperties none() {
        return new PasskeyProperties(null, null, null, null, null);
    }

    /** Whether a relying party and its origins are set. */
    public boolean configured() {
        return rpId != null && !origins.isEmpty();
    }

    /** Whether anything is set at all (then it must be complete and right). */
    boolean partly() {
        return rpId != null || !origins.isEmpty();
    }

    /** What is wrong with the settings; empty when they are usable or not set at all. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (challengeTtl.compareTo(Duration.ofSeconds(30)) < 0 || challengeTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            problems.add("challenge-ttl must be between 30 seconds and 15 minutes, not " + challengeTtl);
        }
        if (maxPerUser < 1 || maxPerUser > 50) {
            problems.add("max-per-user must be between 1 and 50, not " + maxPerUser);
        }
        if (!partly()) {
            return problems;
        }
        if (rpId == null) {
            problems.add("rp-id is not set: it is the domain the passkeys are bound to");
        } else if (!rpId.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")) {
            problems.add("rp-id must be a domain such as example.com, not '" + rpId + "'");
        }
        if (origins.isEmpty()) {
            problems.add("origins is empty: no page could use a passkey");
        }
        for (String origin : origins) {
            String host = host(origin);
            if (host == null) {
                problems.add("origin '" + origin + "' must be https://host[:port] (http only for localhost), without "
                    + "a path");
            } else if (rpId != null && !host.equals(rpId) && !host.endsWith("." + rpId)) {
                problems.add("origin '" + origin + "' is not under rp-id " + rpId + ": its pages could not use the "
                    + "passkeys");
            }
        }
        return problems;
    }

    /** The host of a usable origin, or null when it is not one. */
    static String host(String origin) {
        try {
            URI uri = new URI(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            boolean pathless = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null
                && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
            if (host == null || !pathless) {
                return null;
            }
            boolean local = host.equals("localhost");
            return "https".equals(scheme) || local && "http".equals(scheme) ? host : null;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}

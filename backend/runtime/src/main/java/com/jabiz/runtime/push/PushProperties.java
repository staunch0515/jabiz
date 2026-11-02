package com.jabiz.runtime.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Push notifications ({@code jabiz.push}, decision D69): off unless {@code enabled}; Web Push needs this server's VAPID
 * key pair ({@code web-push.public-key} and {@code .private-key}, Base64url as web-push tools print them, from the
 * environment only) and a contact ({@code .subject}, {@code mailto:} or {@code https:}). Pushes go only to the push
 * services named in {@code .allowed-hosts} (a host, or {@code *.domain} for any host under it): the address a browser
 * gives is a URL this server posts to, so it must be one of theirs.
 */
@ConfigurationProperties("jabiz.push")
public record PushProperties(boolean enabled, WebPush webPush) {

    /** The push services of Chrome and Android, Firefox, Safari and Edge. */
    public static final List<String> DEFAULT_HOSTS = List.of("fcm.googleapis.com", "*.push.services.mozilla.com",
        "web.push.apple.com", "*.notify.windows.com");

    @ConstructorBinding
    public PushProperties {
        webPush = webPush == null ? new WebPush(null, null, null, null) : webPush;
    }

    /**
     * @param allowedHosts the push services allowed; {@link #DEFAULT_HOSTS} when none is set
     */
    public record WebPush(String publicKey, String privateKey, String subject, List<String> allowedHosts) {

        @ConstructorBinding
        public WebPush {
            allowedHosts = allowedHosts == null || allowedHosts.isEmpty() ? DEFAULT_HOSTS
                : List.copyOf(allowedHosts.stream().map(h -> h == null ? "" : h.trim().toLowerCase(Locale.ROOT))
                    .toList());
        }

        @Override
        public String toString() {
            return "WebPush[publicKey=" + publicKey + ", privateKey=***, subject=" + subject + ", allowedHosts="
                + allowedHosts + "]";
        }
    }

    /**
     * Whether a subscription's endpoint is one this server may post to: https (http only for the local machine, in
     * tests) to an allowed host, no user information, no fragment, at most 1000 characters.
     */
    public boolean allowedEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 1000) {
            return false;
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
            if (host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !(scheme.equals("https") || scheme.equals("http") && local)) {
                return false;
            }
            return webPush.allowedHosts().stream().anyMatch(allowed -> allowed.startsWith("*.")
                ? host.endsWith(allowed.substring(1)) && host.length() > allowed.length() - 1
                : host.equals(allowed));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** What is wrong with the settings; empty when push can be used (or is off). */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        ECPublicKey publicKey = null;
        ECPrivateKey privateKey = null;
        try {
            publicKey = WebPushCrypto.publicKey(WebPushCrypto.base64url(
                blank(webPush.publicKey()) ? "" : webPush.publicKey()));
        } catch (IllegalArgumentException e) {
            problems.add("jabiz.push.web-push.public-key: must be the VAPID public key (an uncompressed P-256 point, "
                + "Base64url)");
        }
        try {
            privateKey = WebPushCrypto.privateKey(WebPushCrypto.base64url(
                blank(webPush.privateKey()) ? "" : webPush.privateKey()));
        } catch (IllegalArgumentException e) {
            problems.add("jabiz.push.web-push.private-key: must be the VAPID private key (32 bytes, Base64url), from "
                + "the environment");
        }
        if (publicKey != null && privateKey != null && !WebPushCrypto.pair(publicKey, privateKey)) {
            // Browsers subscribe with the public key; requests signed with another private key are all refused.
            problems.add("jabiz.push.web-push.public-key: is not the public key of the configured private key");
        }
        String subject = webPush.subject();
        if (blank(subject) || !(subject.startsWith("mailto:") || subject.startsWith("https://"))) {
            problems.add("jabiz.push.web-push.subject: must be a mailto: or https: contact for the push services");
        }
        for (String host : webPush.allowedHosts()) {
            if (!host.matches("(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")) {
                problems.add("jabiz.push.web-push.allowed-hosts: \"" + host + "\" is not a host or *.domain");
            }
        }
        return problems;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

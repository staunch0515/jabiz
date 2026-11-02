package com.jabiz.runtime.push;

import com.jabiz.runtime.security.AppleClientSecret;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Push notifications ({@code jabiz.push}, decision D69): off unless {@code enabled}; Web Push needs this server's VAPID
 * key pair ({@code web-push.public-key} and {@code .private-key}, Base64url as web-push tools print them, from the
 * environment only) and a contact ({@code .subject}, {@code mailto:} or {@code https:}). Pushes go only to the push
 * services named in {@code .allowed-hosts} (a host, or {@code *.domain} for any host under it): the address a browser
 * gives is a URL this server posts to, so it must be one of theirs. Apple's push service for the store apps' iOS shell
 * ({@code apns}, phase 17k-b) is used when its team is set: token authentication with a {@code .p8} key of the team,
 * from the environment only.
 */
@ConfigurationProperties("jabiz.push")
public record PushProperties(boolean enabled, WebPush webPush, Apns apns) {

    private static final Pattern APNS_ID = Pattern.compile("[A-Z0-9]{10}");
    private static final Pattern BUNDLE_ID = Pattern.compile("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");

    /** The push services of Chrome and Android, Firefox, Safari and Edge. */
    public static final List<String> DEFAULT_HOSTS = List.of("fcm.googleapis.com", "*.push.services.mozilla.com",
        "web.push.apple.com", "*.notify.windows.com");

    @ConstructorBinding
    public PushProperties {
        webPush = webPush == null ? new WebPush(null, null, null, null) : webPush;
        apns = apns == null ? new Apns(null, null, null, null, null, null) : apns;
    }

    /** Backwards: settings without APNs. */
    public PushProperties(boolean enabled, WebPush webPush) {
        this(enabled, webPush, null);
    }

    /** Apple's push service, production or the development one ({@code sandbox}, for apps built by Xcode). */
    public static final String APNS_PRODUCTION = "production";
    public static final String APNS_SANDBOX = "sandbox";
    static final String APNS_PRODUCTION_URL = "https://api.push.apple.com";
    static final String APNS_SANDBOX_URL = "https://api.sandbox.push.apple.com";

    /**
     * Apple's push service (17k-b): set when {@code teamId} is.
     *
     * @param teamId      the Apple Developer team (ten characters)
     * @param keyId       the id of the key with Apple Push Notifications service (ten characters)
     * @param privateKey  that key's {@code .p8} file: PEM (line breaks may be written {@code \n}) or its Base64 body
     * @param topic       the app's bundle id, which the notifications are for
     * @param environment {@link #APNS_PRODUCTION} (the default: TestFlight and the App Store) or {@link #APNS_SANDBOX}
     * @param url         another address of the service, for tests only: {@code http://localhost:<port>}
     */
    public record Apns(String teamId, String keyId, String privateKey, String topic, String environment, String url) {

        @ConstructorBinding
        public Apns {
            environment = environment == null || environment.isBlank() ? APNS_PRODUCTION
                : environment.trim().toLowerCase(Locale.ROOT);
        }

        /** Whether APNs is set up at all. */
        public boolean configured() {
            return !blank(teamId);
        }

        /** Whether any setting of APNs but the environment is given. */
        boolean partly() {
            return !blank(teamId) || !blank(keyId) || !blank(privateKey) || !blank(topic) || !blank(url);
        }

        /** The service's address: the configured test address, or Apple's for the environment. */
        public String baseUrl() {
            if (!blank(url)) {
                return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            }
            return APNS_SANDBOX.equals(environment) ? APNS_SANDBOX_URL : APNS_PRODUCTION_URL;
        }

        @Override
        public String toString() {
            return "Apns[teamId=" + teamId + ", keyId=" + keyId + ", privateKey=***, topic=" + topic + ", environment="
                + environment + ", url=" + url + "]";
        }
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
        if (apns.configured()) {
            apnsProblems(problems);
        } else if (apns.partly()) {
            // The other settings of APNs without its team: nothing would be pushed to iPhones, and nothing said.
            problems.add("jabiz.push.apns.team-id: is required with the other jabiz.push.apns settings");
        }
        return problems;
    }

    private void apnsProblems(List<String> problems) {
        if (!APNS_ID.matcher(apns.teamId()).matches()) {
            problems.add("jabiz.push.apns.team-id: must be the team's ten-character id");
        }
        if (blank(apns.keyId()) || !APNS_ID.matcher(apns.keyId()).matches()) {
            problems.add("jabiz.push.apns.key-id: must be the key's ten-character id");
        }
        if (blank(apns.privateKey())) {
            problems.add("jabiz.push.apns.private-key: is required (the key's .p8 file, from the environment)");
        } else {
            try {
                AppleClientSecret.privateKey(apns.privateKey());
            } catch (IllegalArgumentException e) {
                problems.add("jabiz.push.apns.private-key: " + e.getMessage());
            }
        }
        if (blank(apns.topic()) || !BUNDLE_ID.matcher(apns.topic()).matches()) {
            problems.add("jabiz.push.apns.topic: must be the app's bundle id");
        }
        if (!APNS_PRODUCTION.equals(apns.environment()) && !APNS_SANDBOX.equals(apns.environment())) {
            problems.add("jabiz.push.apns.environment: must be production or sandbox");
        }
        if (!blank(apns.url()) && !apns.url().matches("http://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}/?")) {
            // Only tests point it elsewhere: the device tokens and the provider token go to this address.
            problems.add("jabiz.push.apns.url: only a local address for tests; leave it unset");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

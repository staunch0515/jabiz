package com.jabiz.runtime.push;

import com.jabiz.runtime.security.AppleClientSecret;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sends one notification to one iOS device through Apple's push service (phase 17k-b, decision D69): an HTTP/2
 * request to {@code /3/device/<token>} with a provider token, a JWT of the team signed with its {@code .p8} key
 * (ES256), signed once for all sends and kept for 40 minutes (Apple refuses one older than an hour, and answers 429
 * to a team that signs new ones more often than every 20 minutes: a refused token is signed again only once it is 20
 * minutes old). The notification is an alert for the app's bundle ({@code apns-topic}), kept for a day; its collapse
 * id makes a copy sent again replace the first. Only Apple's 410 forgets a device: a token Apple calls bad may be one
 * of the other environment (production and sandbox tokens differ), so a wrong setting must not delete every iPhone.
 * Five seconds at most. The signature draws on {@link java.security.SecureRandom}, which may block: on
 * {@code boundedElastic}. The HTTP/2 client is made at the first notification.
 */
public class ApnsSender {

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** How long Apple keeps a notification for a device that is offline. */
    static final Duration EXPIRATION = Duration.ofDays(1);
    /** How long a provider token is used before a new one is signed. */
    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(40);
    /** The largest notification Apple takes (bytes of JSON). */
    public static final int MAX_PAYLOAD = 4096;
    /** A device token as the app hands it over: hexadecimal. */
    public static final Pattern DEVICE_TOKEN = Pattern.compile("[0-9a-f]{64,200}");
    /** The reasons of a 400 that point at the settings (environment, bundle id) rather than at the device. */
    static final Set<String> SETTINGS_REASONS = Set.of("BadDeviceToken", "DeviceTokenNotForTopic");
    /** The reasons of a 403 that mean the provider token is no longer taken: a new one is signed. */
    static final Set<String> TOKEN_REASONS = Set.of("ExpiredProviderToken", "InvalidProviderToken");
    /** The least age of a provider token before a refused one is signed again (Apple: not more often). */
    static final Duration RESIGN_AFTER = Duration.ofMinutes(20);
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApnsSender.class);
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([A-Za-z]+)\"");

    record ProviderToken(String value, Instant issuedAt) {}

    private final PushProperties.Apns settings;
    private final Clock clock;
    private volatile HttpClient http;
    private ProviderToken token;

    public ApnsSender(PushProperties.Apns settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER).build();
                }
                client = http;
            }
        }
        return client;
    }

    private record Prepared(HttpRequest request, ProviderToken token) {}

    /** Sends {@code payload} (JSON, at most {@link #MAX_PAYLOAD} bytes) to the device; never fails. */
    public Mono<WebPushSender.Outcome> send(String deviceToken, byte[] payload, String collapseId) {
        if (deviceToken == null || !DEVICE_TOKEN.matcher(deviceToken).matches()) {
            // Registration refuses such tokens; one that is not a token is no device of this app.
            return Mono.just(WebPushSender.Outcome.GONE);
        }
        return Mono.fromCallable(() -> request(deviceToken, payload, collapseId))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(prepared -> Mono.fromFuture(() -> http().sendAsync(prepared.request(),
                    HttpResponse.BodyHandlers.ofString()))
                .map(response -> outcome(response.statusCode(), response.body(), prepared.token())))
            .timeout(TIMEOUT)
            .onErrorReturn(WebPushSender.Outcome.FAILED);
    }

    WebPushSender.Outcome outcome(int status, String body, ProviderToken used) {
        if (status == 200) {
            return WebPushSender.Outcome.DELIVERED;
        }
        if (status == 410) {
            // The app was removed from the device, or its token replaced.
            return WebPushSender.Outcome.GONE;
        }
        String reason = reason(body);
        if (status == 400 && SETTINGS_REASONS.contains(reason)) {
            log.warn("APNs refused a device token ({}): check jabiz.push.apns.environment and .topic", reason);
        }
        if (status == 403 && TOKEN_REASONS.contains(reason)) {
            log.warn("APNs refused the provider token ({}): check jabiz.push.apns.team-id, .key-id and the clock",
                reason);
            refused(used);
        }
        return WebPushSender.Outcome.FAILED;
    }

    /** Forgets a refused provider token, if it is still the one in use and old enough to be renewed. */
    synchronized void refused(ProviderToken used) {
        if (used != null && used.equals(token) && !clock.instant().isBefore(used.issuedAt().plus(RESIGN_AFTER))) {
            token = null;
        }
    }

    static String reason(String body) {
        if (body == null) {
            return null;
        }
        var matcher = REASON.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Prepared request(String deviceToken, byte[] payload, String collapseId) {
        ProviderToken provider = providerToken();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/3/device/" + deviceToken))
            .timeout(TIMEOUT)
            .header("authorization", "bearer " + provider.value())
            .header("apns-topic", settings.topic())
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .header("apns-expiration", String.valueOf(clock.instant().plus(EXPIRATION).getEpochSecond()))
            .header("content-type", "application/json");
        if (collapseId != null && !collapseId.isBlank() && collapseId.length() <= 64) {
            request.header("apns-collapse-id", collapseId);
        }
        return new Prepared(request.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), provider);
    }

    /**
     * The provider token in use, or a new one when it is older than {@link #TOKEN_LIFETIME} (or was refused): one
     * signature for all concurrent sends.
     */
    synchronized ProviderToken providerToken() {
        Instant now = clock.instant();
        ProviderToken current = token;
        if (current != null && now.isBefore(current.issuedAt().plus(TOKEN_LIFETIME))) {
            return current;
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(settings.keyId()).build(),
                new JWTClaimsSet.Builder().issuer(settings.teamId()).issueTime(Date.from(now)).build());
            jwt.sign(new ECDSASigner(AppleClientSecret.privateKey(settings.privateKey())));
            ProviderToken signed = new ProviderToken(jwt.serialize(), now);
            token = signed;
            return signed;
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the APNs provider token", e);
        }
    }
}

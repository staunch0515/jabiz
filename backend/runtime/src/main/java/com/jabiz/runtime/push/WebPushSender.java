package com.jabiz.runtime.push;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
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
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sends one Web Push message to one subscription (RFC 8030, 8291 and 8292; decision D69): the payload encrypted for
 * the subscription, the request signed with this server's VAPID key for the push service's origin. Only to allowed
 * push services ({@link PushProperties#allowedEndpoint}). Five seconds at most; does not follow redirects. The key
 * pair and salt of each message come from {@link SecureRandom}, which may block: on {@code boundedElastic}.
 */
public class WebPushSender {

    /** How a push service answered. */
    public enum Outcome {
        /** Accepted (201 or another 2xx). */
        DELIVERED,
        /** The subscription is gone (404 or 410): the device is to be forgotten. */
        GONE,
        /** Refused otherwise or unreachable: tried again with the message. */
        FAILED
    }

    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** How long the push service keeps an undelivered message. */
    static final Duration TTL = Duration.ofDays(1);
    /** How long a VAPID token is valid (RFC 8292: at most 24 hours). */
    static final Duration VAPID_LIFETIME = Duration.ofHours(12);
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final PushProperties properties;
    private final HttpClient http;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WebPushSender(PushProperties properties, Clock clock) {
        this(properties, HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER)
            .build(), clock);
    }

    WebPushSender(PushProperties properties, HttpClient http, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.http = Objects.requireNonNull(http);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Sends {@code payload} to the subscription; never fails, the outcome says how it went. A {@code topic} (at most 32
     * Base64url characters; RFC 8030 section 5.4) makes the push service replace a message with the same topic it has
     * not delivered yet.
     */
    public Mono<Outcome> send(String endpoint, String p256dh, String auth, byte[] payload, String topic) {
        if (!properties.allowedEndpoint(endpoint)) {
            // Registration refuses such endpoints; one kept from before the allowed hosts changed is forgotten.
            return Mono.just(Outcome.GONE);
        }
        return Mono.fromCallable(() -> request(endpoint, p256dh, auth, payload, topic))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(request -> Mono.fromFuture(() -> http.sendAsync(request, HttpResponse.BodyHandlers.discarding())))
            .timeout(TIMEOUT)
            .map(response -> outcome(response.statusCode()))
            .onErrorReturn(Outcome.FAILED);
    }

    static Outcome outcome(int status) {
        if (status >= 200 && status < 300) {
            return Outcome.DELIVERED;
        }
        return status == 404 || status == 410 ? Outcome.GONE : Outcome.FAILED;
    }

    private HttpRequest request(String endpoint, String p256dh, String auth, byte[] payload, String topic)
        throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair keys = generator.generateKeyPair();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] body = WebPushCrypto.encrypt(payload, WebPushCrypto.base64url(p256dh), WebPushCrypto.base64url(auth),
            keys, salt);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint));
        if (topic != null && TOPIC.matcher(topic).matches()) {
            request.header("Topic", topic);
        }
        return request
            .timeout(TIMEOUT)
            .header("Content-Type", "application/octet-stream")
            .header("Content-Encoding", "aes128gcm")
            .header("TTL", String.valueOf(TTL.toSeconds()))
            .header("Urgency", "normal")
            .header("Authorization", vapid(endpoint))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    /** {@code vapid t=<token>, k=<public key>}: a token for the push service's origin, signed with the VAPID key. */
    String vapid(String endpoint) {
        URI uri = URI.create(endpoint);
        String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        try {
            ECPrivateKey key = WebPushCrypto.privateKey(WebPushCrypto.base64url(properties.webPush().privateKey()));
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder().audience(origin)
                    .expirationTime(Date.from(clock.instant().plus(VAPID_LIFETIME)))
                    .subject(properties.webPush().subject()).build());
            jwt.sign(new ECDSASigner(key));
            return "vapid t=" + jwt.serialize() + ", k=" + properties.webPush().publicKey().trim();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the VAPID token", e);
        }
    }
}

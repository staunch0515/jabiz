package com.jabiz.runtime.test;

import com.jabiz.runtime.push.WebPushCrypto;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Web Push service for tests (decision D69): makes browser subscriptions ({@link #subscribe}, with the browser's
 * key pair and secret), accepts pushes to them on a local port, checks the VAPID signature against the expected public
 * key and decrypts each message as a browser would ({@link #received}). A subscription can be made to answer with
 * another status ({@link #answer}, 410 for one that is gone).
 */
public final class TestPushService implements AutoCloseable {

    /** A browser's subscription: what it gives the server, and the private half only the browser has. */
    public record Subscription(String id, String endpoint, String p256dh, String auth, KeyPair keys, byte[] secret) {}

    /** A push as the browser receives it. */
    public record Received(String subscription, String plaintext, String authorization, String ttl,
        String encoding, boolean vapidValid, String topic, int bodyLength) {}

    private final HttpServer server;
    private final String base;
    private final String vapidPublicKey;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Integer> answers = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final SecureRandom random = new SecureRandom();

    /** @param vapidPublicKey the server's VAPID public key the pushes must be signed with (Base64url) */
    public TestPushService(String vapidPublicKey) {
        this.vapidPublicKey = vapidPublicKey;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        base = "http://localhost:" + server.getAddress().getPort() + "/push/";
        server.createContext("/push/", this::push);
        server.start();
    }

    /** A new VAPID key pair as web-push tools print it: {public (65-byte point), private (32-byte scalar)}. */
    public static String[] vapidKeys() {
        try {
            ECKey key = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_256).generate();
            byte[] scalar = key.getD().decode();
            byte[] d = new byte[32];
            System.arraycopy(scalar, Math.max(0, scalar.length - 32), d, 32 - Math.min(32, scalar.length),
                Math.min(32, scalar.length));
            return new String[] {WebPushCrypto.base64url(WebPushCrypto.uncompressed(key.toECPublicKey())),
                WebPushCrypto.base64url(d)};
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new subscription of a browser. */
    public Subscription subscribe() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keys = generator.generateKeyPair();
            byte[] secret = new byte[16];
            random.nextBytes(secret);
            String id = UUID.randomUUID().toString();
            Subscription subscription = new Subscription(id, base + id,
                WebPushCrypto.base64url(WebPushCrypto.uncompressed((ECPublicKey) keys.getPublic())),
                WebPushCrypto.base64url(secret), keys, secret);
            subscriptions.put(id, subscription);
            return subscription;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Makes the subscription answer pushes with {@code status} (410: gone). */
    public void answer(Subscription subscription, int status) {
        answers.put(subscription.id(), status);
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> received(Subscription subscription) {
        return received.stream().filter(r -> r.subscription().equals(subscription.id())).toList();
    }

    private void push(HttpExchange exchange) throws IOException {
        String id = exchange.getRequestURI().getPath().substring("/push/".length());
        byte[] body = exchange.getRequestBody().readAllBytes();
        Subscription subscription = subscriptions.get(id);
        int status = answers.getOrDefault(id, subscription == null ? 404 : 201);
        if (subscription != null && status == 201) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            received.add(new Received(id, decrypt(body, subscription), authorization,
                exchange.getRequestHeaders().getFirst("TTL"), exchange.getRequestHeaders().getFirst("Content-Encoding"),
                vapidValid(authorization), exchange.getRequestHeaders().getFirst("Topic"), body.length));
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /** {@code vapid t=<jwt>, k=<key>}: the key is the expected one and signs the token, for this origin. */
    private boolean vapidValid(String authorization) {
        try {
            if (authorization == null || !authorization.startsWith("vapid ")) {
                return false;
            }
            String token = authorization.replaceAll(".*t=([^,\\s]+).*", "$1");
            String key = authorization.replaceAll(".*k=([^,\\s]+).*", "$1");
            if (!key.equals(vapidPublicKey)) {
                return false;
            }
            SignedJWT jwt = SignedJWT.parse(token);
            ECPublicKey publicKey = WebPushCrypto.publicKey(WebPushCrypto.base64url(key));
            return jwt.verify(new ECDSAVerifier(publicKey))
                && jwt.getJWTClaimsSet().getAudience().equals(List.of(base.substring(0, base.indexOf("/push/"))))
                && jwt.getJWTClaimsSet().getSubject() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** RFC 8291 decryption, as the browser does it. */
    static String decrypt(byte[] body, Subscription subscription) {
        try {
            byte[] salt = Arrays.copyOfRange(body, 0, 16);
            int idLength = body[20] & 0xff;
            byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
            byte[] record = Arrays.copyOfRange(body, 21 + idLength, body.length);
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(subscription.keys().getPrivate());
            agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
            byte[] ecdh = agreement.generateSecret();
            byte[] uaPublic = WebPushCrypto.uncompressed((ECPublicKey) subscription.keys().getPublic());
            byte[] prkKey = hmac(subscription.secret(), ecdh);
            byte[] ikm = hmac(prkKey, concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0},
                uaPublic, asPublic, new byte[] {1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0, 1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0, 1})), 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] padded = cipher.doFinal(record);
            int end = padded.length - 1;
            while (end >= 0 && padded[end] == 0) {
                end--;
            }
            if (end < 0 || padded[end] != 2) {
                throw new IllegalStateException("Not the last record");
            }
            return new String(padded, 0, end, StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot decrypt the push", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

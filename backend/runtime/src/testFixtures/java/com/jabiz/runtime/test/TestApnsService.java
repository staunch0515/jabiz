package com.jabiz.runtime.test;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Apple's push service on a local port (phase 17k-b), as {@link TestPushService} is for Web Push:
 * {@code POST /3/device/<token>} records each notification with its headers and whether its provider token is the
 * team's, signed with the team's key; answers 200, or what a test set for a token (410, or 400 / 403 with a reason).
 * Point {@code jabiz.push.apns.url} at {@link #base()}; the team's {@code .p8} key is {@link #privateKey()}.
 */
public final class TestApnsService implements AutoCloseable {

    /** A notification as Apple received it. */
    public record Received(String token, String body, String topic, String pushType, String collapseId,
        boolean providerTokenValid, String keyId, String teamId) {}

    private record Answer(int status, String reason) {}

    private final HttpServer server;
    private final KeyPair key;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public TestApnsService() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            key = generator.generateKeyPair();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/3/device/", this::push);
        server.start();
    }

    /** The service's address, for {@code jabiz.push.apns.url}. */
    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The team's key as a {@code .p8} file's Base64 body. */
    public String privateKey() {
        return Base64.getEncoder().encodeToString(key.getPrivate().getEncoded());
    }

    /** A device token as an iPhone's app is given one: 32 random bytes, hexadecimal. */
    public String deviceToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Answers {@code status} (with Apple's {@code reason}, or none) to the notifications for {@code token}. */
    public void answer(String token, int status, String reason) {
        answers.put(token, new Answer(status, reason));
    }

    /** The notifications {@code token} received, oldest first. */
    public List<Received> received(String token) {
        return received.stream().filter(one -> one.token().equals(token)).toList();
    }

    private void push(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestURI().getPath().substring("/3/device/".length());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Answer answer = answers.getOrDefault(token, new Answer(200, null));
        if (answer.status() == 200) {
            String authorization = exchange.getRequestHeaders().getFirst("authorization");
            String keyId = null;
            String teamId = null;
            boolean valid = false;
            try {
                SignedJWT jwt = SignedJWT.parse(authorization.substring("bearer ".length()));
                keyId = jwt.getHeader().getKeyID();
                teamId = jwt.getJWTClaimsSet().getIssuer();
                valid = jwt.verify(new ECDSAVerifier((ECPublicKey) key.getPublic()))
                    && jwt.getJWTClaimsSet().getIssueTime() != null;
            } catch (Exception e) {
                valid = false;
            }
            received.add(new Received(token, body, exchange.getRequestHeaders().getFirst("apns-topic"),
                exchange.getRequestHeaders().getFirst("apns-push-type"),
                exchange.getRequestHeaders().getFirst("apns-collapse-id"), valid, keyId, teamId));
        }
        byte[] reply = answer.reason() == null ? new byte[0]
            : ("{\"reason\":\"" + answer.reason() + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answer.status(), reply.length == 0 ? -1 : reply.length);
        if (reply.length > 0) {
            exchange.getResponseBody().write(reply);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

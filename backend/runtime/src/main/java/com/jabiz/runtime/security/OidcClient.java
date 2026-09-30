package com.jabiz.runtime.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.util.JSONObjectUtils;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Talks to the OpenID Connect providers (docs/design/10-security.md section 12) without blocking: the discovery
 * document and JWKS (cached for an hour; the JWKS read again, at most once a minute, when a token names an unknown
 * key), the authorization URL and the token request. Every call has a five second timeout; the endpoints must be
 * https (http only for localhost). Uses the JDK's asynchronous HTTP client: it does not follow redirects and carries
 * no tracing headers to the provider.
 */
public class OidcClient {

    static final Duration CACHE = Duration.ofHours(1);
    static final Duration JWKS_MIN_REFRESH = Duration.ofMinutes(1);
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Discovery documents, JWKS and token responses are small; anything larger is not one. */
    static final int MAX_BODY = 256 * 1024;

    /** The provider could not be reached or answered something unusable. */
    public static final class ProviderException extends RuntimeException {
        ProviderException(String message) {
            super(message);
        }
    }

    /** The endpoints the discovery document names. */
    record Metadata(String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}

    private record Cached<T>(T value, Instant fetchedAt) {}

    private final HttpClient http;
    private final Clock clock;
    private final Map<String, Cached<Metadata>> metadata = new ConcurrentHashMap<>();
    private final Map<String, Cached<JWKSet>> keys = new ConcurrentHashMap<>();

    public OidcClient(Clock clock) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
            clock);
    }

    OidcClient(HttpClient http, Clock clock) {
        this.http = Objects.requireNonNull(http);
        this.clock = Objects.requireNonNull(clock);
    }

    Mono<Metadata> metadata(OidcProvider provider) {
        Cached<Metadata> cached = metadata.get(provider.id());
        if (cached != null && fresh(cached, CACHE)) {
            return Mono.just(cached.value());
        }
        String url = provider.issuer().replaceAll("/+$", "") + "/.well-known/openid-configuration";
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), "discovery").map(body -> {
            Map<String, Object> document = json(body, "discovery");
            if (!provider.issuer().equals(document.get("issuer"))) {
                throw new ProviderException("The discovery document names another issuer");
            }
            Metadata found = new Metadata(endpoint(document, "authorization_endpoint"),
                endpoint(document, "token_endpoint"), endpoint(document, "jwks_uri"));
            metadata.put(provider.id(), new Cached<>(found, clock.instant()));
            return found;
        });
    }

    /** The provider's keys; {@code refresh} reads them again unless that happened within the last minute. */
    Mono<JWKSet> keys(OidcProvider provider, boolean refresh) {
        Cached<JWKSet> cached = keys.get(provider.id());
        if (cached != null && (refresh ? fresh(cached, JWKS_MIN_REFRESH) : fresh(cached, CACHE))) {
            return Mono.just(cached.value());
        }
        return metadata(provider).flatMap(found -> send(HttpRequest.newBuilder(URI.create(found.jwksUri())).GET(),
            "JWKS").map(body -> {
                try {
                    JWKSet set = JWKSet.parse(body);
                    keys.put(provider.id(), new Cached<>(set, clock.instant()));
                    return set;
                } catch (ParseException e) {
                    throw new ProviderException("Unreadable JWKS");
                }
            }));
    }

    Mono<String> authorizationUrl(OidcProvider provider, OidcStateStore.Started started) {
        return metadata(provider).map(found -> UriComponentsBuilder.fromUriString(found.authorizationEndpoint())
            .queryParam("response_type", "code")
            .queryParam("client_id", provider.clientId())
            .queryParam("redirect_uri", provider.redirectUri())
            .queryParam("scope", String.join(" ", provider.scopes()))
            .queryParam("state", started.state())
            .queryParam("nonce", started.nonce())
            .queryParam("code_challenge", started.codeChallenge())
            .queryParam("code_challenge_method", "S256")
            .encode().build().toUriString());
    }

    /** Exchanges an authorization code (client_secret_basic, PKCE) for the ID token. */
    Mono<String> idToken(OidcProvider provider, String code, String codeVerifier) {
        String form = Map.of("grant_type", "authorization_code", "code", code,
                "redirect_uri", provider.redirectUri(), "code_verifier", codeVerifier).entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        // RFC 6749 section 2.3.1: client credentials are form-encoded before Basic authentication.
        String basic = Base64.getEncoder().encodeToString((encode(provider.clientId()) + ":"
            + encode(provider.clientSecret())).getBytes(StandardCharsets.UTF_8));
        return metadata(provider).flatMap(found -> send(HttpRequest.newBuilder(URI.create(found.tokenEndpoint()))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), "token request"))
            .map(body -> {
                Object token = json(body, "token response").get("id_token");
                if (!(token instanceof String text) || text.isBlank()) {
                    throw new ProviderException("The token response has no ID token");
                }
                return text;
            });
    }

    private Mono<String> send(HttpRequest.Builder request, String what) {
        return Mono.fromFuture(() -> http.sendAsync(request.timeout(TIMEOUT).header("Accept", "application/json")
                .build(), responseInfo -> limited()))
            .timeout(TIMEOUT)
            .onErrorMap(e -> !(e instanceof ProviderException), e -> new ProviderException(what + ": "
                + e.getClass().getSimpleName()))
            .map(response -> {
                if (response.statusCode() != 200) {
                    throw new ProviderException(what + ": HTTP " + response.statusCode());
                }
                return response.body();
            });
    }

    /** Reads a body of at most {@link #MAX_BODY} bytes as UTF-8; stops reading and fails beyond. */
    private static HttpResponse.BodySubscriber<String> limited() {
        return new HttpResponse.BodySubscriber<>() {
            private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            private final java.util.concurrent.CompletableFuture<String> result =
                new java.util.concurrent.CompletableFuture<>();
            private java.util.concurrent.Flow.Subscription subscription;

            @Override
            public java.util.concurrent.CompletionStage<String> getBody() {
                return result;
            }

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.util.List<java.nio.ByteBuffer> items) {
                for (java.nio.ByteBuffer item : items) {
                    if (bytes.size() + item.remaining() > MAX_BODY) {
                        subscription.cancel();
                        result.completeExceptionally(new ProviderException("response too large"));
                        return;
                    }
                    byte[] chunk = new byte[item.remaining()];
                    item.get(chunk);
                    bytes.writeBytes(chunk);
                }
            }

            @Override
            public void onError(Throwable error) {
                result.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                result.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        };
    }

    private static Map<String, Object> json(String body, String what) {
        try {
            return JSONObjectUtils.parse(body);
        } catch (ParseException e) {
            throw new ProviderException(what + ": not JSON");
        }
    }

    private static String endpoint(Map<String, Object> document, String name) {
        Object value = document.get(name);
        if (!(value instanceof String url) || !OidcProvider.secureUrl(url)) {
            throw new ProviderException("The discovery document has no usable " + name);
        }
        return url;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean fresh(Cached<?> cached, Duration age) {
        return cached.fetchedAt().plus(age).isAfter(clock.instant());
    }
}

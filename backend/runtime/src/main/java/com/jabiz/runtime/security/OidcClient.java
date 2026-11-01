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

    /**
     * What the token endpoint gave: the ID token, and the refresh token where the provider gives one (Apple's is what
     * revokes the user's authorization when the account goes, decision D67 item 5).
     */
    record Tokens(String idToken, String refreshToken) {
        @Override
        public String toString() {
            return "Tokens[***]";
        }
    }

    /** The endpoints the discovery document names. */
    record Metadata(String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}

    private record Cached<T>(T value, Instant fetchedAt) {}

    private final HttpClient http;
    private final Clock clock;
    private final Map<String, Cached<Metadata>> metadata = new ConcurrentHashMap<>();
    private final Map<String, Cached<JWKSet>> keys = new ConcurrentHashMap<>();
    private final AppleClientSecret appleSecrets;

    public OidcClient(Clock clock) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
            clock);
    }

    OidcClient(HttpClient http, Clock clock) {
        this.http = Objects.requireNonNull(http);
        this.clock = Objects.requireNonNull(clock);
        this.appleSecrets = new AppleClientSecret(clock);
    }

    Mono<Metadata> metadata(OidcProvider provider) {
        Cached<Metadata> cached = metadata.get(provider.id());
        if (cached != null && fresh(cached, CACHE)) {
            return Mono.just(cached.value());
        }
        return send(HttpRequest.newBuilder(URI.create(provider.discoveryUrl())).GET(), "discovery").map(body -> {
            Map<String, Object> document = json(body, "discovery");
            if (!provider.issuer().equals(document.get("issuer"))) {
                throw new ProviderException("The discovery document names another issuer");
            }
            // Facebook's document names no token endpoint: its Graph API's is the one (decision D61 item 1).
            String tokenEndpoint = document.get("token_endpoint") == null && provider.defaultTokenEndpoint() != null
                ? provider.defaultTokenEndpoint() : endpoint(document, "token_endpoint");
            Metadata found = new Metadata(endpoint(document, "authorization_endpoint"), tokenEndpoint,
                endpoint(document, "jwks_uri"));
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

    /**
     * The provider's page to send the user to; it sends them back to {@code redirectUri}
     * ({@link OidcProvider#providerRedirectUri}). Apple posts its answer as a form (it must, to give the name and the
     * address) and takes no PKCE challenge (decision D67 items 1 and 2).
     */
    Mono<String> authorizationUrl(OidcProvider provider, OidcStateStore.Started started, String redirectUri) {
        return metadata(provider).map(found -> {
            UriComponentsBuilder url = UriComponentsBuilder.fromUriString(found.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", provider.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", String.join(" ", provider.scopes()))
                .queryParam("state", started.state())
                .queryParam("nonce", started.nonce());
            if (provider.isApple()) {
                url.queryParam("response_mode", "form_post");
            }
            if (provider.pkce()) {
                url.queryParam("code_challenge", started.codeChallenge()).queryParam("code_challenge_method", "S256");
            }
            return url.encode().build().toUriString();
        });
    }

    /**
     * Exchanges an authorization code (PKCE) for the ID token, naming the callback the authorization request named
     * ({@code redirectUri}), as the provider requires. The client authenticates with Basic (client_secret_basic), or
     * in the form for the providers that take only that ({@link OidcProvider#clientSecretPost}); Apple's secret is a
     * token signed for the purpose ({@link AppleClientSecret}). No verifier where the round trip had no challenge.
     */
    Mono<Tokens> tokens(OidcProvider provider, String code, String codeVerifier, String redirectUri) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("grant_type", "authorization_code");
        fields.put("code", code);
        fields.put("redirect_uri", redirectUri);
        if (provider.pkce()) {
            fields.put("code_verifier", codeVerifier);
        }
        if (provider.clientSecretPost()) {
            fields.put("client_id", provider.clientId());
            fields.put("client_secret", provider.isApple() ? appleSecrets.secretOf(provider) : provider.clientSecret());
        }
        String form = fields.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        return metadata(provider).flatMap(found -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(found.tokenEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
            if (!provider.clientSecretPost()) {
                // RFC 6749 section 2.3.1: client credentials are form-encoded before Basic authentication.
                request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((encode(
                    provider.clientId()) + ":" + encode(provider.clientSecret())).getBytes(StandardCharsets.UTF_8)));
            }
            return send(request, "token request");
        })
            .map(body -> {
                Map<String, Object> response = json(body, "token response");
                if (!(response.get("id_token") instanceof String token) || token.isBlank()) {
                    throw new ProviderException("The token response has no ID token");
                }
                return new Tokens(token, response.get("refresh_token") instanceof String refresh && !refresh.isBlank()
                    ? refresh : null);
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

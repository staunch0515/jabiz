package com.jabiz.quizbuks.shopify;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalService;
import com.jabiz.external.ExternalServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The calls QuizBuks for Shopify makes to a shop's Admin API, through the declared external service {@value #SERVICE}
 * only (platform decision D38), from blocking steps: the token exchange (a staff member's session token for the shop's
 * expiring offline access token), its refresh, and the shop's name. Every shop has an address of its own, allowed by
 * the host pattern {@code *.myshopify.com} (D59 item 5); {@code quizbuks.shopify.shop-url} says how a shop's address
 * is written ({@code https://{shop}}; tests point it at a local stub).
 */
@Component
public class ShopifyApi {

    public static final String SERVICE = "shopify";
    public static final String API_KEY = "SHOPIFY_API_KEY";
    public static final String API_SECRET = "SHOPIFY_API_SECRET";
    /** The Admin API version this module speaks. */
    public static final String API_VERSION = "2026-10";
    static final String SHOP = "{shop}";

    public static final ExternalService DEFINITION = ExternalService.define(SERVICE, s -> s
        .baseUrl("https://shopify.myshopify.com")
        .allowedHosts("*.myshopify.com")
        .secret("apiKey", API_KEY)
        .secret("apiSecret", API_SECRET)
        .connectTimeout(Duration.ofSeconds(5))
        .requestTimeout(Duration.ofSeconds(20))
        .maxResponseSize(256 * 1024));

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ID_TOKEN = "urn:ietf:params:oauth:token-type:id_token";
    static final String OFFLINE_TOKEN = "urn:shopify:params:oauth:token-type:offline-access-token";

    /** A shop's token pair; never logged. */
    public record Tokens(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt,
        String scope) {
        @Override
        public String toString() {
            return "Tokens[accessExpiresAt=" + accessExpiresAt + ", refreshExpiresAt=" + refreshExpiresAt + ", scope="
                + scope + ", tokens=***]";
        }
    }

    /** What Shopify answered: the tokens, or refused (the request was understood and turned down). */
    public record Answer<T>(T value, int refusedStatus) {
        public boolean refused() {
            return value == null;
        }
    }

    private final ExternalServices services;
    private final JsonMapper json;
    private final String shopUrl;

    public ShopifyApi(ExternalServices services, JsonMapper json,
        @Value("${quizbuks.shopify.shop-url:https://{shop}}") String shopUrl) {
        this.services = services;
        this.json = json;
        this.shopUrl = shopUrl;
    }

    /** The address of a shop's Admin API path. */
    String url(String shopDomain, String path) {
        if (!Shopify.isShopDomain(shopDomain)) {
            throw new IllegalArgumentException("Not a shop's address: " + shopDomain);
        }
        return shopUrl.replace(SHOP, shopDomain) + path;
    }

    /** A staff member's session token for the shop's expiring offline token pair ({@code expiring=1}). */
    public Answer<Tokens> exchange(String shopDomain, String sessionToken, Instant now) {
        Map<String, Object> body = credentials();
        body.put("grant_type", TOKEN_EXCHANGE);
        body.put("subject_token", sessionToken);
        body.put("subject_token_type", ID_TOKEN);
        body.put("requested_token_type", OFFLINE_TOKEN);
        body.put("expiring", "1");
        return tokens(shopDomain, body, now);
    }

    /** A new pair for the refresh token; the old pair stops working. */
    public Answer<Tokens> refresh(String shopDomain, String refreshToken, Instant now) {
        Map<String, Object> body = credentials();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        return tokens(shopDomain, body, now);
    }

    /** The shop's name, with an access token; refused when Shopify no longer takes the token. */
    public Answer<String> shopName(String shopDomain, String accessToken) {
        ExternalHttp.Response response = services.http(SERVICE).send(ExternalHttp.Request
            .post(url(shopDomain, "/admin/api/" + API_VERSION + "/graphql.json"))
            .header("X-Shopify-Access-Token", accessToken)
            .json(write(Map.of("query", "{ shop { name } }")))
            .timeout(TIMEOUT));
        if (response.status() == 401 || response.status() == 403) {
            return new Answer<>(null, response.status());
        }
        if (!response.isSuccess()) {
            throw ExternalCallException.badResponse(SERVICE, response.status());
        }
        JsonNode name = read(response).path("data").path("shop").path("name");
        if (!name.isString()) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "no shop name");
        }
        return new Answer<>(name.asString(), 0);
    }

    private Map<String, Object> credentials() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", services.secret(SERVICE, "apiKey"));
        body.put("client_secret", services.secret(SERVICE, "apiSecret"));
        return body;
    }

    private Answer<Tokens> tokens(String shopDomain, Map<String, Object> body, Instant now) {
        ExternalHttp.Response response = services.http(SERVICE).send(ExternalHttp.Request
            .post(url(shopDomain, "/admin/oauth/access_token"))
            .header("Accept", "application/json")
            .json(write(body))
            .timeout(TIMEOUT));
        // Shopify answers a token it will not take (expired, revoked, of another app) with 400 or 401.
        if (response.status() == 400 || response.status() == 401 || response.status() == 403) {
            return new Answer<>(null, response.status());
        }
        if (!response.isSuccess()) {
            throw ExternalCallException.badResponse(SERVICE, response.status());
        }
        JsonNode answer = read(response);
        String access = text(answer, "access_token");
        String refresh = text(answer, "refresh_token");
        long accessSeconds = answer.path("expires_in").asLong(0);
        long refreshSeconds = answer.path("refresh_token_expires_in").asLong(0);
        if (access == null || refresh == null || accessSeconds <= 0 || refreshSeconds <= 0) {
            // Not an expiring offline token: the request did not get what it asked for.
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE,
                "the answer holds no expiring token pair");
        }
        return new Answer<>(new Tokens(access, now.plusSeconds(accessSeconds), refresh, now.plusSeconds(refreshSeconds),
            text(answer, "scope")), 0);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private String write(Map<String, Object> body) {
        return json.writeValueAsString(body);
    }

    private JsonNode read(ExternalHttp.Response response) {
        try {
            return json.readTree(response.text());
        } catch (JacksonException e) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "not JSON");
        }
    }
}

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

    // ---- discounts (S2a, docs/quizbuks/shopify/S2-storefront.md section 2) --------------------------------------------
    //
    // The shapes below follow the Admin GraphQL API as documented for discounts (discountCodeBasicCreate,
    // discountRedeemCodeBulkAdd, discountRedeemCodeBulkCreation, discountCodeDelete); shopify.dev cannot be reached
    // from where this was written, so the first run on a development store checks them (Q-S2-4).

    /** A new discount: its id and the bulk creation that adds the rest of its codes (null when there are none). */
    public record CreatedBatch(String discountId, String bulkCreationId) {}

    /** How a bulk creation went: whether it is done, and the codes Shopify did not add. */
    public record BulkStatus(boolean done, java.util.Set<String> failed) {}

    /**
     * Creates a discount of {@code percent} % off everything, once per customer, each code used once, from now until
     * {@code endsAt}, with the first code; then adds the others in one bulk creation (Shopify takes up to 250 at a
     * time, asynchronously). Refused (null) when Shopify no longer takes the token.
     */
    public Answer<CreatedBatch> createBatch(String shopDomain, String accessToken, String title, int percent,
        Instant startsAt, Instant endsAt, java.util.List<String> codes) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", title);
        input.put("code", codes.getFirst());
        input.put("startsAt", startsAt.toString());
        input.put("endsAt", endsAt.toString());
        input.put("context", Map.of("all", "ALL"));
        input.put("customerGets", Map.of("value", Map.of("percentage", percent / 100.0), "items", Map.of("all", true)));
        input.put("appliesOncePerCustomer", true);
        input.put("usageLimit", 1);
        JsonNode created = graphql(shopDomain, accessToken, """
            mutation create($input: DiscountCodeBasicInput!) {
              discountCodeBasicCreate(basicCodeDiscount: $input) {
                codeDiscountNode { id }
                userErrors { field code message }
              }
            }""", Map.of("input", input));
        if (created == null) {
            return new Answer<>(null, 401);
        }
        JsonNode result = created.path("discountCodeBasicCreate");
        userErrors(result);
        String discountId = result.path("codeDiscountNode").path("id").asString(null);
        if (discountId == null) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "no discount id");
        }
        if (codes.size() == 1) {
            return new Answer<>(new CreatedBatch(discountId, null), 0);
        }
        JsonNode added = graphql(shopDomain, accessToken, """
            mutation add($id: ID!, $codes: [DiscountRedeemCodeInput!]!) {
              discountRedeemCodeBulkAdd(discountId: $id, codes: $codes) {
                bulkCreation { id }
                userErrors { field code message }
              }
            }""", Map.of("id", discountId, "codes", codes.subList(1, codes.size()).stream()
            .map(code -> Map.of("code", code)).toList()));
        if (added == null) {
            return new Answer<>(null, 401);
        }
        JsonNode bulk = added.path("discountRedeemCodeBulkAdd");
        userErrors(bulk);
        String bulkId = bulk.path("bulkCreation").path("id").asString(null);
        if (bulkId == null) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "no bulk creation");
        }
        return new Answer<>(new CreatedBatch(discountId, bulkId), 0);
    }

    /** Whether a bulk creation is done, and which of its codes failed. */
    public Answer<BulkStatus> bulkStatus(String shopDomain, String accessToken, String bulkCreationId) {
        JsonNode answer = graphql(shopDomain, accessToken, """
            query bulk($id: ID!) {
              discountRedeemCodeBulkCreation(id: $id) {
                done
                failedCount
                codes(first: 250) { nodes { code errors { code message } } }
              }
            }""", Map.of("id", bulkCreationId));
        if (answer == null) {
            return new Answer<>(null, 401);
        }
        JsonNode creation = answer.path("discountRedeemCodeBulkCreation");
        if (creation.isMissingNode() || creation.isNull()) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "no bulk creation");
        }
        java.util.Set<String> failed = new java.util.LinkedHashSet<>();
        for (JsonNode node : creation.path("codes").path("nodes")) {
            if (node.path("errors").isArray() && !node.path("errors").isEmpty()) {
                failed.add(node.path("code").asString(""));
            }
        }
        return new Answer<>(new BulkStatus(creation.path("done").asBoolean(false), failed), 0);
    }

    /** Deletes a discount (its end passed: the codes no longer work anyway); one already gone counts as deleted. */
    public Answer<Boolean> deleteDiscount(String shopDomain, String accessToken, String discountId) {
        JsonNode answer = graphql(shopDomain, accessToken, """
            mutation remove($id: ID!) {
              discountCodeDelete(id: $id) {
                deletedCodeDiscountId
                userErrors { field code message }
              }
            }""", Map.of("id", discountId));
        if (answer == null) {
            return new Answer<>(null, 401);
        }
        return new Answer<>(true, 0);
    }

    // ---- plans (S3a, docs/quizbuks/shopify/S3-billing.md section 2) --------------------------------------------------
    //
    // Shopify App Pricing makes the subscriptions (its plan page); the app only reads them. The shape follows the Admin
    // GraphQL API as documented (currentAppInstallation.activeSubscriptions); the first run on a development store
    // checks it (Q-S3-6).

    /** A subscription of the app the shop pays: Shopify's plan name, its state and the end of its billing period. */
    public record Subscription(String id, String name, String status, boolean test, int trialDays, Instant createdAt,
        Instant currentPeriodEnd) {}

    /** The app's subscriptions the shop has now (none on a free plan); refused when Shopify no longer takes the token. */
    public Answer<java.util.List<Subscription>> activeSubscriptions(String shopDomain, String accessToken) {
        JsonNode answer = graphql(shopDomain, accessToken, """
            query plans {
              currentAppInstallation {
                activeSubscriptions { id name status test trialDays createdAt currentPeriodEnd }
              }
            }""", Map.of());
        if (answer == null) {
            return new Answer<>(null, 401);
        }
        JsonNode installation = answer.path("currentAppInstallation");
        if (!installation.isObject() || !installation.path("activeSubscriptions").isArray()) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "no subscriptions");
        }
        java.util.List<Subscription> subscriptions = new java.util.ArrayList<>();
        for (JsonNode node : installation.path("activeSubscriptions")) {
            String id = text(node, "id");
            String name = text(node, "name");
            String status = text(node, "status");
            if (id == null || name == null || status == null) {
                throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "a subscription"
                    + " without id, name or status");
            }
            subscriptions.add(new Subscription(id, name, status, node.path("test").asBoolean(false),
                node.path("trialDays").asInt(0), time(node, "createdAt"), time(node, "currentPeriodEnd")));
        }
        return new Answer<>(java.util.List.copyOf(subscriptions), 0);
    }

    private static Instant time(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "not a time: " + field);
        }
    }

    /** The {@code data} of a GraphQL call; null when Shopify no longer takes the token. */
    private JsonNode graphql(String shopDomain, String accessToken, String query, Map<String, Object> variables) {
        ExternalHttp.Response response = services.http(SERVICE).send(ExternalHttp.Request
            .post(url(shopDomain, "/admin/api/" + API_VERSION + "/graphql.json"))
            .header("X-Shopify-Access-Token", accessToken)
            .json(write(Map.of("query", query, "variables", variables)))
            .timeout(TIMEOUT));
        if (response.status() == 401 || response.status() == 403) {
            return null;
        }
        if (!response.isSuccess()) {
            throw ExternalCallException.badResponse(SERVICE, response.status());
        }
        JsonNode answer = read(response);
        if (answer.path("errors").isArray() && !answer.path("errors").isEmpty()) {
            // A query Shopify could not run (throttled, a field it does not know): retried by the next run.
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE, "GraphQL errors");
        }
        return answer.path("data");
    }

    /** A mutation's user errors: the request was understood and refused, which the next run will not change. */
    private static void userErrors(JsonNode result) {
        JsonNode errors = result.path("userErrors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new ExternalCallException(SERVICE, ExternalCallException.Kind.BAD_RESPONSE,
                "user errors: " + errors.get(0).path("code").asString("?"));
        }
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

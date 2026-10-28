package com.jabiz.quizbuks.it;

import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizBuks for Shopify's integration tests (docs/quizbuks/shopify/S1-install.md): session tokens signed with the
 * app's test secret as Shopify would sign them, sign-in at the entry {@code shopify}, webhooks signed over their body,
 * and a stub of the shops' Admin API on this machine (token exchange, refresh and the shop's name), which by default
 * grants a fresh expiring pair and names a shop after its address, and (S2a) creates discounts whose bulk creations are
 * done at once, and (S3a) answers each shop's subscriptions from {@link #SUBSCRIPTIONS} (none by default). QuizBuks is
 * set up once (the role QBS_SHOP, the parameters published).
 */
abstract class ShopifyItSupport extends ContentItSupport {

    static final String SECRET = "test_only_quizbuks_shopify_secret_0123456789";
    static final String KEY = "test-only-quizbuks-shopify-key";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicInteger GRANTS = new AtomicInteger();

    /** S3a: a shop's active subscriptions as Shopify answers them (the JSON array), by its address. */
    static final Map<String, String> SUBSCRIPTIONS = new java.util.concurrent.ConcurrentHashMap<>();

    static final StubHttpServer SHOPIFY = StubHttpServer.start(ShopifyItSupport::shopify);

    @DynamicPropertySource
    static void shopifyApi(DynamicPropertyRegistry registry) {
        registry.add("jabiz.external-services.shopify.base-url", () -> SHOPIFY.url(""));
        registry.add("jabiz.external-services.shopify.allowed-hosts", () -> "127.0.0.1");
        registry.add("quizbuks.shopify.shop-url", () -> SHOPIFY.url("/shops/{shop}"));
        // Every test opens accounts from the one address of the test client.
        registry.add("jabiz.security.sign-up.rate-limit.per-address", () -> "10000");
    }

    /**
     * Shopify's default answers: a new pair for any exchange or refresh (access for an hour, refresh for 90 days),
     * and the shop's name for any access token.
     */
    static StubHttpServer.Answer shopify(StubHttpServer.Received request) {
        String path = request.path();
        String shop = path.startsWith("/shops/") ? path.substring(7, path.indexOf('/', 7)) : "unknown";
        if (path.endsWith("/admin/oauth/access_token")) {
            int n = GRANTS.incrementAndGet();
            return StubHttpServer.Answer.json(200, "{\"access_token\":\"shpat_" + n + "\",\"scope\":\"read_products\","
                + "\"expires_in\":3600,\"refresh_token\":\"shprt_" + n + "\",\"refresh_token_expires_in\":7776000}");
        }
        if (path.endsWith("/graphql.json")) {
            String body = request.body();
            // S2a: the discounts; every bulk creation is done at once and adds every code.
            if (body.contains("discountCodeBasicCreate")) {
                return StubHttpServer.Answer.json(200, "{\"data\":{\"discountCodeBasicCreate\":{\"codeDiscountNode\":"
                    + "{\"id\":\"gid://shopify/DiscountCodeNode/" + GRANTS.incrementAndGet() + "\"},\"userErrors\":[]}}}");
            }
            if (body.contains("discountRedeemCodeBulkAdd")) {
                return StubHttpServer.Answer.json(200, "{\"data\":{\"discountRedeemCodeBulkAdd\":{\"bulkCreation\":"
                    + "{\"id\":\"gid://shopify/DiscountRedeemCodeBulkCreation/" + GRANTS.incrementAndGet()
                    + "\"},\"userErrors\":[]}}}");
            }
            if (body.contains("discountRedeemCodeBulkCreation")) {
                return StubHttpServer.Answer.json(200, "{\"data\":{\"discountRedeemCodeBulkCreation\":{\"done\":true,"
                    + "\"failedCount\":0,\"codes\":{\"nodes\":[]}}}}");
            }
            if (body.contains("currentAppInstallation")) {
                return StubHttpServer.Answer.json(200, "{\"data\":{\"currentAppInstallation\":{\"activeSubscriptions\":"
                    + SUBSCRIPTIONS.getOrDefault(shop, "[]") + "}}}");
            }
            if (body.contains("discountCodeDelete")) {
                return StubHttpServer.Answer.json(200, "{\"data\":{\"discountCodeDelete\":{\"deletedCodeDiscountId\":"
                    + "\"gid://shopify/DiscountCodeNode/1\",\"userErrors\":[]}}}");
            }
            return StubHttpServer.Answer.json(200, "{\"data\":{\"shop\":{\"name\":\"Shop " + shop + "\"}}}");
        }
        return StubHttpServer.Answer.of(404, "");
    }

    @Autowired
    Clock clock;

    @Autowired
    OutboxDeliverer deliverer;

    @BeforeEach
    void setUpShopify() {
        if (query("SELECT 1 FROM sec_role_version WHERE role_code = 'QBS_SHOP'").isEmpty()) {
            // The controlled parameters too (S2: a storefront's submission reads the engine's, as every one does).
            publishProposals(setup());
        }
        SHOPIFY.reset();
    }

    /** A shop's address no other test uses. */
    static String shop(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + ".myshopify.com";
    }

    /** A session token of {@code staff} in {@code shop}, valid for a minute, as App Bridge gets it. */
    String sessionToken(String shop, String staff) {
        return sessionToken(shop, staff, claims -> { });
    }

    String sessionToken(String shop, String staff, Consumer<Map<String, Object>> change) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://" + shop + "/admin");
        claims.put("dest", "https://" + shop);
        claims.put("aud", KEY);
        claims.put("sub", staff);
        claims.put("exp", now + 60);
        claims.put("nbf", now - 1);
        claims.put("iat", now - 1);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("sid", "s-" + staff);
        change.accept(claims);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT"))) + "."
            + encoder.encodeToString(JSON.writeValueAsBytes(claims));
        return input + "." + encoder.encodeToString(hmac(input.getBytes(StandardCharsets.US_ASCII)));
    }

    static byte[] hmac(byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    WebTestClient.ResponseSpec signIn(String token) {
        return client.post().uri("/api/auth/external-token").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("token", token, "entry", "shopify")).exchange();
    }

    /** A staff member signed in as the embedded pages do; the platform's tokens. */
    Map<String, Object> session(String shop, String staff) {
        return session(shop, staff, claims -> { });
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> session(String shop, String staff, Consumer<Map<String, Object>> change) {
        Map<String, Object> session = signIn(sessionToken(shop, staff, change)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(session).containsEntry("status", "SIGNED_IN");
        return session;
    }

    static String bearer(Map<String, Object> session) {
        return "Bearer " + session.get("accessToken");
    }

    /** Signs in and opens the app (QBS_SHOP_JOIN): the staff member's bearer and the join's answer. */
    record Staff(String bearer, String userId, Map<String, Object> joined) {}

    Staff open(String shop, String staff) {
        return open(shop, staff, claims -> { });
    }

    /** As {@link #open(String, String)}, with the tokens' claims changed (an {@code iat} after an uninstall). */
    Staff open(String shop, String staff, Consumer<Map<String, Object>> change) {
        Map<String, Object> session = session(shop, staff, change);
        Map<String, Object> joined = run("QBS_SHOP_JOIN", bearer(session),
            Map.of("sessionToken", sessionToken(shop, staff, change)));
        return new Staff(bearer(session), (String) session.get("userId"), joined);
    }

    /** Tokens issued a second after the tests' (unmoving) clock: after anything recorded now, such as an uninstall. */
    Consumer<Map<String, Object>> issuedLater() {
        long later = clock.instant().getEpochSecond() + 1;
        return claims -> claims.put("iat", later);
    }

    /** Sends a webhook signed over its body, as Shopify does; answers the platform's response. */
    WebTestClient.ResponseSpec webhook(String topic, String shopHeader, Map<String, Object> body) {
        return webhook(topic, shopHeader, JSON.writeValueAsString(body), null);
    }

    WebTestClient.ResponseSpec webhook(String topic, String shopHeader, String body, String signature) {
        String signed = signature != null ? signature
            : Base64.getEncoder().encodeToString(hmac(body.getBytes(StandardCharsets.UTF_8)));
        return client.post().uri("/api/inbound/shopify").contentType(MediaType.APPLICATION_JSON)
            .header("X-Shopify-Hmac-Sha256", signed).header("X-Shopify-Topic", topic)
            .header("X-Shopify-Event-Id", UUID.randomUUID().toString())
            .header("X-Shopify-Shop-Domain", shopHeader).bodyValue(body).exchange();
    }

    /** Sends a webhook that must be accepted and delivers it to its consumer. */
    void deliver(String topic, String shop, Map<String, Object> body) {
        webhook(topic, shop, body).expectStatus().isOk();
        deliverer.deliverPending().block();
    }

    /** The body of {@code app/uninstalled}: the shop's object. */
    static Map<String, Object> uninstalled(String shop) {
        return Map.of("id", 548380009, "name", "Shop", "domain", "shop.example.com", "myshopify_domain", shop);
    }

    /** The body of a privacy topic about {@code shop}. */
    static Map<String, Object> privacy(String shop) {
        return Map.of("shop_id", 954889, "shop_domain", shop);
    }

    static List<Map<String, Object>> shopRows(String shop) {
        return query("SELECT shop_id, status, connection, shop_name, uninstalled_at FROM qbs_shop WHERE shop_domain = ?",
            shop);
    }

    static List<Map<String, Object>> memberRows(String shop) {
        return query("SELECT user_id, staff_id, role, shop_status FROM qbs_shop_member WHERE shop_domain = ?"
            + " ORDER BY joined_at, staff_id", shop);
    }

    /** The rule code of a problem: its first violation's, or its own. */
    static String ruleCode(Map<String, Object> problem) {
        Object violations = problem.get("violations");
        if (violations instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
            return String.valueOf(first.get("ruleCode"));
        }
        return String.valueOf(problem.get("code"));
    }
}

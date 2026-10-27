package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuizBuks for Shopify, S1 (docs/quizbuks/shopify/S1-install.md): staff sign in with Shopify's session token and join
 * their shop (the first as its admin), the admin hands the role on, a member connects the shop (the session token is
 * exchanged for the shop's expiring offline tokens, kept sealed) and checks it (refreshing near the end, dropping the
 * tokens Shopify refuses), and Shopify's webhooks uninstall, erase and ask about customers' data.
 */
class ShopifyIT extends ShopifyItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void theFirstStaffMemberBecomesTheAdminAndJoiningAgainChangesNothing() {
        String shop = shop("join");
        Staff first = open(shop, "101");
        assertThat(first.joined()).containsEntry("shopDomain", shop).containsEntry("role", "ADMIN")
            .containsEntry("joined", true).containsEntry("reinstalled", false).containsEntry("connection", "NONE");
        Staff second = open(shop, "102");
        assertThat(second.joined()).containsEntry("role", "MEMBER").containsEntry("joined", true);
        assertThat((List<Map<String, Object>>) second.joined().get("members")).extracting(m -> m.get("staffId"))
            .containsExactly("101", "102");

        Map<String, Object> again = run("QBS_SHOP_JOIN", first.bearer(), Map.of("sessionToken",
            sessionToken(shop, "101")));
        assertThat(again).containsEntry("role", "ADMIN").containsEntry("joined", false);
        assertThat(memberRows(shop)).extracting(r -> r.get("staff_id") + ":" + r.get("role") + ":" + r.get("shop_status"))
            .containsExactly("101:ADMIN:ACTIVE", "102:MEMBER:ACTIVE");
        assertThat(shopRows(shop)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "ACTIVE")
            .containsEntry("connection", "NONE"));
        // The sign-in opened accounts of the role QBS_SHOP only, named after the identity, without an address.
        assertThat(query("SELECT user_name, email FROM sec_user_version WHERE user_id = ?::uuid", first.userId()))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("user_name", shop + "/101")
                .containsEntry("email", null));
    }

    @Test
    void joiningTakesAFreshTokenOfTheOperatorOnly() {
        String shop = shop("token");
        Staff first = open(shop, "1");
        open(shop, "2");
        // Another staff member's token, one of a person nobody linked, one of another shop: 403 alike.
        for (String token : List.of(sessionToken(shop, "2"), sessionToken(shop, "3"), sessionToken(shop("other"), "1"),
            sessionToken(shop, "1", c -> c.put("aud", "another-app")))) {
            refused("QBS_SHOP_JOIN", first.bearer(), Map.of("sessionToken", token), 403);
        }
        // Somebody of the shop who is not signed in is nobody.
        refused("QBS_SHOP_JOIN", "Bearer not-a-token", Map.of("sessionToken", sessionToken(shop, "1")), 401);
    }

    @Test
    void theAdminHandsTheRoleOnToAnotherMemberOfTheShop() {
        String shop = shop("transfer");
        Staff admin = open(shop, "1");
        Staff member = open(shop, "2");
        Staff stranger = open(shop("elsewhere"), "1");

        assertThat(ruleCode(refused("QBS_SHOP_ADMIN_TRANSFER", member.bearer(), Map.of("toUserId", admin.userId()),
            422))).isEqualTo("QBS_NOT_ADMIN");
        for (String toUserId : List.of(admin.userId(), stranger.userId(), "no-such-user")) {
            assertThat(ruleCode(refused("QBS_SHOP_ADMIN_TRANSFER", admin.bearer(), Map.of("toUserId", toUserId),
                422))).as(toUserId).isEqualTo("QBS_NOT_A_COLLEAGUE");
        }
        assertThat(run("QBS_SHOP_ADMIN_TRANSFER", admin.bearer(), Map.of("toUserId", member.userId())))
            .containsEntry("adminUserId", member.userId());
        assertThat(memberRows(shop)).extracting(r -> r.get("staff_id") + ":" + r.get("role"))
            .containsExactly("1:MEMBER", "2:ADMIN");
        // And back: the new admin may.
        run("QBS_SHOP_ADMIN_TRANSFER", member.bearer(), Map.of("toUserId", admin.userId()));
        assertThat(memberRows(shop)).extracting(r -> r.get("role")).containsExactly("ADMIN", "MEMBER");
        // Somebody of no shop.
        assertThat(ruleCode(refused("QBS_SHOP_ADMIN_TRANSFER", tokenWithoutShop(), Map.of("toUserId",
            admin.userId()), 422))).isEqualTo("QBS_NOT_A_MEMBER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectingExchangesTheSessionTokenAndKeepsTheTokensSealed() {
        String shop = shop("connect");
        Staff admin = open(shop, "1");
        Staff member = open(shop, "2");
        String token = sessionToken(shop, "2");
        Map<String, Object> connected = run("QBS_SHOP_CONNECT", member.bearer(), Map.of("sessionToken", token));
        assertThat(connected).containsEntry("connection", "CONNECTED").containsEntry("shopName", "Shop " + shop);

        StubHttpServer.Received exchange = SHOPIFY.requests("/shops/" + shop + "/admin/oauth/access_token").getFirst();
        Map<String, Object> asked = JSON.readValue(exchange.body(), Map.class);
        assertThat(asked).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
            .containsEntry("subject_token", token).containsEntry("expiring", "1")
            .containsEntry("subject_token_type", "urn:ietf:params:oauth:token-type:id_token")
            .containsEntry("requested_token_type", "urn:shopify:params:oauth:token-type:offline-access-token")
            .containsEntry("client_id", KEY).containsEntry("client_secret", SECRET);
        String access = SHOPIFY.requests("/shops/" + shop + "/admin/api/2026-10/graphql.json").getFirst()
            .header("X-Shopify-Access-Token");
        assertThat(access).startsWith("shpat_");

        Map<String, Object> stored = query("SELECT t.* FROM qbs_shop_token t JOIN qbs_shop s ON s.shop_id = t.shop_id"
            + " WHERE s.shop_domain = ?", shop).getFirst();
        assertThat(String.valueOf(stored.get("sealed_access_token"))).doesNotContain(access).doesNotContain("shpat_");
        assertThat(String.valueOf(stored.get("sealed_refresh_token"))).doesNotContain("shprt_");
        assertThat(stored.get("access_expires_at")).isNotNull();
        assertThat(shopRows(shop).getFirst()).containsEntry("connection", "CONNECTED")
            .containsEntry("shop_name", "Shop " + shop);
        // The session token is no part of the record.
        assertThat(query("SELECT input_summary FROM op_process WHERE process_name = 'QBS_SHOP_CONNECT'"))
            .allSatisfy(row -> assertThat(String.valueOf(row.get("input_summary"))).doesNotContain(token));

        // The join's answer shows the connection; a check reads the name without refreshing a fresh token.
        assertThat(run("QBS_SHOP_JOIN", admin.bearer(), Map.of("sessionToken", sessionToken(shop, "1"))))
            .containsEntry("connection", "CONNECTED").containsEntry("shopName", "Shop " + shop);
        SHOPIFY.reset();
        assertThat(run("QBS_SHOP_CHECK", admin.bearer(), Map.of())).containsEntry("connection", "CONNECTED");
        assertThat(SHOPIFY.requests("/shops/" + shop + "/admin/oauth/access_token")).isEmpty();
        assertThat(SHOPIFY.requests("/shops/" + shop + "/admin/api/2026-10/graphql.json")).singleElement()
            .satisfies(r -> assertThat(r.header("X-Shopify-Access-Token")).isEqualTo(access));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAccessTokenAboutToEndIsRefreshedAndARefusedOneDropsTheConnection() {
        String shop = shop("refresh");
        Staff admin = open(shop, "1");
        run("QBS_SHOP_CONNECT", admin.bearer(), Map.of("sessionToken", sessionToken(shop, "1")));
        String firstRefresh = "shprt_" + GRANTS.get();
        endsSoon(shop);
        SHOPIFY.reset();

        assertThat(run("QBS_SHOP_CHECK", admin.bearer(), Map.of())).containsEntry("connection", "CONNECTED");
        Map<String, Object> asked = JSON.readValue(SHOPIFY.requests("/shops/" + shop + "/admin/oauth/access_token")
            .getFirst().body(), Map.class);
        assertThat(asked).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", firstRefresh);
        String renewed = "shpat_" + GRANTS.get();
        assertThat(SHOPIFY.requests("/shops/" + shop + "/admin/api/2026-10/graphql.json").getFirst()
            .header("X-Shopify-Access-Token")).isEqualTo(renewed);

        // Shopify refuses the refresh token: the tokens are dropped and the shop connects again.
        endsSoon(shop);
        SHOPIFY.script("/shops/" + shop + "/admin/oauth/access_token", StubHttpServer.Answer.json(400,
            "{\"error\":\"invalid_grant\"}"));
        assertThat(run("QBS_SHOP_CHECK", admin.bearer(), Map.of())).containsEntry("connection", "RECONNECT");
        assertThat(query("SELECT 1 FROM qbs_shop_token t JOIN qbs_shop s ON s.shop_id = t.shop_id"
            + " WHERE s.shop_domain = ?", shop)).isEmpty();
        assertThat(shopRows(shop).getFirst()).containsEntry("connection", "RECONNECT");
        assertThat(ruleCode(refused("QBS_SHOP_CHECK", admin.bearer(), Map.of(), 422))).isEqualTo("QBS_NOT_CONNECTED");
        // Connecting again brings it back.
        assertThat(run("QBS_SHOP_CONNECT", admin.bearer(), Map.of("sessionToken", sessionToken(shop, "1"))))
            .containsEntry("connection", "CONNECTED");
    }

    @Test
    void anExchangeShopifyRefusesKeepsNothing() {
        String shop = shop("refused");
        Staff admin = open(shop, "1");
        SHOPIFY.script("/shops/" + shop + "/admin/oauth/access_token", StubHttpServer.Answer.json(401,
            "{\"error\":\"invalid_subject_token\"}"));
        assertThat(ruleCode(refused("QBS_SHOP_CONNECT", admin.bearer(), Map.of("sessionToken",
            sessionToken(shop, "1")), 422))).isEqualTo("QBS_EXCHANGE_REFUSED");
        assertThat(shopRows(shop).getFirst()).containsEntry("connection", "NONE");
        assertThat(query("SELECT 1 FROM qbs_shop_token t JOIN qbs_shop s ON s.shop_id = t.shop_id"
            + " WHERE s.shop_domain = ?", shop)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUninstallKeepsTheDataEndsTheSessionsAndAReinstallCarriesOn() {
        String shop = shop("uninstall");
        Map<String, Object> adminSession = session(shop, "1");
        String adminBearer = bearer(adminSession);
        run("QBS_SHOP_JOIN", adminBearer, Map.of("sessionToken", sessionToken(shop, "1")));
        Staff member = open(shop, "2");
        run("QBS_SHOP_CONNECT", adminBearer, Map.of("sessionToken", sessionToken(shop, "1")));

        deliver("app/uninstalled", shop, uninstalled(shop));
        assertThat(shopRows(shop).getFirst()).containsEntry("status", "UNINSTALLED").containsEntry("connection", "NONE");
        assertThat(shopRows(shop).getFirst().get("uninstalled_at")).isNotNull();
        assertThat(memberRows(shop)).extracting(r -> r.get("shop_status")).containsOnly("UNINSTALLED");
        assertThat(query("SELECT 1 FROM qbs_shop_token t JOIN qbs_shop s ON s.shop_id = t.shop_id"
            + " WHERE s.shop_domain = ?", shop)).isEmpty();
        // Sessions end at their next refresh; writes are refused meanwhile.
        client.post().uri("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("refreshToken", adminSession.get("refreshToken"), "entry", "shopify")).exchange()
            .expectStatus().isUnauthorized();
        assertThat(ruleCode(refused("QBS_SHOP_ADMIN_TRANSFER", adminBearer, Map.of("toUserId", member.userId()),
            422))).isEqualTo("QBS_SHOP_UNINSTALLED");
        assertThat(ruleCode(refused("QBS_SHOP_CHECK", adminBearer, Map.of(), 422)))
            .isEqualTo("QBS_SHOP_UNINSTALLED");
        // Delivered twice by Shopify: nothing more happens.
        deliver("app/uninstalled", shop, uninstalled(shop));

        // Installed again: a staff member opens the app, the shop is active again, members and admin as before.
        Staff back = open(shop, "1");
        assertThat(back.joined()).containsEntry("role", "ADMIN").containsEntry("reinstalled", true)
            .containsEntry("joined", false);
        assertThat(shopRows(shop).getFirst()).containsEntry("status", "ACTIVE").containsEntry("uninstalled_at", null);
        assertThat(memberRows(shop)).extracting(r -> r.get("staff_id") + ":" + r.get("role") + ":" + r.get("shop_status"))
            .containsExactly("1:ADMIN:ACTIVE", "2:MEMBER:ACTIVE");
        assertThat((List<Map<String, Object>>) back.joined().get("members")).hasSize(2);
    }

    @Test
    void shopRedactErasesAnUninstalledShopAndItsAccounts() {
        String shop = shop("redact");
        Staff admin = open(shop, "1");
        Staff member = open(shop, "2");
        run("QBS_SHOP_CONNECT", admin.bearer(), Map.of("sessionToken", sessionToken(shop, "1")));
        deliver("app/uninstalled", shop, uninstalled(shop));

        deliver("shop/redact", shop, privacy(shop));
        assertThat(shopRows(shop)).isEmpty();
        assertThat(memberRows(shop)).isEmpty();
        for (String userId : List.of(admin.userId(), member.userId())) {
            assertThat(query("SELECT enabled FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC"
                + " LIMIT 1", userId).getFirst()).containsEntry("enabled", false);
        }
        assertThat(query("SELECT 1 FROM (SELECT DISTINCT ON (user_identity_id) * FROM sec_user_identity_version"
            + " ORDER BY user_identity_id, effect_start_time DESC, version_no DESC) i WHERE NOT i.is_deleted"
            + " AND i.subject LIKE ?", shop + "/%")).isEmpty();
        assertThat(compliance(shop)).containsExactly("shop/redact:DELETED");

        // The same staff member opening the app after a new install is a new account of a new shop.
        Staff again = open(shop, "1");
        assertThat(again.userId()).isNotEqualTo(admin.userId());
        assertThat(again.joined()).containsEntry("role", "ADMIN").containsEntry("reinstalled", false);
    }

    @Test
    void shopRedactKeepsAShopInstalledAgainAndRecordsAnUnknownOne() {
        String shop = shop("kept");
        open(shop, "1");
        deliver("app/uninstalled", shop, uninstalled(shop));
        open(shop, "1");
        deliver("shop/redact", shop, privacy(shop));
        assertThat(shopRows(shop)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "ACTIVE"));
        assertThat(compliance(shop)).containsExactly("shop/redact:KEPT_REINSTALLED");

        String unknown = shop("unknown");
        deliver("shop/redact", unknown, privacy(unknown));
        assertThat(compliance(unknown)).containsExactly("shop/redact:NOT_FOUND");
    }

    @Test
    void customersPrivacyRequestsAreRecordedAsNoData() {
        String shop = shop("privacy");
        open(shop, "1");
        Map<String, Object> request = Map.of("shop_id", 954889, "shop_domain", shop, "orders_requested",
            List.of(299938, 280263), "customer", Map.of("id", 191167, "email", "john@example.com"),
            "data_request", Map.of("id", 9999));
        deliver("customers/data_request", shop, request);
        deliver("customers/redact", shop, Map.of("shop_id", 954889, "shop_domain", shop, "customer",
            Map.of("id", 191167), "orders_to_redact", List.of(299938)));
        assertThat(compliance(shop)).containsExactlyInAnyOrder("customers/data_request:NO_DATA",
            "customers/redact:NO_DATA");
    }

    @Test
    void aWebhookWhoseHeaderNamesAnotherShopIsNotActedOn() {
        String shop = shop("mismatch");
        String other = shop("victim");
        open(other, "1");
        // The body says one shop, the (unsigned) header another: nothing happens to either.
        deliver("app/uninstalled", other, uninstalled(shop));
        assertThat(shopRows(other).getFirst()).containsEntry("status", "ACTIVE");
        deliver("shop/redact", other, privacy(shop));
        assertThat(shopRows(other)).hasSize(1);
        assertThat(query("SELECT outcome FROM qbs_compliance_request WHERE shop_domain IS NULL")).isNotEmpty();
    }

    @Test
    void aBadSignatureIsAnsweredWith401AndOtherTopicsAreIgnored() {
        String shop = shop("signature");
        webhook("app/uninstalled", shop, "{\"myshopify_domain\":\"" + shop + "\"}",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").expectStatus().isUnauthorized();
        // A topic the provider does not list is answered and dropped.
        webhook("orders/create", shop, Map.of("id", 1)).expectStatus().isOk();
        assertThat(query("SELECT 1 FROM sys_inbound_event WHERE provider = 'shopify' AND event_type = 'orders/create'"))
            .isEmpty();
    }

    @Test
    void theEmbeddedPagesAreFramedByTheShopThatOpenedThemOnly() {
        String shop = shop("frame");
        client.get().uri("/shopify/?shop=" + shop + "&host=abc").exchange()
            .expectHeader().value("Content-Security-Policy", csp -> assertThat(csp)
                .contains("frame-ancestors https://" + shop + " https://admin.shopify.com")
                .contains("script-src 'self' https://cdn.shopify.com"))
            .expectHeader().doesNotExist("X-Frame-Options");
        client.get().uri("/shopify/?shop=evil.example.com").exchange()
            .expectHeader().valueEquals("X-Frame-Options", "DENY")
            .expectHeader().value("Content-Security-Policy", csp -> assertThat(csp).contains("frame-ancestors 'none'"));
        client.get().uri("/sponsor/?shop=" + shop).exchange().expectHeader().valueEquals("X-Frame-Options", "DENY");
    }

    /** Makes the shop's access token end within the refresh margin (the tests' clock does not move). */
    void endsSoon(String shop) {
        Instant soon = clock.instant().plusSeconds(60);
        execute("UPDATE qbs_shop_token SET access_expires_at = ? WHERE shop_id = (SELECT shop_id FROM qbs_shop"
            + " WHERE shop_domain = ?)", java.sql.Timestamp.from(soon), shop);
    }

    static List<String> compliance(String shop) {
        return query("SELECT topic, outcome FROM qbs_compliance_request WHERE shop_domain = ? ORDER BY received_at",
            shop).stream().map(r -> r.get("topic") + ":" + r.get("outcome")).toList();
    }

    /** A signed-in user of the entry who never joined a shop (the token's permissions are the role's). */
    String tokenWithoutShop() {
        return com.jabiz.runtime.test.TestTokens.bearer(tokens, java.util.UUID.randomUUID().toString(),
            com.jabiz.quizbuks.shopify.Shopify.USE);
    }

    static String ruleCode(Map<String, Object> problem) {
        Object violations = problem.get("violations");
        if (violations instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
            return String.valueOf(first.get("ruleCode"));
        }
        return String.valueOf(problem.get("code"));
    }
}

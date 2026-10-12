package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.PortalOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer portal of the sample (ROADMAP phase 16b-2; docs/design/10-security.md section 15): customers register
 * at the {@code portal} entry, verify their address, sign in and order for their own customer only
 * ({@value PortalOrders#NAME}); a blocked customer's account is refused by the portal's sign-in guard, at sign-in and
 * at the next refresh.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3030", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import(SignUpItSupport.FixedTimeLimits.class)
class PortalIT extends SignUpItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3030, null, ServerSetup.PROTOCOL_SMTP));

    private static final String ORDER = "/api/processes/" + PortalOrders.NAME + "/latest";

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @BeforeEach
    void catalog() {
        if (query("SELECT 1 FROM warehouse_version WHERE warehouse_code = 'PRT'").isEmpty()) {
            insert(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", "PRT", "warehouseName", "Portal",
                "active", true), null);
            insert(CommerceEntities.PRODUCT_DATASET, Map.of("sku", "PRT-1", "productName", "Portal tea",
                "unitPrice", 400, "active", true), null);
            post("/api/processes/STOCK_RECEIVE/latest", admin(), Map.of("warehouseCode", "PRT", "sku", "PRT-1",
                "quantity", 100)).expectStatus().isOk();
        }
    }

    /** Registers at the portal and verifies the address through the mailed link; returns the user id. */
    private String registered(String email) {
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        deliver();
        anonymous("/api/auth/verify-email", Map.of("token", token(VERIFY_LINK, email), "password", PASSWORD))
            .expectStatus().isNoContent();
        return String.valueOf(userOf(email).get("user_id"));
    }

    private String customerOf(String userId, String code) {
        return String.valueOf(insert(CommerceEntities.CUSTOMER_DATASET, Map.of("customerCode", code,
            "customerName", code, "userId", userId), null).get("id"));
    }

    private void block(String customerId) {
        Map<String, Object> customer = get("/api/datasets/" + CommerceEntities.CUSTOMER_DATASET + "/entities/"
            + customerId, admin()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("/api/datasets/" + CommerceEntities.CUSTOMER_DATASET + "/commit", admin(), Map.of("changes", List.of(
            Map.of("action", "UPDATE", "id", customerId, "version", customer.get("version"), "attributes",
                Map.of("blocked", true))))).expectStatus().isOk();
    }

    private Map<String, Object> session(String email) {
        return session(email, PASSWORD, PORTAL);
    }

    private static Map<String, Object> order(int quantity) {
        return Map.of("warehouseCode", "PRT", "lines", List.of(Map.of("sku", "PRT-1", "quantity", quantity)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRegisteredCustomerVerifiesSignsInAndOrdersForTheirOwnCustomer() {
        String email = address("cara");
        String userId = registered(email);
        String code = unique("C").toUpperCase();
        customerOf(userId, code);

        Map<String, Object> placed = post(ORDER, bearerOf(session(email)), order(2)).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        String orderId = (String) ((Map<String, Object>) placed.get("output")).get("orderId");
        assertThat(query("SELECT customer_code FROM sales_order_version WHERE order_id = ?::uuid", orderId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("customer_code", code));

        // The input cannot name another customer: there is no such field, and the order is the caller's all the same.
        Map<String, Object> other = new java.util.HashMap<>(order(1));
        other.put("customerCode", "SOMEONE-ELSE");
        Map<String, Object> again = post(ORDER, bearerOf(session(email)), other).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(query("SELECT customer_code FROM sales_order_version WHERE order_id = ?::uuid",
            ((Map<String, Object>) again.get("output")).get("orderId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("customer_code", code));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String template, String bearer) {
        Map<String, Object> page = post("/api/queries/" + template, bearer, Map.of("params", Map.of()))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void aCustomerSeesTheirOwnOrdersOnlyAndWhatTheyCanOrder() {
        // The portal's pages (phase 16c-2): my orders, through the dataset of the caller's own customer.
        String mine = address("mine");
        customerOf(registered(mine), unique("M").toUpperCase());
        String theirs = address("theirs");
        customerOf(registered(theirs), unique("T").toUpperCase());
        String bearer = bearerOf(session(mine));
        String placed = (String) ((Map<?, ?>) post(ORDER, bearer, order(3)).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody().get("output")).get("orderNo");
        post(ORDER, bearerOf(session(theirs)), order(1)).expectStatus().isOk();

        assertThat(rows("commerce.portal_orders", bearer)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("orderNo", placed).containsEntry("lineCount", 1));
        String other = bearerOf(session(theirs));
        assertThat(rows("commerce.portal_orders", other)).singleElement().satisfies(row -> assertThat(row)
            .doesNotContainEntry("orderNo", placed));
        // An account without a customer has no orders at all.
        String none = address("nobody");
        registered(none);
        assertThat(rows("commerce.portal_orders", bearerOf(session(none)))).isEmpty();
        assertThat(rows("commerce.portal_products", bearer)).anySatisfy(row -> assertThat(row)
            .containsEntry("warehouseCode", "PRT").containsEntry("sku", "PRT-1"));
        // The caller's customer cannot be written through the portal's dataset.
        post("/api/datasets/" + PortalOrders.OWN_CUSTOMER_DATASET + "/commit", bearer, Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("customerCode", "X", "customerName", "X")))))
            .expectStatus().isForbidden();
    }

    @Test
    void anUnverifiedSessionCannotOrder() {
        // Signed in where no verified address is required.
        String email = address("uno");
        register(PORTAL, email, PASSWORD).expectStatus().isAccepted();
        String userId = String.valueOf(userOf(email).get("user_id"));
        customerOf(userId, unique("U").toUpperCase());
        assertThat(refusal(loginTo(email, PASSWORD, PORTAL), 403)).isEqualTo("EMAIL_NOT_VERIFIED");
        Map<String, Object> loose = session(email, PASSWORD, LOOSE);
        assertThat(refusal(post(ORDER, bearerOf(loose), order(1)), 403)).isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void anAccountWithoutACustomerCannotOrder() {
        String email = address("none");
        registered(email);
        assertThat(refusal(post(ORDER, bearerOf(session(email)), order(1)), 422))
            .isEqualTo(PortalOrders.NO_CUSTOMER);
    }

    @Test
    void aBlockedCustomerIsRefusedAtSignInAndAtTheNextRefresh() {
        String email = address("blk");
        String userId = registered(email);
        String customerId = customerOf(userId, unique("B").toUpperCase());
        Map<String, Object> session = session(email);

        block(customerId);
        assertThat(refusal(loginTo(email, PASSWORD, PORTAL), 403)).isEqualTo("SIGN_IN_REFUSED");
        assertThat(query("SELECT outcome FROM sec_login_record_version WHERE user_id = ?::uuid "
            + "ORDER BY attempt_no DESC LIMIT 1", userId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("outcome", "REFUSED"));
        assertThat(refusal(post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"),
            "entry", PORTAL)), 401)).isEqualTo("INVALID_REFRESH_TOKEN");
        // Its access token is still good until it expires; ordering is refused all the same.
        assertThat(refusal(post(ORDER, bearerOf(session), order(1)), 422)).isEqualTo(PortalOrders.CUSTOMER_BLOCKED);
        // The guard is the portal's: other entries do not ask it.
        loginTo(email, PASSWORD, LOOSE).expectStatus().isOk();
    }
}

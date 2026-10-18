package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItRetentionFixtures;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision D51 item 3 (review M2, L4): a time PostgreSQL cannot store, or the driver cannot bind faithfully, is the
 * caller's 400 wherever it is given - an attribute, the effective time of a commit or of a protected-grant request,
 * the bounds of a data export - and the bounds themselves are stored and read back unchanged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class TimeRangeIT extends SecurityItSupport {

    private static final String FAR = "+300000-01-01T00:00:00Z";

    private Map<String, Object> badRequest(String path, Map<String, Object> body) {
        return post(path, admin(), body).expectStatus().isBadRequest().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    private static List<Object> fields(Map<String, Object> problem) {
        return violations(problem).stream().map(v -> v.get("field")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> saved) {
        return (Map<String, Object>) saved.get("attributes");
    }

    @Test
    void theBoundsAreStoredAndReadBackUnchanged() {
        String early = "-4712-01-01T00:00:00Z";
        String late = "+294276-12-31T23:59:59.999999Z";
        for (String time : List.of(early, late)) {
            Map<String, Object> saved = insert(ItRetentionFixtures.RECORD_DATASET, Map.of("recordId", unique("r"),
                "title", "bound", "bookedTime", time), null);
            Object id = saved.get("id");
            Map<String, Object> read = get("/api/datasets/" + ItRetentionFixtures.RECORD_DATASET + "/entities/" + id,
                admin()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
            assertThat(Instant.parse(String.valueOf(attributes(read).get("bookedTime"))))
                .isEqualTo(Instant.parse(time));
        }
        for (String day : List.of("-4712-01-01", "+5874897-12-31")) {
            Map<String, Object> saved = insert(ItRetentionFixtures.VOUCHER_DATASET, Map.of("voucherId",
                unique("v"), "title", "bound", "voucherDate", day), null);
            Map<String, Object> read = get("/api/datasets/" + ItRetentionFixtures.VOUCHER_DATASET + "/entities/"
                + saved.get("id"), admin()).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
            assertThat(String.valueOf(attributes(read).get("voucherDate"))).isEqualTo(day);
        }
        // Just before: refused, never stored as -infinity.
        Map<String, Object> change = Map.of("action", "INSERT", "attributes", Map.of("recordId", unique("r"),
            "title", "x", "bookedTime", "-4713-12-31T23:59:59Z"));
        assertThat(fields(badRequest("/api/datasets/" + ItRetentionFixtures.RECORD_DATASET + "/commit",
            Map.of("changes", List.of(change))))).hasSize(1);
    }

    @Test
    void anEffectiveTimeOutOfRangeIsABadRequest() {
        Map<String, Object> change = new LinkedHashMap<>(Map.of("action", "INSERT", "attributes",
            Map.of("title", "far", "vendor", "v"), "effectiveTime", FAR));
        assertThat(fields(badRequest("/api/datasets/" + ItRetentionFixtures.DOCUMENT_DATASET + "/commit",
            Map.of("changes", List.of(change))))).containsExactly("effectiveTime");
    }

    @Test
    void aProtectedGrantRequestWithAnEffectiveTimeOutOfRangeIsABadRequest() {
        String user = String.valueOf(insert(SecurityEntities.USER_DATASET, Map.of("userName", unique("u"),
            "displayName", "u", "enabled", true), null).get("id"));
        String role = createRole(unique("R"), "price.read");
        String bearer = TestTokens.bearer(tokens, "it-admin", "*");
        Map<String, Object> assign = post("/api/processes/SEC_USER_ROLE_ASSIGN/latest", bearer, Map.of("userId", user,
            "roleId", role, "reason", "r", "effectiveTime", FAR)).expectStatus().isBadRequest().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(fields(assign)).contains("effectiveTime");
        Map<String, Object> add = post("/api/processes/SEC_ROLE_PERMISSION_ADD/latest", bearer, Map.of("roleId", role,
            "permission", "ledger.read", "reason", "r", "effectiveTime", FAR)).expectStatus().isBadRequest()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(fields(add)).contains("effectiveTime");
    }

    @Test
    void aDataExportBoundOutOfRangeIsABadRequest() {
        String exporter = TestTokens.bearer(tokens, "it-exporter", "*");
        Map<String, Object> problem = post("/api/exports/data", exporter, Map.of("datasets",
            List.of(ItRetentionFixtures.RECORD_DATASET), "reports", true, "reportsFrom", "-5000-01-01T00:00:00Z",
            "reportsTo", FAR)).expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(fields(problem)).contains("reportsFrom", "reportsTo");
        Map<String, Object> asOf = post("/api/exports/data", exporter, Map.of("datasets",
            List.of(ItRetentionFixtures.RECORD_DATASET), "asOf", FAR)).expectStatus().isBadRequest()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(fields(asOf)).containsExactly("asOf");
    }
}

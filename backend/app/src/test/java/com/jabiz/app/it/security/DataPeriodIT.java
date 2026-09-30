package com.jabiz.app.it.security;

import com.jabiz.context.DataPeriod;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data periods (docs/design/10-security.md section 13.2, decision D28 item 8), on the ledger: an auditor whose role
 * assignment covers fiscal 2026 sees the transactions and entries booked in it, the reports over them and the audit
 * trail recorded in it, and nothing else; the period comes with the sign-in and every refresh; one assignment without
 * a period lifts the limit; writes outside the period are refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DataPeriodIT extends SecurityItSupport {

    private static final Instant FY2026 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FY2027 = Instant.parse("2027-01-01T00:00:00Z");
    private static final DataPeriod FISCAL_2026 = new DataPeriod(FY2026, FY2027);
    private static final Instant IN_2025 = Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant IN_2026 = Instant.parse("2026-01-15T00:00:00Z");

    private static final String TRANSACTIONS = LedgerEntities.TRANSACTION_DATASET;
    private static final String ENTRIES = LedgerEntities.ENTRY_DATASET;

    private String prefix() {
        return "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) + "-";
    }

    private void openAccounts(String prefix) {
        for (String code : List.of("1100", "4100")) {
            insert(LedgerEntities.ACCOUNT_DATASET, Map.of("accountCode", prefix + code, "accountName", code,
                "accountType", code.startsWith("4") ? "REVENUE" : "ASSET", "enabled", true), null);
        }
    }

    @SuppressWarnings("unchecked")
    private String postAt(String authorization, String prefix, Instant bookingTime, String amount) {
        Map<String, Object> result = post("/api/processes/LEDGER_POST/latest", authorization, posting(prefix,
            bookingTime, amount)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("transactionId");
    }

    private static Map<String, Object> posting(String prefix, Instant bookingTime, String amount) {
        return Map.of("bookingTime", bookingTime.toString(), "description", "period test", "entries", List.of(
            Map.of("accountCode", prefix + "1100", "direction", "DEBIT", "amount", amount),
            Map.of("accountCode", prefix + "4100", "direction", "CREDIT", "amount", amount)));
    }

    private String auditor(String... permissions) {
        return TestTokens.withinPeriod(tokens, FISCAL_2026, unique("auditor"), permissions);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entriesOf(String authorization, String transactionId) {
        Map<String, Object> page = post("/api/datasets/" + ENTRIES + "/query", authorization, Map.of("filters",
            List.of(Map.of("field", "transactionId", "op", "EQ", "value", transactionId))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAuditorSeesTheLedgerOfTheirPeriodOnly() {
        String prefix = prefix();
        openAccounts(prefix);
        String old = postAt(admin(), prefix, IN_2025, "70");
        String current = postAt(admin(), prefix, IN_2026, "30");
        String auditor = auditor("ledger.read", "ledger.account.read");

        get("/api/datasets/" + TRANSACTIONS + "/entities/" + old, auditor).expectStatus().isNotFound();
        get("/api/datasets/" + TRANSACTIONS + "/entities/" + current, auditor).expectStatus().isOk();
        assertThat(entriesOf(auditor, old)).isEmpty();
        assertThat(entriesOf(auditor, current)).hasSize(2);
        // Without a period, everything.
        assertThat(entriesOf(bearer("ledger.read"), old)).hasSize(2);

        // Templates read the datasets as the caller may (decision D10): the trial balance over the period only.
        Map<String, Object> balances = post("/api/queries/jabiz.ledger.account_balances", auditor, Map.of(
            "params", Map.of("asOf", FY2027.toString()),
            "filters", List.of(Map.of("field", "accountCode", "op", "EQ", "value", prefix + "1100"))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) balances.get("items")).singleElement()
            .satisfies(row -> assertThat(new BigDecimal(String.valueOf(row.get("balance"))))
                .isEqualByComparingTo("30"));

        Map<String, Object> me = get("/api/auth/me", auditor).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(me).containsEntry("dataFrom", FY2026.toString()).containsEntry("dataTo", FY2027.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAuditTrailIsReadByItsRecordingTime() {
        String prefix = prefix();
        openAccounts(prefix);
        String transaction = postAt(admin(), prefix, IN_2026, "5");
        String now = TestTokens.withinPeriod(tokens, new DataPeriod(clock.instant().minusSeconds(3600), null),
            "auditor-now", "audit.read");
        String past = TestTokens.withinPeriod(tokens, new DataPeriod(null, clock.instant().minusSeconds(3600)),
            "auditor-past", "audit.read");

        Map<String, Object> seen = get("/api/audit/records?entityId=" + transaction, now).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Object>) seen.get("items")).isNotEmpty();
        long recordNo = ((Number) ((Map<String, Object>) ((List<Object>) seen.get("items")).getFirst())
            .get("recordNo")).longValue();
        get("/api/audit/records?entityId=" + transaction, past).expectStatus().isOk().expectBody(MAP)
            .value(body -> assertThat(body).containsEntry("total", 0));
        get("/api/audit/records/" + recordNo, past).expectStatus().isNotFound();
        get("/api/audit/records/" + recordNo, now).expectStatus().isOk();
    }

    @Test
    void writesOutsideThePeriodAreRefused() {
        String prefix = prefix();
        openAccounts(prefix);
        String poster = TestTokens.withinPeriod(tokens, FISCAL_2026, "period-poster", "ledger.post");

        Map<String, Object> problem = post("/api/processes/LEDGER_POST/latest", poster, posting(prefix, IN_2025, "1"))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("OUT_OF_SCOPE");
        postAt(poster, prefix, IN_2026, "1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePeriodComesFromTheRoleAssignmentsAtSignInAndRefresh() {
        String prefix = prefix();
        openAccounts(prefix);
        String old = postAt(admin(), prefix, IN_2025, "9");
        String userName = unique("aud");
        String userId = createUser(userName, "correct-horse-battery");
        String role = createRole(unique("AUDITOR"), "ledger.read");
        assignWithin(userId, role, FY2026, FY2027);

        Map<String, Object> session = signIn(userName, "correct-horse-battery");
        get("/api/datasets/" + TRANSACTIONS + "/entities/" + old, bearerOf(session)).expectStatus().isNotFound();
        Map<String, Object> refreshed = post("/api/auth/refresh", null,
            Map.of("refreshToken", session.get("refreshToken"))).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        get("/api/datasets/" + TRANSACTIONS + "/entities/" + old, bearerOf(refreshed)).expectStatus().isNotFound();

        // A second, earlier period widens it to the span of both.
        assignWithin(userId, createRole(unique("AUDITOR"), "ledger.read"), Instant.parse("2025-01-01T00:00:00Z"),
            Instant.parse("2025-07-01T00:00:00Z"));
        Map<String, Object> wider = signIn(userName, "correct-horse-battery");
        get("/api/datasets/" + TRANSACTIONS + "/entities/" + old, bearerOf(wider)).expectStatus().isOk();
        assertThat(get("/api/auth/me", bearerOf(wider)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("dataFrom", "2025-01-01T00:00:00Z")
            .containsEntry("dataTo", FY2027.toString());

        // One assignment without a period: not limited at all.
        assign(userId, createRole(unique("CLERK"), "it.read"), null);
        Map<String, Object> unlimited = signIn(userName, "correct-horse-battery");
        assertThat(get("/api/auth/me", bearerOf(unlimited)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("dataFrom", null).containsEntry("dataTo", null);

        Map<String, Object> reversed = new LinkedHashMap<>(Map.of("userId", userId,
            "roleId", createRole(unique("LATE"), "ledger.read"),
            "dataFrom", FY2027.toString(), "dataTo", FY2026.toString()));
        Map<String, Object> refused = post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", reversed))))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(refused)).extracting(v -> v.get("ruleCode")).contains("DATA_PERIOD_ORDER");
    }

    private void assignWithin(String userId, String roleId, Instant from, Instant to) {
        insert(SecurityEntities.USER_ROLE_DATASET, Map.of("userId", userId, "roleId", roleId,
            "dataFrom", from.toString(), "dataTo", to.toString()), null);
    }
}

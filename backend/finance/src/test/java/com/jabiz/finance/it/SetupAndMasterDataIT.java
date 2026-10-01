package com.jabiz.finance.it;

import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.setup.SetupProcesses;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Setting up the books (docs/finance/00-design.md section 4.6) and the master data of F1: {@code FIN_SETUP} creates the
 * roles and the functional currency and can run again; dimension values validate ledger lines (FIN-GL-006);
 * currencies and exchange rates keep the history of corrections (FIN-FX-001, FIN-FX-002).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SetupAndMasterDataIT extends FinanceItSupport {

    private String administrator() {
        return as("sysadmin", "fin.setup");
    }

    private Map<String, Object> commit(String dataset, String authorization, Map<String, Object> change) {
        return post("/api/datasets/" + dataset + "/commit", authorization, Map.of("changes", List.of(change)))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody().getFirst();
    }

    @Test
    void setupCreatesTheRolesAndTheFunctionalCurrencyOnce() {
        Map<String, Object> first = ok(SetupProcesses.SETUP, administrator(), Map.of());
        assertThat(first).containsEntry("currencyCreated", true);
        assertThat((List<?>) first.get("rolesCreated")).hasSize(9);

        Set<String> ledgerWriters = new HashSet<>();
        for (FinanceRoles.Role role : FinanceRoles.all()) {
            Map<String, Object> stored = find(SecurityEntities.ROLE_DATASET, "roleCode", role.code()).getFirst();
            Set<Object> permissions = new HashSet<>(find(SecurityEntities.ROLE_PERMISSION_DATASET, "roleId",
                stored.get("roleId")).stream().map(p -> p.get("permission")).toList());
            assertThat(permissions).as(role.code()).containsExactlyInAnyOrderElementsOf(role.permissions());
            permissions.stream().map(String::valueOf)
                .filter(p -> p.equals("ledger.post") || p.equals("ledger.reverse") || p.equals("ledger.account.write")
                    || p.equals("*"))
                .forEach(ledgerWriters::add);
        }
        // The general ledger is written only by the finance processes (design section 4.6).
        assertThat(ledgerWriters).isEmpty();
        assertThat(find(GlEntities.CURRENCY_DATASET, "currencyCode", "USD")).singleElement()
            .satisfies(usd -> assertThat(usd).containsEntry("minorUnits", 2).containsEntry("active", true));

        // Again: nothing is missing, nothing changes.
        Map<String, Object> again = ok(SetupProcesses.SETUP, administrator(), Map.of());
        assertThat(again).containsEntry("currencyCreated", false).containsEntry("permissionsAdded", 0);
        assertThat((List<?>) again.get("rolesCreated")).isEmpty();
        assertThat(find(SecurityEntities.ROLE_DATASET, "roleCode", FinanceRoles.CONTROLLER)).hasSize(1);
    }

    @Test
    void setupIsAdministrationAndNeedsTheSecondFactor() {
        run(SetupProcesses.SETUP, TestTokens.withoutMfa(tokens, "sysadmin", "fin.setup"), Map.of())
            .expectStatus().isForbidden();
        run(SetupProcesses.SETUP, as("controller", "fin.account.maintain"), Map.of()).expectStatus().isForbidden();
    }

    /** FIN-GL-006: dimension values come from their lists. */
    @Test
    void ledgerLinesTakeListedDimensionValues() {
        String dimensions = as("controller", "fin.master.read", "fin.dimension.maintain");
        commit(GlEntities.DEPARTMENT_DATASET, dimensions, Map.of("action", "INSERT", "attributes",
            Map.of("departmentCode", "SALES", "departmentName", "Sales", "active", true)));
        commit(GlEntities.LOCATION_DATASET, dimensions, Map.of("action", "INSERT", "attributes",
            Map.of("locationCode", "CHI", "locationName", "Chicago", "active", true)));
        ok(AccountProcesses.CREATE, controller(), Map.of("accountCode", "6900", "accountName", "Supplies",
            "financialType", "EXPENSE", "normalBalance", "DEBIT", "statementLine", "Operating expenses",
            "requiredDimension", "department"));
        ok(AccountProcesses.CREATE, controller(), Map.of("accountCode", "1010", "accountName", "Cash",
            "financialType", "ASSET", "normalBalance", "DEBIT", "statementLine", "Cash"));

        String poster = as("poster", "ledger.post");
        ok("LEDGER_POST", poster, posting(Map.of("department", "SALES", "location", "CHI")));
        assertThat(refused("LEDGER_POST", poster, posting(Map.of("department", "MARKETING")), 422))
            .isEqualTo("LEDGER_DIMENSION_INVALID");
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", "6900").getFirst())
            .containsEntry("requiredDimension", "department");
        assertThat(refused(AccountProcesses.CREATE, controller(), Map.of("accountCode", "6910", "accountName", "x",
            "financialType", "EXPENSE", "normalBalance", "DEBIT", "statementLine", "x",
            "requiredDimension", "project"), 422)).isEqualTo(AccountProcesses.INVALID_VALUE);
    }

    private static Map<String, Object> posting(Map<String, String> dimensions) {
        return Map.of("description", "supplies", "entries", List.of(
            Map.of("accountCode", "6900", "direction", "DEBIT", "amount", "40.00", "dimensions", dimensions),
            Map.of("accountCode", "1010", "direction", "CREDIT", "amount", "40.00")));
    }

    /** FIN-FX-001 and FIN-FX-002 acceptance 1 and 2. */
    @Test
    void exchangeRatesLoadAndKeepTheirCorrections() {
        String treasurer = as("treasurer", "fin.master.read", "fin.fx.maintain");
        commit(GlEntities.CURRENCY_DATASET, treasurer, Map.of("action", "INSERT", "attributes",
            Map.of("currencyCode", "EUR", "currencyName", "Euro", "minorUnits", 2, "active", true)));
        // Rates are entered through FIN_EXCHANGE_RATE_SET, as the import enters them (FIN-DI-001).
        for (Map<String, String> row : sample("fx-rates.csv")) {
            ok("FIN_EXCHANGE_RATE_SET", treasurer, Map.of("fromCurrency", "EUR", "toCurrency", "USD",
                "rateDate", row.get("date"), "rate", row.get("eur_usd")));
        }
        assertThat(find(GlEntities.EXCHANGE_RATE_DATASET, "fromCurrency", "EUR"))
            .extracting(r -> r.get("rateDate")).containsExactlyInAnyOrder("2026-01-12", "2026-01-31", "2026-02-20");
        assertThat(find(GlEntities.CURRENCY_DATASET, "currencyCode", "EUR").getFirst()).containsEntry("minorUnits", 2);

        Map<String, Object> rate = find(GlEntities.EXCHANGE_RATE_DATASET, "rateDate", "2026-01-31").getFirst();
        clock.advance(Duration.ofDays(1));
        assertThat(ok("FIN_EXCHANGE_RATE_SET", as("treasurer-2", "fin.master.read", "fin.fx.maintain"),
            Map.of("fromCurrency", "EUR", "toCurrency", "USD", "rateDate", "2026-01-31", "rateType", "SPOT",
                "rate", "1.0930"))).containsEntry("changed", true).containsEntry("rateId", rate.get("rateId"));
        assertThat(new BigDecimal(String.valueOf(find(GlEntities.EXCHANGE_RATE_DATASET, "rateDate", "2026-01-31")
            .getFirst().get("rate")))).isEqualByComparingTo("1.0930");
        List<Map<String, Object>> history = get("/api/datasets/" + GlEntities.EXCHANGE_RATE_DATASET + "/entities/"
            + rate.get("rateId") + "/history", as("auditor", "*")).expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(history).hasSize(2);
        assertThat(history.toString()).contains("1.092").contains("1.093").contains("treasurer-2");

        // One rate per pair, day and type: setting the same one again changes nothing; a rate converts between two
        // currencies; the dataset takes no writes but through the process.
        treasurer = as("treasurer", "fin.master.read", "fin.fx.maintain");
        assertThat(ok("FIN_EXCHANGE_RATE_SET", treasurer, Map.of("fromCurrency", "EUR", "toCurrency", "USD",
            "rateDate", "2026-01-31", "rate", "1.093"))).containsEntry("changed", false);
        assertThat(refused("FIN_EXCHANGE_RATE_SET", treasurer, Map.of("fromCurrency", "USD", "toCurrency", "USD",
            "rateDate", "2026-01-31", "rateType", "CLOSING", "rate", "1"), 422))
            .isEqualTo(GlEntities.EXCHANGE_RATE_SAME_CURRENCY);
        assertThat(commitRefused(GlEntities.EXCHANGE_RATE_DATASET, treasurer, Map.of("action", "INSERT",
            "attributes", Map.of("fromCurrency", "EUR", "toCurrency", "USD", "rateDate", "2026-03-01",
                "rateType", "SPOT", "rate", "1.1")))).isEqualTo("PROCESS_ONLY_DATASET");
        assertOnlyInserted("fi_currency_version", "fi_exchange_rate_version", "fi_department_version",
            "fi_location_version");
    }
}

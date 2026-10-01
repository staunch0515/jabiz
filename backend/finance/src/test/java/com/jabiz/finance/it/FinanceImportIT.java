package com.jabiz.finance.it;

import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Master-data imports (FIN-DI-001): the sample company's chart of accounts and exchange rates through the finance
 * imports, every row by the process used for entering it by hand; a file with one wrong row is refused whole, with
 * the row and the reason, and changes nothing; a file is imported once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinanceImportIT extends FinanceItSupport {

    private String controllerRole() {
        return inRoles("import-controller", FinanceRoles.CONTROLLER);
    }

    /** FIN-DI-001 acceptance 1 for the chart: the sample file as it is, 0 rejected rows. */
    @Test
    void theSampleChartImportsAsItIs() {
        String chart = sampleText("chart-of-accounts.csv");
        Map<String, Object> preview = importCsv("finance.chart", controllerRole(), chart, "preview", null, null, 200);
        assertThat(preview).containsEntry("accepted", true).containsEntry("records", 36).containsEntry("units", 36);
        assertThat(issues(preview)).isEmpty();
        // A preview leaves nothing behind.
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", "1010")).isEmpty();

        Map<String, Object> committed = importCsv("finance.chart", controllerRole(), chart, "commit", null, null, 200);
        assertThat(committed).containsEntry("committed", true).containsEntry("processed", 36);
        for (Map<String, String> row : sample("chart-of-accounts.csv")) {
            assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", row.get("code"))).as(row.get("code"))
                .singleElement().satisfies(account -> assertThat(account.toString())
                    .contains(row.get("type").toUpperCase(java.util.Locale.ROOT)));
        }
        // The same file again: imported already, nothing runs.
        importCsv("finance.chart", controllerRole(), chart, "commit", null, null, 409);
    }

    /** FIN-DI-001 acceptance 2: one wrong row refuses the whole file, with the row and the reason. */
    @Test
    void aFileWithOneWrongRowChangesNothing() {
        String chart = """
            code,name,type,normal_balance,statement_line
            9100,Clearing - Imports,Asset,D,Other assets
            9101,Something,Bogus,D,Other assets
            9102,Other clearing,Liability,X,Other liabilities
            """;
        Map<String, Object> refused = importCsv("finance.chart", controllerRole(), chart, "commit", null, null, 422);
        assertThat(refused).containsEntry("committed", false);
        assertThat(issues(refused)).anySatisfy(issue -> assertThat(issue).startsWith("2:"))
            .anySatisfy(issue -> assertThat(issue).startsWith("3:"));
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", "9100")).isEmpty();
    }

    @Test
    void exchangeRatesImportFromAColumnPerPairAndCorrectEarlierOnes() {
        ok("FIN_SETUP", as("sysadmin", "fin.setup"), Map.of());
        post("/api/datasets/" + GlEntities.CURRENCY_DATASET + "/commit", controller(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("currencyCode", "EUR", "currencyName", "Euro",
                "minorUnits", 2, "active", true))))).expectStatus().isOk();

        // The sample file has a column per pair: the mapping names it and gives the currencies.
        Map<String, Object> mapping = Map.of("columns", Map.of("rateDate", "date", "rate", "eur_usd"),
            "constants", Map.of("fromCurrency", "EUR", "toCurrency", "USD"));
        Map<String, Object> committed = importCsv("finance.fx_rates", controllerRole(), sampleText("fx-rates.csv"),
            "commit", mapping, null, 200);
        assertThat(committed).containsEntry("committed", true).containsEntry("processed", 3);
        for (Map<String, String> row : sample("fx-rates.csv")) {
            assertThat(find(GlEntities.EXCHANGE_RATE_DATASET, "rateDate", row.get("date"))).singleElement()
                .satisfies(rate -> assertThat(new BigDecimal(String.valueOf(rate.get("rate"))))
                    .isEqualByComparingTo(row.get("eur_usd")));
        }

        // A later file corrects a rate; the earlier value stays in its history.
        String correction = """
            date,from,to,type,rate
            2026-01-31,EUR,USD,SPOT,1.0925
            """;
        importCsv("finance.fx_rates", controllerRole(), correction, "commit", null, null, 200);
        Map<String, Object> rate = find(GlEntities.EXCHANGE_RATE_DATASET, "rateDate", "2026-01-31").getFirst();
        assertThat(new BigDecimal(String.valueOf(rate.get("rate")))).isEqualByComparingTo("1.0925");
        assertThat(get("/api/datasets/" + GlEntities.EXCHANGE_RATE_DATASET + "/entities/" + rate.get("rateId")
            + "/history", controllerRole()).expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody())
            .hasSize(2);

        // An unknown currency refuses the file; the dataset itself takes no writes but through the process.
        Map<String, Object> refused = importCsv("finance.fx_rates", controllerRole(), """
            date,from,to,rate
            2026-02-01,EUR,USD,1.0810
            2026-02-01,GBP,USD,1.2500
            """, "commit", null, null, 422);
        assertThat(issues(refused)).containsExactly("2:FIN_EXCHANGE_RATE_UNKNOWN_CURRENCY");
        assertThat(find(GlEntities.EXCHANGE_RATE_DATASET, "rateDate", "2026-02-01")).isEmpty();
        assertThat(commitRefused(GlEntities.EXCHANGE_RATE_DATASET, controller(), Map.of("action", "INSERT",
            "attributes", Map.of("fromCurrency", "EUR", "toCurrency", "USD", "rateDate", "2026-02-02",
                "rateType", "SPOT", "rate", 1.08)))).isEqualTo("PROCESS_ONLY_DATASET");
    }
}

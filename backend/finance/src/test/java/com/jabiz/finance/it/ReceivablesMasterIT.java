package com.jabiz.finance.it;

import com.jabiz.finance.ar.ArEntities;
import com.jabiz.finance.ar.ArSettingsProcesses;
import com.jabiz.finance.ar.CustomerProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.migration.MigrationProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.tax.TaxEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receivables' master data (ROADMAP F3a): the sample company's customers and tax codes imported as they are,
 * a customer's address changing from a later day, tax rates over time, the receivables settings, merging duplicate
 * legacy customers, and the certificates to look after.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ReceivablesMasterIT extends FinanceItSupport {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    /** The schema lives as long as the class: the books, the sample's tax codes and customers are loaded once. */
    private static boolean loaded;

    private String clerk;
    private String controller;

    @BeforeEach
    void books() {
        clerk = inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK);
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        if (loaded) {
            return;
        }
        loaded = true;
        openBooks();
        post("/api/datasets/" + GlEntities.CURRENCY_DATASET + "/commit", controller(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("currencyCode", "EUR", "currencyName", "Euro",
                "minorUnits", 2, "active", true))))).expectStatus().isOk();
        importTaxCodes();
        importCustomers();
    }

    private void importTaxCodes() {
        Map<String, Object> report = importCsv("finance.tax_codes", controller, sampleText("tax-codes.csv"), "commit",
            null, Map.of("ratesFrom", "2025-01-01"), 200);
        assertThat(report).containsEntry("committed", true);
    }

    private void importCustomers() {
        // The sample has exempt and non-taxable customers and a certificate: the controller's (fin.customer.tax).
        Map<String, Object> report = importCsv("finance.customers", controller, sampleText("customers.csv"),
            "commit", null, null, 200);
        assertThat(report).containsEntry("committed", true).containsEntry("units", 4);
    }

    @Test
    void theSampleFilesImportAsTheyAre() {
        Map<String, Map<String, Object>> customers = new LinkedHashMap<>();
        for (String code : List.of("C100", "C200", "C300", "C400")) {
            customers.put(code, attributes(find(ArEntities.CUSTOMER_DATASET, "customerCode", code).getFirst()));
        }
        assertThat(customers.get("C100")).containsEntry("currency", "USD").containsEntry("termsCode", "NET30")
            .containsEntry("taxCode", "TX-AUSTIN").containsEntry("shippingState", "TX")
            .containsEntry("shippingCity", "Austin").containsEntry("legalName", "Acme Robotics, Inc.");
        assertThat(customers.get("C200")).containsEntry("taxCode", "OR-NONE").containsEntry("shippingState", "OR");
        assertThat(customers.get("C300")).containsEntry("taxCode", "TX-RESALE");
        assertThat(customers.get("C400")).containsEntry("currency", "EUR").containsEntry("taxCode", "EXPORT")
            .containsEntry("shippingCountry", "Germany");
        assertThat(attributes(find(ArEntities.PAYMENT_TERMS_DATASET, "termsCode", "NET30").getFirst()))
            .containsEntry("netDays", 30);
        assertThat(find(ArEntities.CERTIFICATE_DATASET, "customerCode", "C300").stream()
            .map(ReceivablesMasterIT::attributes).filter(c -> "RC-3301".equals(c.get("certificateNo"))))
            .singleElement().satisfies(c -> assertThat(c).containsEntry("state", "TX")
                .containsEntry("certificateType", "RESALE").containsEntry("expiryDate", "2027-12-31"));

        // TX-AUSTIN is the state's 6.25 % and the city's 2.00 % (FIN-TX-001 acceptance 1).
        assertThat(attributes(find(TaxEntities.CODE_DATASET, "taxCode", "TX-AUSTIN").getFirst()))
            .containsEntry("kind", "TAXABLE").containsEntry("jurisdictions", "TX,TX-AUSTIN-LOCAL")
            .containsEntry("state", "TX");
        assertThat(rate("TX")).isEqualByComparingTo("6.25");
        assertThat(rate("TX-AUSTIN-LOCAL")).isEqualByComparingTo("2.00");
        assertThat(attributes(find(TaxEntities.CODE_DATASET, "taxCode", "TX-RESALE").getFirst()))
            .containsEntry("kind", "EXEMPT").containsEntry("certificateRequired", true)
            .containsEntry("reason", "RESALE");
        assertThat(attributes(find(TaxEntities.CODE_DATASET, "taxCode", "NT").getFirst()))
            .containsEntry("kind", "NON_TAXABLE").containsEntry("reason", "NON_TAXABLE_SERVICE");

        // The same files again are refused as imported already; nothing doubles.
        importCsv("finance.customers", controller, sampleText("customers.csv"), "commit", null, null, 409);
        assertThat(find(ArEntities.CUSTOMER_DATASET, "customerCode", "C100")).hasSize(1);
        assertOnlyInserted("fi_customer_version", "fi_payment_terms_version", "fi_exemption_certificate_version",
            "fi_tax_code_version", "fi_tax_jurisdiction_version", "fi_tax_rate_version");
    }

    @Test
    void oneInvalidRowRefusesTheWholeFile() {
        Map<String, Object> report = importCsv("finance.customers", clerk, """
            id,name,location,currency,tax_code,terms_days
            C900,Good Customer,TX (Austin),USD,TX-AUSTIN,30
            C901,Bad Customer,TX (Austin),USD,TX-NOWHERE,30
            """, "commit", null, null, 422);
        assertThat(issues(report)).contains("2:" + CustomerProcesses.UNKNOWN_TAX_CODE);
        assertThat(find(ArEntities.CUSTOMER_DATASET, "customerCode", "C900")).isEmpty();
    }

    @Test
    void anAddressChangesFromALaterDayAndThePastIsNotRewritten() {
        Map<String, Object> customer = new HashMap<>();
        customer.put("customerCode", "C500");
        customer.put("legalName", "Hill Country Tools LLC");
        customer.put("billing", Map.of("street", "100 Congress Ave", "city", "Austin", "state", "TX",
            "postalCode", "78701", "country", "US"));
        customer.put("currency", "USD");
        customer.put("termsDays", 30);
        customer.put("taxCode", "TX-AUSTIN");
        // As FIN-AR-001 acceptance 2, a month on (the test books open on 31 January): entered on 2 February, the
        // address changed on 10 February from 1 March.
        clock.set(Instant.parse("2026-02-02T15:00:00Z"));
        clerk = inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK);
        assertThat(ok(CustomerProcesses.SAVE, clerk, customer)).containsEntry("created", true);
        clock.set(Instant.parse("2026-02-10T15:00:00Z"));
        clerk = inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK);
        ok(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C500", "effectiveDate", "2026-03-01",
            "billing", Map.of("street", "500 Lamar Blvd", "city", "Austin", "state", "TX", "postalCode", "78703",
                "country", "US")));

        // An invoice dated 2026-02-15 reads the old address, one dated from the first the new.
        assertThat(asOf("C500", LocalDate.of(2026, 2, 15))).containsEntry("billingStreet", "100 Congress Ave")
            .containsEntry("billingPostalCode", "78701");
        assertThat(asOf("C500", LocalDate.of(2026, 3, 1))).containsEntry("billingStreet", "500 Lamar Blvd")
            .containsEntry("billingPostalCode", "78703").containsEntry("legalName", "Hill Country Tools LLC");
        // Today counts as now; the past is not rewritten.
        LocalDate today = LocalDate.of(2026, 2, 10);
        assertThat(ok(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C500", "effectiveDate", today.toString(),
            "contactName", "Dana Ruiz"))).containsEntry("changed", true);
        assertThat(refused(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C500", "effectiveDate",
            today.minusDays(1).toString(), "contactName", "Back Dated"), 422)).isEqualTo(CustomerProcesses.PAST_DATE);
        assertThat(refused(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C501"), 422))
            .isEqualTo(CustomerProcesses.MISSING);

        // One change waits at a time: another from a later day is refused until the first is cancelled or in effect,
        // rather than compared with today's address and dropped.
        run(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C500", "effectiveDate", "2026-04-01",
            "billing", Map.of("street", "100 Congress Ave", "postalCode", "78701"))).expectStatus().isEqualTo(409);
        // Once 1 March has come, going back to the old street from 1 April is written, though it is no change from
        // the address of the day the change is made.
        clock.set(Instant.parse("2026-03-02T15:00:00Z"));
        clerk = inRoles("clerk", FinanceRoles.RECEIVABLES_CLERK);
        assertThat(ok(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C500", "effectiveDate", "2026-04-01",
            "billing", Map.of("street", "100 Congress Ave", "postalCode", "78701")))).containsEntry("changed", true);
        assertThat(asOf("C500", LocalDate.of(2026, 3, 15))).containsEntry("billingStreet", "500 Lamar Blvd");
        assertThat(asOf("C500", LocalDate.of(2026, 4, 1))).containsEntry("billingStreet", "100 Congress Ave")
            .containsEntry("billingCity", "Austin");
        // An empty text clears the contact.
        ok(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C500", "contactName", ""));
        assertThat(asOf("C500", LocalDate.of(2026, 3, 3))).containsEntry("contactName", null);
    }

    @Test
    void aRateFromALaterDayEndsTheEarlierOne() {
        ok("FIN_TAX_RATE_SET", controller, Map.of("jurisdictionCode", "TX", "effectiveFrom", "2026-04-01",
            "ratePercent", "6.50"));
        List<Map<String, Object>> rates = find(TaxEntities.RATE_DATASET, "jurisdictionCode", "TX").stream()
            .map(ReceivablesMasterIT::attributes)
            .sorted(java.util.Comparator.comparing(r -> (String) r.get("effectiveFrom"))).toList();
        assertThat(rates).extracting(r -> r.get("effectiveFrom") + ".." + r.get("effectiveTo") + " "
            + amount(r.get("ratePercent")).stripTrailingZeros().toPlainString())
            .containsExactly("2025-01-01..2026-03-31 6.25", "2026-04-01..null 6.5");
        // Tax master data is the controller's: a clerk is refused.
        assertThat(refused("FIN_TAX_RATE_SET", clerk, Map.of("jurisdictionCode", "TX", "effectiveFrom", "2026-05-01",
            "ratePercent", "7"), 403)).contains("PERMISSION");
        assertThat(refused("FIN_TAX_RATE_SET", controller, Map.of("jurisdictionCode", "ZZ", "effectiveFrom",
            "2026-05-01", "ratePercent", "7"), 422)).contains("FIN_TAX_UNKNOWN_JURISDICTION");
    }

    @Test
    void theSettingsNameTheRightAccounts() {
        Map<String, Object> settings = new HashMap<>(Map.of("receivableAccount", "1200", "allowanceAccount", "1210",
            "returnsAccount", "4900", "salesTaxAccount", "2200"));
        assertThat(ok(ArSettingsProcesses.SET, controller, settings)).containsEntry("changed", true);
        assertThat(ok(ArSettingsProcesses.SET, controller, settings)).containsEntry("changed", false);
        assertThat(attributes(find(ArEntities.SETTINGS_DATASET, "settingsKey", "AR").getFirst()))
            .containsEntry("missingCertificate", "BLOCK").containsEntry("creditLimitCheck", "WARN");

        for (Map.Entry<String, String> wrong : Map.of("receivableAccount", "1010", "unappliedCashAccount", "2100",
            "salesTaxAccount", "1200").entrySet()) {
            Map<String, Object> input = new HashMap<>(settings);
            input.put(wrong.getKey(), wrong.getValue());
            assertThat(refused(ArSettingsProcesses.SET, controller, input, 422)).as(wrong.getKey())
                .isEqualTo(ArSettingsProcesses.WRONG_ACCOUNT);
        }
        Map<String, Object> unknown = new HashMap<>(settings);
        unknown.put("discountAccount", "9999");
        assertThat(refused(ArSettingsProcesses.SET, controller, unknown, 422))
            .isEqualTo(ArSettingsProcesses.UNKNOWN_ACCOUNT);
        assertThat(refused(ArSettingsProcesses.SET, clerk, settings, 403)).contains("PERMISSION");
    }

    @Test
    void aMergedLegacyCustomerIsNotCreatedAndTheDecisionIsReported() {
        ok(MigrationProcesses.DECIDE, controller, Map.of("kind", "CUSTOMER", "legacyValue", "c100-dup",
            "decidedValue", "C100", "reason", "Acme Robotics twice in the legacy system"));
        assertThat(refused(MigrationProcesses.DECIDE, controller, Map.of("kind", "CUSTOMER", "legacyValue", "C200",
            "decidedValue", "C100", "reason", "x"), 422)).contains(MigrationProcesses.LEGACY_IS_CUSTOMER);
        assertThat(refused(MigrationProcesses.DECIDE, controller, Map.of("kind", "CUSTOMER", "legacyValue", "C999",
            "decidedValue", "C998", "reason", "x"), 422)).contains(MigrationProcesses.UNKNOWN_CUSTOMER);

        Map<String, Object> report = importCsv("finance.customers", clerk, """
            id,name,location,currency,tax_code,terms_days
            C100-DUP,"Acme Robotics Inc",TX (Austin),USD,TX-AUSTIN,30
            C600,Gulf Coast Fabrication,TX (Houston),USD,TX-AUSTIN,30
            """, "commit", null, null, 200);
        assertThat(report).containsEntry("committed", true);
        assertThat(find(ArEntities.CUSTOMER_DATASET, "customerCode", "C100-DUP")).isEmpty();
        assertThat(find(ArEntities.CUSTOMER_DATASET, "customerCode", "C600")).hasSize(1);

        List<Map<String, Object>> decisions = report("finance.migration.reconciliation", controller, Map.of())
            .stream().filter(r -> "DECISION".equals(r.get("section"))).toList();
        assertThat(decisions).singleElement().satisfies(row -> {
            assertThat(row.get("item")).isEqualTo("CUSTOMER C100-DUP -> C100");
            assertThat(row.get("decidedBy")).isEqualTo("controller");
            assertThat(row.get("decidedAt")).isNotNull();
        });
    }

    @Test
    void termsAndCertificatesAreKeptThroughTheirProcesses() {
        Map<String, Object> terms = new HashMap<>(Map.of("termsCode", "2/10N30", "description", "2% 10, net 30",
            "netDays", 30, "discountPercent", "2.00", "discountDays", 10));
        assertThat(ok(CustomerProcesses.TERMS_SAVE, controller, terms)).containsEntry("changed", true);
        assertThat(ok(CustomerProcesses.TERMS_SAVE, controller, terms)).containsEntry("changed", false);
        assertThat(attributes(find(ArEntities.PAYMENT_TERMS_DATASET, "termsCode", "2/10N30").getFirst()))
            .containsEntry("discountDays", 10).containsEntry("endOfMonth", false);
        Map<String, Object> late = new HashMap<>(terms);
        late.put("discountDays", 40);
        assertThat(refused(CustomerProcesses.TERMS_SAVE, controller, late, 422)).isEqualTo("FIN_TERMS_DISCOUNT");
        assertThat(refused(CustomerProcesses.TERMS_SAVE, clerk, terms, 403)).isEqualTo("PERMISSION_DENIED");

        Map<String, Object> certificate = new HashMap<>(Map.of("state", "OR", "certificateNo", "OR-77",
            "certificateType", "EXEMPT_ORGANIZATION", "expiryDate", "2026-12-31"));
        assertThat(refused(CustomerProcesses.CERTIFICATE_SAVE, clerk, Map.of("customerCode", "C200",
            "certificate", certificate), 403)).isEqualTo("PERMISSION_DENIED");
        ok(CustomerProcesses.CERTIFICATE_SAVE, controller, Map.of("customerCode", "C200", "certificate",
            certificate));
        assertThat(find(ArEntities.CERTIFICATE_DATASET, "customerCode", "C200")).singleElement()
            .satisfies(c -> assertThat(attributes(c)).containsEntry("certificateNo", "OR-77"));
        certificate.put("certificateType", "SOMETHING");
        assertThat(refused(CustomerProcesses.CERTIFICATE_SAVE, controller, Map.of("customerCode", "C200",
            "certificate", certificate), 422)).isEqualTo(CustomerProcesses.INVALID_VALUE);
        assertThat(refused(CustomerProcesses.CERTIFICATE_SAVE, controller, Map.of("customerCode", "C999",
            "certificate", Map.of("state", "OR", "certificateNo", "X", "certificateType", "OTHER")), 422))
            .isEqualTo(CustomerProcesses.UNKNOWN_CUSTOMER);
        // A tax code whose charge code is not taxable is refused.
        assertThat(refused("FIN_TAX_CODE_SAVE", controller, Map.of("taxCode", "OR-EXEMPT", "description", "Oregon "
            + "exempt", "kind", "EXEMPT", "reason", "OTHER", "certificateRequired", true, "chargeCode", "NT"), 422))
            .isEqualTo("FIN_TAX_UNKNOWN_CHARGE_CODE");
        // Certificates are per state, and only for exempt codes.
        assertThat(refused("FIN_TAX_CODE_SAVE", controller, Map.of("taxCode", "ANY-EXEMPT", "description", "Exempt",
            "kind", "EXEMPT", "reason", "OTHER", "certificateRequired", true), 422)).isEqualTo("FIN_TAX_CODE_KIND");
        // A jurisdiction named twice would double its tax.
        assertThat(refused("FIN_TAX_CODE_SAVE", controller, Map.of("taxCode", "TX-TWICE", "description", "Twice",
            "kind", "TAXABLE", "state", "TX", "jurisdictions", List.of(Map.of("jurisdictionCode", "TX"),
                Map.of("jurisdictionCode", "TX"))), 422)).isEqualTo("FIN_TAX_DUPLICATE_JURISDICTION");
        // Another code of Texas imported with explicit columns shares the state's jurisdiction without renaming it.
        importCsv("finance.tax_codes", controller, """
            code,description,kind,state,jurisdictions
            TX-HOUSTON,Houston,TAXABLE,TX,TX:6.25;TX-HOUSTON-LOCAL:2.00
            """, "commit", null, Map.of("ratesFrom", "2025-01-01"), 200);
        assertThat(attributes(find(TaxEntities.JURISDICTION_DATASET, "jurisdictionCode", "TX").getFirst()))
            .containsEntry("jurisdictionName", "TX state").containsEntry("level", "STATE");
        // The state's rate stood at 6.25 % already: no second rate from the same day.
        assertThat(find(TaxEntities.RATE_DATASET, "jurisdictionCode", "TX").stream()
            .map(r -> attributes(r).get("effectiveFrom")).toList()).doesNotHaveDuplicates();
    }

    @Test
    void aClerkCannotMakeSalesTaxFreeRaiseCreditOrScheduleMoreThanAddresses() {
        Map<String, Object> customer = new HashMap<>(Map.of("customerCode", "C800", "legalName", "Lone Clerk Co",
            "currency", "USD", "termsDays", 30, "taxCode", "TX-AUSTIN"));
        ok(CustomerProcesses.SAVE, clerk, customer);
        // A tax code that charges no tax, a certificate, a credit limit: each needs a permission the clerk lacks.
        assertThat(refused(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C800", "taxCode", "EXPORT"), 422))
            .isEqualTo(CustomerProcesses.TAX_RESTRICTED);
        assertThat(refused(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C800", "certificate",
            Map.of("state", "TX", "certificateNo", "X-1", "certificateType", "RESALE")), 422))
            .isEqualTo(CustomerProcesses.TAX_RESTRICTED);
        assertThat(refused(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C800", "creditLimit", 1000000),
            422)).isEqualTo(CustomerProcesses.CREDIT_RESTRICTED);
        assertThat(refused(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C801", "legalName", "Exporter",
            "currency", "USD", "termsDays", 30, "taxCode", "EXPORT"), 422)).isEqualTo(CustomerProcesses.TAX_RESTRICTED);
        // Only addresses and the contact change from a later day.
        assertThat(refused(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C800", "effectiveDate",
            "2026-06-01", "taxCode", "TX-RESALE"), 422)).isEqualTo(CustomerProcesses.SCHEDULED_FIELD);
        // The controller may; resaving the same values needs nothing.
        ok(CustomerProcesses.SAVE, controller, Map.of("customerCode", "C800", "creditLimit", 100000,
            "taxCode", "EXPORT"));
        assertThat(ok(CustomerProcesses.SAVE, clerk, Map.of("customerCode", "C800", "creditLimit", 100000,
            "taxCode", "EXPORT", "contactName", "Sam"))).containsEntry("changed", true);
    }

    @Test
    void theCertificatesReportShowsMissingExpiringAndExpiredCertificates() {
        Map<String, Object> customer = new HashMap<>(Map.of("customerCode", "C700", "legalName", "Resale Without Paper",
            "currency", "USD", "termsDays", 30, "taxCode", "TX-RESALE"));
        ok(CustomerProcesses.SAVE, controller, customer);

        assertThat(statuses(LocalDate.of(2026, 1, 15))).containsExactly("MISSING C700");
        assertThat(statuses(LocalDate.of(2027, 12, 15))).containsExactly("EXPIRING C300", "MISSING C700");
        assertThat(statuses(LocalDate.of(2028, 1, 15))).containsExactly("EXPIRED C300", "MISSING C300",
            "MISSING C700");

        // Renewed: the old certificate is neither expiring nor expired any more.
        ok(CustomerProcesses.CERTIFICATE_SAVE, controller, Map.of("customerCode", "C300", "certificate", Map.of(
            "state", "TX", "certificateNo", "RC-3302", "certificateType", "RESALE", "issueDate", "2027-12-01",
            "expiryDate", "2029-12-31")));
        assertThat(statuses(LocalDate.of(2027, 12, 15))).containsExactly("MISSING C700");
        assertThat(statuses(LocalDate.of(2028, 1, 15))).containsExactly("MISSING C700");
    }

    private List<String> statuses(LocalDate onDate) {
        return report("finance.ar.certificates", clerk, Map.of("onDate", onDate.toString())).stream()
            .map(r -> r.get("status") + " " + r.get("customerCode")).sorted().toList();
    }

    private java.math.BigDecimal rate(String jurisdiction) {
        return amount(attributes(find(TaxEntities.RATE_DATASET, "jurisdictionCode", jurisdiction).getFirst())
            .get("ratePercent"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asOf(String customerCode, LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filters", List.of(Map.of("field", "customerCode", "op", "eq", "value", customerCode)));
        body.put("asOf", date.atStartOfDay(CHICAGO).toInstant().toString());
        Map<String, Object> page = post("/api/datasets/" + ArEntities.CUSTOMER_DATASET + "/query", clerk, body)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return attributes(((List<Map<String, Object>>) page.get("items")).getFirst());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> item) {
        return item.containsKey("attributes") ? (Map<String, Object>) item.get("attributes") : item;
    }
}

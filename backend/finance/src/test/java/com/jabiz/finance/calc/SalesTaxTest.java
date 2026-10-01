package com.jabiz.finance.calc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sales tax of a document (FIN-TX-001 … 004, FIN-UI-007), on the sample company's codes. */
class SalesTaxTest {

    private static final LocalDate JAN_6 = LocalDate.of(2026, 1, 6);

    private static final Map<String, SalesTax.Code> CODES = Map.of(
        "TX-AUSTIN", new SalesTax.Code("TX-AUSTIN", SalesTax.Kind.TAXABLE, null, "TX",
            List.of("TX", "TX-AUSTIN-LOCAL"), false, null),
        "TX-DALLAS", new SalesTax.Code("TX-DALLAS", SalesTax.Kind.TAXABLE, null, "TX",
            List.of("TX", "TX-DALLAS-LOCAL"), false, null),
        "TX-RESALE", new SalesTax.Code("TX-RESALE", SalesTax.Kind.EXEMPT, "RESALE", "TX", List.of(), true,
            "TX-DALLAS"),
        "NT", new SalesTax.Code("NT", SalesTax.Kind.NON_TAXABLE, "NON_TAXABLE_SERVICE", null, List.of(), false, null),
        "OR-NONE", new SalesTax.Code("OR-NONE", SalesTax.Kind.NON_TAXABLE, "NO_SALES_TAX", "OR", List.of(), false,
            null));

    private static final List<SalesTax.Rate> RATES = List.of(
        new SalesTax.Rate("TX", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 3, 31), new BigDecimal("6.25")),
        new SalesTax.Rate("TX", LocalDate.of(2026, 4, 1), null, new BigDecimal("6.50")),
        new SalesTax.Rate("TX-AUSTIN-LOCAL", LocalDate.of(2025, 1, 1), null, new BigDecimal("2.00")),
        new SalesTax.Rate("TX-DALLAS-LOCAL", LocalDate.of(2025, 1, 1), null, new BigDecimal("2.00")));

    private static final SalesTax.Certificate RC_3301 =
        new SalesTax.Certificate("TX", "RC-3301", "RESALE", null, LocalDate.of(2027, 12, 31));

    private static SalesTax.Result tax(LocalDate date, List<SalesTax.Line> lines, List<SalesTax.Certificate> certs,
        SalesTax.OnMissingCertificate onMissing) {
        return SalesTax.compute(new SalesTax.Request(date, lines, CODES, RATES, certs, onMissing));
    }

    private static SalesTax.Line line(String amount, String code) {
        return new SalesTax.Line(new BigDecimal(amount), code);
    }

    @Test
    void inv1004TaxesTheComponentsAndNotTheService() {
        SalesTax.Result result = tax(JAN_6, List.of(line("40000.00", "TX-AUSTIN"), line("10000.00", "NT")), List.of(),
            SalesTax.OnMissingCertificate.BLOCK);
        assertThat(result.problems()).isEmpty();
        assertThat(result.total()).isEqualByComparingTo("3300.00");
        // The explanation: base, rate and the day the rate took effect, per jurisdiction (state and local parts).
        assertThat(result.taxes()).extracting(t -> t.jurisdiction() + " " + t.base() + " " + t.percent() + " "
                + t.rateFrom() + " " + t.tax())
            .containsExactly("TX 40000.00 6.25 2025-01-01 2500.00", "TX-AUSTIN-LOCAL 40000.00 2.00 2025-01-01 800.00");
        assertThat(result.lines()).extracting(l -> l.taxCode() + " " + l.kind() + " " + l.reason() + " " + l.tax())
            .containsExactly("TX-AUSTIN TAXABLE null 3300.00", "NT NON_TAXABLE NON_TAXABLE_SERVICE 0.00");
    }

    @Test
    void oregonBearsNoSalesTax() {
        SalesTax.Result result = tax(JAN_6, List.of(line("18000.00", "OR-NONE")), List.of(),
            SalesTax.OnMissingCertificate.BLOCK);
        assertThat(result.total()).isEqualByComparingTo("0.00");
        assertThat(result.taxes()).isEmpty();
        assertThat(result.lines().getFirst().reason()).isEqualTo("NO_SALES_TAX");
    }

    @Test
    void aResaleWithAValidCertificateIsExemptAndNamesTheCertificate() {
        SalesTax.Result result = tax(LocalDate.of(2026, 1, 15), List.of(line("25000.00", "TX-RESALE")),
            List.of(RC_3301), SalesTax.OnMissingCertificate.BLOCK);
        assertThat(result.problems()).isEmpty();
        assertThat(result.total()).isEqualByComparingTo("0.00");
        assertThat(result.lines().getFirst()).extracting(SalesTax.LineResult::kind, SalesTax.LineResult::reason,
            SalesTax.LineResult::certificate).containsExactly(SalesTax.Kind.EXEMPT, "RESALE", "RC-3301");
    }

    @Test
    void anExpiredCertificateBlocksOrIsChargedAsConfigured() {
        LocalDate later = LocalDate.of(2028, 1, 15);
        SalesTax.Result blocked = tax(later, List.of(line("25000.00", "TX-RESALE")), List.of(RC_3301),
            SalesTax.OnMissingCertificate.BLOCK);
        assertThat(blocked.problems()).extracting(SalesTax.Problem::code).containsExactly(SalesTax.CERTIFICATE_MISSING);
        SalesTax.Result charged = tax(later, List.of(line("25000.00", "TX-RESALE")), List.of(RC_3301),
            SalesTax.OnMissingCertificate.CHARGE);
        assertThat(charged.problems()).isEmpty();
        // The state's rate changed on 2026-04-01: 6.50 % + 2.00 %.
        assertThat(charged.total()).isEqualByComparingTo("2125.00");
        assertThat(charged.lines().getFirst().taxCode()).isEqualTo("TX-DALLAS");
    }

    @Test
    void theRateInEffectOnTheDocumentDateApplies() {
        assertThat(tax(LocalDate.of(2026, 3, 31), List.of(line("1000.00", "TX-AUSTIN")), List.of(),
            SalesTax.OnMissingCertificate.BLOCK).total()).isEqualByComparingTo("82.50");
        assertThat(tax(LocalDate.of(2026, 4, 1), List.of(line("1000.00", "TX-AUSTIN")), List.of(),
            SalesTax.OnMissingCertificate.BLOCK).total()).isEqualByComparingTo("85.00");
    }

    @Test
    void aCustomersCertificateExemptsATaxableCodeOfItsState() {
        SalesTax.Result result = tax(JAN_6, List.of(line("100.00", "TX-AUSTIN")), List.of(RC_3301),
            SalesTax.OnMissingCertificate.BLOCK);
        assertThat(result.total()).isEqualByComparingTo("0.00");
        assertThat(result.lines().getFirst().certificate()).isEqualTo("RC-3301");
    }

    @Test
    void twoCodesOfOneStateShareTheStatesTaxRoundedOnce() {
        // 2.00 × 6.25 % = 0.125 → 0.13 for Texas; per code it would be 0.06 + 0.06.
        SalesTax.Result result = tax(JAN_6, List.of(line("1.00", "TX-AUSTIN"), line("1.00", "TX-DALLAS")), List.of(),
            SalesTax.OnMissingCertificate.BLOCK);
        assertThat(result.taxes()).extracting(t -> t.jurisdiction() + " " + t.base() + " " + t.tax())
            .containsExactly("TX 2.00 0.13", "TX-AUSTIN-LOCAL 1.00 0.02", "TX-DALLAS-LOCAL 1.00 0.02");
        assertThat(result.total()).isEqualByComparingTo("0.17");
        assertThat(result.lines()).extracting(SalesTax.LineResult::taxCode).containsExactly("TX-AUSTIN", "TX-DALLAS");
    }

    @Test
    void unknownCodesMissingRatesAndNegativeLinesAreProblems() {
        SalesTax.Result result = SalesTax.compute(new SalesTax.Request(LocalDate.of(2024, 6, 1),
            List.of(line("1.00", "XX"), line("-1.00", "NT"), line("10.00", "TX-AUSTIN")), CODES, RATES, List.of(),
            SalesTax.OnMissingCertificate.BLOCK));
        assertThat(result.problems()).extracting(SalesTax.Problem::code).containsExactly(SalesTax.UNKNOWN_CODE,
            SalesTax.NEGATIVE_LINE, SalesTax.NO_RATE, SalesTax.NO_RATE);
    }

    @Test
    void taxRoundsHalfAwayFromZeroOnTheSumAndAllocatesByLargestRemainder() {
        // 3 × 0.06: each line alone would round to no tax, the document bears 0.18 × 6.25 % = 0.01125 → 0.01 of state
        // tax (0.0036 → 0.00 local), and the cent goes to the last line.
        SalesTax.Result result = tax(JAN_6, List.of(line("0.06", "TX-AUSTIN"), line("0.06", "TX-AUSTIN"),
            line("0.06", "TX-AUSTIN")), List.of(), SalesTax.OnMissingCertificate.BLOCK);
        assertThat(result.taxes()).extracting(t -> t.tax().toPlainString()).containsExactly("0.01", "0.00");
        assertThat(result.lines()).extracting(l -> l.tax().toPlainString()).containsExactly("0.00", "0.00", "0.01");
        // Half a cent rounds away from zero: 0.08 × 6.25 % = 0.005 → 0.01.
        assertThat(tax(JAN_6, List.of(line("0.08", "TX-AUSTIN")), List.of(), SalesTax.OnMissingCertificate.BLOCK)
            .taxes().getFirst().tax()).isEqualByComparingTo("0.01");
    }

    @Property
    void lineSharesAddUpToEachJurisdictionsTax(
        @ForAll @Size(min = 1, max = 12) List<@BigRange(min = "0", max = "100000") @Scale(2) BigDecimal> amounts) {
        List<SalesTax.Line> lines = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            lines.add(new SalesTax.Line(amounts.get(i), i % 3 == 2 ? "NT" : "TX-AUSTIN"));
        }
        SalesTax.Result result = tax(JAN_6, lines, List.of(), SalesTax.OnMissingCertificate.BLOCK);
        assertThat(result.problems()).isEmpty();
        BigDecimal lineTotal = BigDecimal.ZERO;
        for (SalesTax.LineResult line : result.lines()) {
            lineTotal = lineTotal.add(line.tax());
        }
        assertThat(lineTotal).isEqualByComparingTo(result.total());
        for (SalesTax.JurisdictionTax tax : result.taxes()) {
            assertThat(tax.shares().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(tax.tax());
            assertThat(tax.tax()).isEqualByComparingTo(Money.usd(tax.base().multiply(tax.percent()).movePointLeft(2)));
        }
    }
}

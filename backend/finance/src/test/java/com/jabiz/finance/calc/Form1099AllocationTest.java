package com.jabiz.finance.calc;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Form1099AllocationTest {

    @Test
    void aBillOfOneBoxCountsItsCash() {
        assertThat(Form1099Allocation.allocate(new BigDecimal("8500.00"), Map.of("MISC|1", new BigDecimal("8500.00"))))
            .containsExactly(Map.entry("MISC|1", new BigDecimal("8500.00")));
    }

    @Test
    void cashIsSpreadOverTheBoxesAndTheLinesOfNone() {
        Map<String, BigDecimal> parts = new LinkedHashMap<>();
        parts.put("NEC|1", new BigDecimal("700.00"));
        parts.put(Form1099Allocation.NONE, new BigDecimal("300.00"));
        // Paid in part, net of a 2% discount: 490.00 of 500.00 applied.
        assertThat(Form1099Allocation.allocate(new BigDecimal("490.00"), parts))
            .containsExactly(Map.entry("NEC|1", new BigDecimal("343.00")));
        // A third each: the cent left by rounding goes to the largest share.
        Map<String, BigDecimal> thirds = new LinkedHashMap<>();
        thirds.put("MISC|1", new BigDecimal("1.00"));
        thirds.put("MISC|3", new BigDecimal("1.00"));
        thirds.put("NEC|1", new BigDecimal("1.01"));
        Map<String, BigDecimal> shares = Form1099Allocation.allocate(new BigDecimal("1.00"), thirds);
        assertThat(shares).containsEntry("MISC|1", new BigDecimal("0.33")).containsEntry("MISC|3",
            new BigDecimal("0.33")).containsEntry("NEC|1", new BigDecimal("0.34"));
        assertThat(Form1099Allocation.allocate(BigDecimal.ONE, Map.of())).isEmpty();
        assertThat(Form1099Allocation.allocate(BigDecimal.ZERO, parts)).isEmpty();
        assertThat(Form1099Allocation.key("NEC", "1")).isEqualTo("NEC|1");
        assertThat(Form1099Allocation.key(null, "1")).isEqualTo(Form1099Allocation.NONE);
        assertThat(Form1099Allocation.key("NEC", " ")).isEqualTo(Form1099Allocation.NONE);
    }

    /** The shares, with the lines of no box, add up to the cash exactly; none is negative. */
    @Property(tries = 300)
    void theSharesAddUpToTheCash(@ForAll("lines") List<BigDecimal> lines, @ForAll("cents") long cents) {
        Map<String, BigDecimal> parts = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            parts.merge(i == 0 ? Form1099Allocation.NONE : "MISC|" + i, lines.get(i), BigDecimal::add);
        }
        BigDecimal total = lines.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cash = BigDecimal.valueOf(Math.min(cents, total.movePointRight(2).longValueExact()), 2);
        Map<String, BigDecimal> shares = Form1099Allocation.allocate(cash, parts);
        BigDecimal none = cash.multiply(parts.get(Form1099Allocation.NONE)).divide(total, 2,
            java.math.RoundingMode.HALF_UP);
        BigDecimal reported = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(reported.add(none).subtract(cash).abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));
        assertThat(shares.values()).allSatisfy(s -> assertThat(s.signum()).isPositive());
        assertThat(reported).isLessThanOrEqualTo(cash);
    }

    @Provide
    Arbitrary<List<BigDecimal>> lines() {
        return Arbitraries.longs().between(1, 10_000_000).map(c -> BigDecimal.valueOf(c, 2)).list().ofMinSize(1)
            .ofMaxSize(8);
    }

    @Provide
    Arbitrary<Long> cents() {
        return Arbitraries.longs().between(1, 1_000_000_000);
    }

    @Test
    void theExportHasARowPerRecordWithPayerAndRecipient() {
        String file = Form1099File.write(new Form1099File.Payer("12-3456789", "Northwind Components, Inc.",
            "500 Congress Avenue", "Austin", "TX", "78701"), List.of(
            new Form1099File.Record(false, 2026, "NEC", "1", new BigDecimal("9000"), "EIN", "45-1234567",
                "Delta Consulting LLC", "100 Main Street", "Austin", "TX", "78701", "V200"),
            new Form1099File.Record(true, 2026, "MISC", "1", new BigDecimal("8500.00"), "EIN", "45-7654321",
                "Metro Properties LLC", "1 Elm", "Dallas", null, "75201", "V300")));
        assertThat(file.lines().toList()).containsExactly(Form1099File.HEADER,
            "ORIGINAL,2026,NEC,1,9000.00,12-3456789,\"Northwind Components, Inc.\",500 Congress Avenue,Austin,TX,"
                + "78701,EIN,45-1234567,Delta Consulting LLC,100 Main Street,Austin,TX,78701,V200,TX,9000.00",
            "CORRECTED,2026,MISC,1,8500.00,12-3456789,\"Northwind Components, Inc.\",500 Congress Avenue,Austin,TX,"
                + "78701,EIN,45-7654321,Metro Properties LLC,1 Elm,Dallas,,75201,V300,,");
    }
}

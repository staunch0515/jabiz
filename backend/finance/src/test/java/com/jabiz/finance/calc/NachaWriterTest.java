package com.jabiz.finance.calc;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NachaWriterTest {

    private static final NachaWriter.FileHeader HEADER = new NachaWriter.FileHeader("111000025",
        "Lakeside National Bank", "1234567890", "Northwind", LocalDateTime.of(2026, 1, 8, 9, 30), 'A');
    private static final List<String> ROUTINGS = List.of("021000021", "011000015", "091000019", "111000025",
        "121000358", "026009593");

    @Test
    void payRun01IsOneCorporateBatchOfTwoCredits() {
        String file = NachaWriter.write(HEADER, List.of(batch(NachaWriter.CCD,
            new NachaWriter.Entry("22", "021000021", "100200300", new BigDecimal("28300.00"), "PMT-0001",
                "Precision Parts Co."),
            new NachaWriter.Entry("22", "111000025", "600700800", new BigDecimal("4000.00"), "PMT-0002",
                "City Power & Light"))));
        List<String> lines = file.lines().toList();
        // Header, batch header, two entries, batch control, file control: six records padded to ten.
        assertThat(lines).hasSize(10).allSatisfy(l -> assertThat(l).hasSize(94));
        assertThat(lines.get(0)).startsWith("101 1110000251234567890260108" + "0930A094101")
            .contains("LAKESIDE NATIONAL BANK NORTHWIND");
        assertThat(lines.get(1)).startsWith("5220NORTHWIND").contains("1234567890CCDVENDOR PAY")
            .endsWith("260108   1111000020000001");
        assertThat(lines.get(2)).isEqualTo("622021000021100200300        0002830000PMT-0001       PRECISION PARTS CO.   "
            + "  0111000020000001");
        assertThat(lines.get(4)).startsWith("82200000020013200004" + "000000000000" + "000003230000")
            .endsWith("111000020000001");
        assertThat(lines.get(5)).startsWith("900000100000100000002" + "0013200004" + "000000000000"
            + "000003230000");
        assertThat(lines.subList(6, 10)).allSatisfy(l -> assertThat(l).isEqualTo("9".repeat(94)));
        NachaValidator.Result checked = NachaValidator.validate(file);
        assertThat(checked.problems()).isEmpty();
        assertThat(checked.totalCredit()).isEqualByComparingTo("32300.00");
        assertThat(checked.entries()).isEqualTo(2);
    }

    @Test
    void namesAreUpperCaseAsciiAndCut() {
        assertThat(NachaWriter.alpha("Café Ñandú & Söhne GmbH, ein langer Name", 22)).isEqualTo("CAFE NANDU & SOHNE GMB");
        assertThat(NachaWriter.alpha("北京", 4)).isEqualTo("    ");
        assertThat(NachaWriter.alpha(null, 3)).isEqualTo("   ");
        assertThat(NachaWriter.number(42, 5)).isEqualTo("00042");
        assertThatThrownBy(() -> NachaWriter.number(123456, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(NachaWriter.creditCode("SAVINGS")).isEqualTo("32");
        assertThat(NachaWriter.creditCode("CHECKING")).isEqualTo("22");
    }

    @Test
    void whatABankWouldNotTakeIsNeverWritten() {
        assertThatThrownBy(() -> new NachaWriter.Entry("27", "021000021", "1", BigDecimal.ONE, "x", "y"))
            .hasMessageContaining("transactionCode");
        assertThatThrownBy(() -> new NachaWriter.Entry("22", "02100002", "1", BigDecimal.ONE, "x", "y"))
            .hasMessageContaining("routing");
        assertThatThrownBy(() -> new NachaWriter.Entry("22", "021000021", "123456789012345678", BigDecimal.ONE,
            "x", "y")).hasMessageContaining("account");
        assertThatThrownBy(() -> new NachaWriter.Entry("22", "021000021", "1", new BigDecimal("0.001"), "x", "y"))
            .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new NachaWriter.Entry("22", "021000021", "1", BigDecimal.ZERO, "x", "y"))
            .hasMessageContaining("amount");
        assertThatThrownBy(() -> new NachaWriter.Batch("WEB", "N", "1", "D", LocalDate.of(2026, 1, 8), "111000025",
            List.of())).hasMessageContaining("secCode");
        assertThatThrownBy(() -> batch(NachaWriter.CCD)).hasMessageContaining("entries");
        assertThatThrownBy(() -> NachaWriter.write(HEADER, List.of())).hasMessageContaining("batches");
        assertThatThrownBy(() -> new NachaWriter.Entry("22", "021000021", "1", BigDecimal.ONE, "x", "北京"))
            .hasMessageContaining("name");
        assertThatThrownBy(() -> new NachaWriter.FileHeader("111000025", "B", "1", "N", LocalDateTime.now(), '1'))
            .hasMessageContaining("fileIdModifier");
    }

    @Test
    void theValidatorFindsWhatIsWrong() {
        String good = NachaWriter.write(HEADER, List.of(batch(NachaWriter.PPD, new NachaWriter.Entry("32",
            "021000021", "800900100", new BigDecimal("1500.00"), "PMT-0005", "Jordan Rivera"))));
        assertThat(NachaValidator.validate(good).valid()).isTrue();
        // An amount changed after the controls were written.
        String amount = good.replace("0000150000PMT-0005", "0000150001PMT-0005");
        assertThat(NachaValidator.validate(amount).problems()).anyMatch(p -> p.contains("credits"));
        // A routing number with a wrong check digit.
        String routing = good.replace("632021000021", "632021000022");
        assertThat(NachaValidator.validate(routing).problems()).anyMatch(p -> p.contains("no routing number"));
        // A record a character short, and a block not whole.
        assertThat(NachaValidator.validate(good.replaceFirst("PMT-0005 ", "PMT-0005")).problems())
            .anyMatch(p -> p.contains("not 94"));
        String lines = String.join("\n", good.lines().limit(9).toList()) + "\n";
        assertThat(NachaValidator.validate(lines).problems()).anyMatch(p -> p.contains("blocks of ten"));
        assertThat(NachaValidator.validate("").problems()).isNotEmpty();
        // A debit in a batch of credits.
        String debit = good.replace("632021000021", "627021000021");
        assertThat(NachaValidator.validate(debit).problems()).anyMatch(p -> p.contains("credits only")
            || p.contains("debits"));
        // An entry of nothing that is no prenote, and a service class that does not exist.
        assertThat(NachaValidator.validate(good.replace("0000150000PMT-0005", "0000000000PMT-0005")).problems())
            .anyMatch(p -> p.contains("has the amount 0"));
        List<String> classes = new java.util.ArrayList<>(good.lines().toList());
        classes.set(1, "5230" + classes.get(1).substring(4));
        classes.set(3, "8230" + classes.get(3).substring(4));
        assertThat(NachaValidator.validate(String.join("\n", classes)).problems())
            .anyMatch(p -> p.contains("service class 230"));
        // A batch without its control.
        List<String> records = new java.util.ArrayList<>(good.lines().toList());
        records.remove(3);
        records.add("9".repeat(94));
        assertThat(NachaValidator.validate(String.join("\r\n", records)).problems())
            .anyMatch(p -> p.contains("control"));
    }

    @Test
    void routingNumbersAreCheckedByTheirLastDigit() {
        for (String routing : ROUTINGS) {
            assertThat(NachaValidator.routing(routing)).as(routing).isTrue();
            assertThat(BankNumbers.validRouting(routing)).as(routing).isTrue();
        }
        assertThat(NachaValidator.routing("021000022")).isFalse();
        assertThat(NachaValidator.routing("02100002")).isFalse();
        assertThat(NachaValidator.routing(null)).isFalse();
    }

    /** Whatever the payments, what is written is well formed and adds up (FIN-AP-013). */
    @Property(tries = 200)
    void everyFileWrittenIsWellFormedAndAddsUp(@ForAll("batches") List<List<NachaWriter.Entry>> payments) {
        List<NachaWriter.Batch> batches = new java.util.ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int entries = 0;
        for (int i = 0; i < payments.size(); i++) {
            batches.add(batch(i % 2 == 0 ? NachaWriter.CCD : NachaWriter.PPD, payments.get(i)
                .toArray(NachaWriter.Entry[]::new)));
            for (NachaWriter.Entry entry : payments.get(i)) {
                total = total.add(entry.amount());
                entries++;
            }
        }
        String file = NachaWriter.write(HEADER, batches);
        NachaValidator.Result checked = NachaValidator.validate(file);
        assertThat(checked.problems()).isEmpty();
        assertThat(checked.batches()).isEqualTo(batches.size());
        assertThat(checked.entries()).isEqualTo(entries);
        assertThat(checked.totalCredit()).isEqualByComparingTo(total);
        assertThat(checked.totalDebit()).isZero();
        assertThat(file.lines().count() % 10).isZero();
    }

    @Provide
    Arbitrary<List<List<NachaWriter.Entry>>> batches() {
        Arbitrary<NachaWriter.Entry> entry = Combinators.combine(
            Arbitraries.of("22", "32"),
            Arbitraries.of(ROUTINGS),
            Arbitraries.strings().withCharRange('0', '9').ofMinLength(4).ofMaxLength(17),
            Arbitraries.longs().between(1, 9_999_999_999L).map(c -> BigDecimal.valueOf(c, 2)),
            Arbitraries.strings().ofMaxLength(20),
            Arbitraries.strings().ofMaxLength(40).filter(NachaWriter::writable))
            .as(NachaWriter.Entry::new);
        return entry.list().ofMinSize(1).ofMaxSize(30).list().ofMinSize(1).ofMaxSize(4);
    }

    private static NachaWriter.Batch batch(String sec, NachaWriter.Entry... entries) {
        return new NachaWriter.Batch(sec, "Northwind", "1234567890", "Vendor pay", LocalDate.of(2026, 1, 8),
            "111000025", List.of(entries));
    }
}

package com.jabiz.finance.payroll;

import com.jabiz.finance.gl.JournalValidator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider codes read through the mapping into summary lines (FIN-DI-004). */
class PayrollLinesTest {

    private static final Map<String, PayrollLines.Mapping> JANUARY = Map.of(
        "GROSS_WAGES", new PayrollLines.Mapping("GROSS_WAGES", "6100", "DEBIT", null),
        "EMPLOYER_TAX", new PayrollLines.Mapping("EMPLOYER_TAX", "6150", "DEBIT", null),
        "NET_PAY", new PayrollLines.Mapping("NET_PAY", "1010", "CREDIT", null),
        "EMPLOYEE_WITHHOLDING", new PayrollLines.Mapping("EMPLOYEE_WITHHOLDING", "2150", "CREDIT", null),
        "EMPLOYER_TAX_LIABILITY", new PayrollLines.Mapping("EMPLOYER_TAX_LIABILITY", "2150", "CREDIT", null));

    private static PayrollLines.ProviderLine line(String code, String amount) {
        return new PayrollLines.ProviderLine(code, null, new BigDecimal(amount));
    }

    @Test
    void codesOfTheSameAccountAddUpIntoOneLine() {
        PayrollLines.Result result = PayrollLines.lines(List.of(line("GROSS_WAGES", "60000.00"),
            line("EMPLOYER_TAX", "4590.00"), line("NET_PAY", "45000.00"), line("EMPLOYEE_WITHHOLDING", "15000.00"),
            line("EMPLOYER_TAX_LIABILITY", "4590.00")), JANUARY);
        assertThat(result.unmapped()).isEmpty();
        assertThat(result.lines()).extracting(l -> l.accountCode() + ":" + l.debit() + ":" + l.credit())
            .containsExactly("6100:60000.00:null", "6150:4590.00:null", "1010:null:45000.00", "2150:null:19590.00");
        assertThat(result.lines().get(3).memo()).isEqualTo("EMPLOYEE_WITHHOLDING, EMPLOYER_TAX_LIABILITY");
        assertThat(JournalValidator.totals(result.lines()).balanced()).isTrue();
    }

    @Test
    void unmappedCodesAreReportedNegativeAmountsCountOnTheOtherSideAndZerosDrop() {
        PayrollLines.Result result = PayrollLines.lines(List.of(line("GROSS_WAGES", "100.00"),
            line("GROSS_WAGES", "-100.00"), line("NET_PAY", "-20.00"), line("BONUS", "5.00"), line("BONUS", "1.00")),
            JANUARY);
        assertThat(result.unmapped()).containsExactly("BONUS");
        assertThat(result.lines()).extracting(l -> l.accountCode() + ":" + l.debit() + ":" + l.credit())
            .containsExactly("1010:20.00:null");
    }

    @Test
    void aMappingsDepartmentWinsOverTheFilesAndLinesSplitByDepartment() {
        Map<String, PayrollLines.Mapping> mappings = Map.of(
            "WAGES", new PayrollLines.Mapping("WAGES", "6100", "DEBIT", null),
            "ADMIN_WAGES", new PayrollLines.Mapping("ADMIN_WAGES", "6100", "DEBIT", "ADMIN"));
        PayrollLines.Result result = PayrollLines.lines(List.of(
            new PayrollLines.ProviderLine("WAGES", "SALES", new BigDecimal("10.00")),
            new PayrollLines.ProviderLine("ADMIN_WAGES", "SALES", new BigDecimal("5.00"))), mappings);
        assertThat(result.lines()).extracting(l -> l.department() + ":" + l.debit())
            .containsExactly("ADMIN:5.00", "SALES:10.00");
    }

    /** Whatever the run, the lines carry exactly the mapped amounts: debits minus credits equal their signed sum. */
    @Property
    void theLinesKeepTheSignedTotal(@ForAll @Size(min = 1, max = 30) List<@IntRange(min = -500000, max = 500000)
        Integer> cents, @ForAll @IntRange(min = 0, max = 4) int shift) {
        List<String> codes = List.of("GROSS_WAGES", "EMPLOYER_TAX", "NET_PAY", "EMPLOYEE_WITHHOLDING",
            "EMPLOYER_TAX_LIABILITY");
        List<PayrollLines.ProviderLine> provider = new ArrayList<>();
        BigDecimal signed = BigDecimal.ZERO;
        for (int i = 0; i < cents.size(); i++) {
            String code = codes.get((i + shift) % codes.size());
            BigDecimal amount = BigDecimal.valueOf(cents.get(i), 2);
            provider.add(new PayrollLines.ProviderLine(code, null, amount));
            signed = signed.add("CREDIT".equals(JANUARY.get(code).side()) ? amount.negate() : amount);
        }
        PayrollLines.Result result = PayrollLines.lines(provider, JANUARY);
        JournalValidator.Totals totals = JournalValidator.totals(result.lines());
        assertThat(totals.difference()).isEqualByComparingTo(signed);
        assertThat(result.lines()).allSatisfy(l -> assertThat(l.debit() == null ^ l.credit() == null).isTrue());
        assertThat(result.lines()).extracting(l -> l.accountCode()).doesNotHaveDuplicates();
    }
}

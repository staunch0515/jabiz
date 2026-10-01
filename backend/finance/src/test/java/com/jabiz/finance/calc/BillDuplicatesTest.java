package com.jabiz.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The duplicate bill check (FIN-AP-005). */
class BillDuplicatesTest {

    private static final LocalDate JAN_9 = LocalDate.of(2026, 1, 9);

    private static BillDuplicates.Bill bill(String id, String vendor, String number, LocalDate date, String total,
        boolean voided) {
        return new BillDuplicates.Bill(id, "BILL-" + id, "BILL", vendor, number, date, new BigDecimal(total), voided);
    }

    @Test
    void theSameVendorInvoiceNumberIsTheSameBillHoweverWritten() {
        List<BillDuplicates.Bill> posted = List.of(bill("1", "V100", "P-7902", JAN_9, "22000.00", false));
        for (String number : List.of("P-7902", "p 7902", "P7902", " P-7902 ")) {
            assertThat(BillDuplicates.check(bill(null, "V100", number, JAN_9.plusDays(3), "5.00", false), posted)
                .same()).as(number).isNotNull();
        }
        // Another vendor's P-7902 is another bill; so is a voided one, and the bill itself.
        assertThat(BillDuplicates.check(bill(null, "V200", "P-7902", JAN_9, "1.00", false), posted).same()).isNull();
        assertThat(BillDuplicates.check(bill(null, "V100", "P-7902", JAN_9, "1.00", false),
            List.of(bill("1", "V100", "P-7902", JAN_9, "1.00", true))).same()).isNull();
        assertThat(BillDuplicates.check(bill("1", "V100", "P-7902", JAN_9, "22000.00", false), posted).same())
            .isNull();
    }

    @Test
    void theSameAmountAndDateUnderAnotherNumberIsSuspect() {
        List<BillDuplicates.Bill> posted = List.of(bill("1", "V100", "P-7902", JAN_9, "22000.00", false));
        BillDuplicates.Result result = BillDuplicates.check(bill(null, "V100", "P-7903", JAN_9, "22000", false),
            posted);
        assertThat(result.same()).isNull();
        assertThat(result.similar()).extracting(BillDuplicates.Bill::vendorInvoiceNo).containsExactly("P-7902");
        assertThat(BillDuplicates.check(bill(null, "V100", "P-7903", JAN_9.plusDays(1), "22000.00", false), posted)
            .similar()).isEmpty();
        assertThat(BillDuplicates.check(bill(null, "V100", "P-7903", JAN_9, "21999.99", false), posted).similar())
            .isEmpty();
        assertThat(BillDuplicates.key(null)).isEmpty();
    }
}

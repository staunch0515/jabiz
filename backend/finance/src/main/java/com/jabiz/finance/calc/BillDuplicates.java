package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The duplicate bill check (FIN-AP-005): a bill whose vendor and vendor invoice number match another's is refused; one
 * whose vendor, amount and invoice date match another's under a different number is suspect and needs a reason. Vendor
 * invoice numbers are compared as their letters and digits only, so "P-7902", "p 7902" and "P7902" are one number.
 * Voided bills and the bill itself are not compared with. Pure.
 */
public final class BillDuplicates {

    /** A bill to compare with: its identity, number, kind, vendor, vendor invoice number, date, total and status. */
    public record Bill(Object id, String billNo, String kind, String vendorCode, String vendorInvoiceNo,
        LocalDate invoiceDate, BigDecimal total, boolean voided) {}

    /**
     * @param same    the bill with the same vendor invoice number, if any
     * @param similar the bills of the same vendor, amount and date under another number
     */
    public record Result(Bill same, List<Bill> similar) {}

    private BillDuplicates() {}

    public static Result check(Bill candidate, List<Bill> others) {
        String number = key(candidate.vendorInvoiceNo());
        Bill same = null;
        List<Bill> similar = new java.util.ArrayList<>();
        for (Bill other : others) {
            if (other.voided() || Objects.equals(String.valueOf(other.id()), String.valueOf(candidate.id()))
                || !Objects.equals(other.vendorCode(), candidate.vendorCode())
                || !Objects.equals(other.kind(), candidate.kind())) {
                continue;
            }
            if (number.equals(key(other.vendorInvoiceNo()))) {
                same = same == null ? other : same;
            } else if (Objects.equals(other.invoiceDate(), candidate.invoiceDate()) && other.total() != null
                && candidate.total() != null && other.total().compareTo(candidate.total()) == 0) {
                similar.add(other);
            }
        }
        return new Result(same, List.copyOf(similar));
    }

    /** A vendor invoice number as compared: its letters and digits, in capitals. */
    public static String key(String vendorInvoiceNo) {
        return vendorInvoiceNo == null ? "" : vendorInvoiceNo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}

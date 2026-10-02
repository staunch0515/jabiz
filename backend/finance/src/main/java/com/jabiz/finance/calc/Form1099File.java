package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The export for electronic filing of Forms 1099 (FIN-AP-022, FIN-AP-023; F4 plan D6): a filing-service CSV with one
 * row per vendor, form and box, the payer's and the recipient's names, TINs and addresses in full, the amount, and
 * the state columns a filing service passes to states taking part in the combined federal/state program. A
 * correction row is marked {@code CORRECTED} and carries the amount as it should have been. The layout is documented
 * in docs/finance/00-design.md section 9 so a filing service's import can be mapped to it.
 */
public final class Form1099File {

    public static final String HEADER = "record_type,tax_year,form,box,amount,payer_tin,payer_name,payer_street,"
        + "payer_city,payer_state,payer_zip,recipient_tin_type,recipient_tin,recipient_name,recipient_street,"
        + "recipient_city,recipient_state,recipient_zip,account_number,state,state_income";

    /** The payer: the company as its profile has it. */
    public record Payer(String tin, String name, String street, String city, String state, String zip) {}

    /**
     * One record.
     *
     * @param corrected   whether it corrects a record filed before
     * @param accountNumber the vendor's code, telling apart the records of one payer and recipient
     * @param state       the recipient's state, for the combined federal/state program; its income is the amount
     */
    public record Record(boolean corrected, int taxYear, String form, String box, BigDecimal amount, String tinType,
        String tin, String name, String street, String city, String state, String zip, String accountNumber) {}

    public static String write(Payer payer, List<Record> records) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Record r : records) {
            String amount = r.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
            String[] values = {r.corrected() ? "CORRECTED" : "ORIGINAL", String.valueOf(r.taxYear()), r.form(),
                r.box(), amount, payer.tin(), payer.name(), payer.street(), payer.city(), payer.state(), payer.zip(),
                r.tinType(), r.tin(), r.name(), r.street(), r.city(), r.state(), r.zip(), r.accountNumber(),
                r.state(), r.state() == null ? null : amount};
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(CheckFiles.field(values[i]));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private Form1099File() {}
}

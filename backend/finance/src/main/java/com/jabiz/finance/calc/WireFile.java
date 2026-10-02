package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * A wire run's instructions for the bank (FIN-AP-013): one CSV row per wire with the account debited, the beneficiary's
 * bank and account in full, the amount and the reference. It is a generated file rather than a document because
 * documents always mask account numbers (platform docs/design/22-documents.md section 3.1) and a bank needs them.
 */
public final class WireFile {

    /** One wire: the payment, the day it is sent, the beneficiary and where it goes. */
    public record Wire(String paymentNo, LocalDate valueDate, BigDecimal amount, String beneficiary,
        String beneficiaryBank, String beneficiaryRouting, String beneficiaryAccount, String reference) {}

    public static String write(String debitRouting, String debitAccount, String currency, List<Wire> wires) {
        StringBuilder out = new StringBuilder("payment_no,value_date,currency,amount,debit_routing,debit_account,"
            + "beneficiary,beneficiary_bank,beneficiary_routing,beneficiary_account,reference\n");
        for (Wire wire : wires) {
            String[] values = {wire.paymentNo(), wire.valueDate().toString(), currency,
                wire.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), debitRouting, debitAccount,
                wire.beneficiary(), wire.beneficiaryBank(), wire.beneficiaryRouting(), wire.beneficiaryAccount(),
                wire.reference()};
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

    private WireFile() {}
}

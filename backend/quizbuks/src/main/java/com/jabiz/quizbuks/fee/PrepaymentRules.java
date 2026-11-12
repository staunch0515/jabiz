package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one funds check of a publication's submission (F-M5), pure Java: what its fees set aside, added up, against what
 * the sponsor may spend. A prepaid sponsor needs as much in the account that pays the platform's fees (the fee credit
 * not expired and the cash available); a postpaid one needs what they owe (1130 for them: the unpaid invoices and the
 * fees not invoiced yet), what their other submissions set aside and this one within their credit limit. One code,
 * {@value FeeCodes#PUBLICATION_FUNDS}, with every amount.
 */
public final class PrepaymentRules {

    private PrepaymentRules() {}

    /**
     * One fee a submission sets aside for.
     *
     * @param purpose      what for ({@link QbPrepayments#PURPOSES})
     * @param amount       what is set aside: the fee and its tax
     * @param chargeAmount the fee before tax, charged from it later
     * @param detail       what it was worked out from, as JSON (stored with the prepayment)
     */
    public record Item(String purpose, BigDecimal amount, BigDecimal chargeAmount, String detail) {
        public Item {
            if (amount.signum() < 0 || chargeAmount.signum() < 0) {
                throw new IllegalArgumentException("A negative prepayment");
            }
        }
    }

    /**
     * Where the sponsor stands for the check.
     *
     * @param billingMode   {@link FeeRules#PREPAID} or {@link FeeRules#POSTPAID}
     * @param spendable     prepaid: the fee credit not expired and the cash available
     * @param owed          postpaid: 1130 for them (unpaid invoices and fees not invoiced)
     * @param setAside      postpaid: what their other open postpaid prepayments still count
     * @param creditLimit   postpaid: what they may owe (none: 0)
     */
    public record Standing(String billingMode, BigDecimal spendable, BigDecimal owed, BigDecimal setAside,
        BigDecimal creditLimit) {}

    /**
     * The check's outcome.
     *
     * @param total     what this submission sets aside
     * @param available prepaid: what may be spent; postpaid: what is left of the credit limit before this one
     * @param shortfall what is missing (0: enough)
     */
    public record Funds(List<Item> items, BigDecimal total, BigDecimal available, BigDecimal shortfall,
        String billingMode) {
        public Funds {
            items = List.copyOf(items);
        }

        public boolean enough() {
            return shortfall.signum() == 0;
        }
    }

    /** Adds up the items (nothing set aside: always enough) against the standing. */
    public static Funds check(List<Item> items, Standing standing) {
        List<Item> charged = items.stream().filter(item -> item.amount().signum() > 0).toList();
        BigDecimal total = charged.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean postpaid = FeeRules.POSTPAID.equals(standing.billingMode());
        BigDecimal available = postpaid
            ? zero(standing.creditLimit()).subtract(zero(standing.owed())).subtract(zero(standing.setAside()))
            : zero(standing.spendable());
        BigDecimal shortfall = total.signum() == 0 ? BigDecimal.ZERO : total.subtract(available).max(BigDecimal.ZERO);
        return new Funds(charged, total, available.max(BigDecimal.ZERO), shortfall, standing.billingMode());
    }

    /** Why the submission is refused ({@code 422} {@value FeeCodes#PUBLICATION_FUNDS}), or null when it is enough. */
    public static Violation refusal(Funds funds) {
        if (funds.enough()) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("total", funds.total().toPlainString());
        params.put("available", funds.available().toPlainString());
        params.put("shortfall", funds.shortfall().toPlainString());
        params.put("billingMode", funds.billingMode());
        for (Item item : funds.items()) {
            params.put(item.purpose(), item.amount().toPlainString());
        }
        return new Violation(null, FeeCodes.PUBLICATION_FUNDS, "The account cannot cover what the publication sets "
            + "aside", params);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

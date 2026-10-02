package com.jabiz.finance.calc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spreads the cash a payment applies to a bill over the bill's Form 1099 boxes (FIN-AP-020, FIN-AP-021): each box,
 * and the lines reported in none, take the share of the cash their lines have of the bill, in cents, by
 * {@link Money#allocate} so the shares add up to the cash exactly. A partial payment counts in the same proportions
 * as the bill. A box whose lines net to less than nothing (a credit line) weighs nothing: no box ever counts more
 * than the vendor received.
 */
public final class Form1099Allocation {

    /** The key of the lines reported in no box. */
    public static final String NONE = "";

    /**
     * @param cash  what the payment applied to the bill, net of any discount: what the vendor received
     * @param parts the bill's line amounts by box key ({@code NEC|1}, or {@link #NONE})
     * @return the cash of each box key but {@link #NONE}, leaving out boxes of nothing
     */
    public static Map<String, BigDecimal> allocate(BigDecimal cash, Map<String, BigDecimal> parts) {
        List<String> keys = new ArrayList<>(parts.keySet());
        List<BigDecimal> weights = keys.stream().map(k -> parts.get(k).max(BigDecimal.ZERO)).toList();
        if (cash.signum() <= 0 || weights.stream().allMatch(w -> w.signum() == 0)) {
            return Map.of();
        }
        List<BigDecimal> shares = Money.allocate(cash, weights, Money.USD_SCALE);
        Map<String, BigDecimal> boxes = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            if (!NONE.equals(keys.get(i)) && shares.get(i).signum() != 0) {
                boxes.put(keys.get(i), shares.get(i));
            }
        }
        return boxes;
    }

    /** The key of a form and box, or {@link #NONE} for a line reported in none. */
    public static String key(String form, String box) {
        return form == null || form.isBlank() || box == null || box.isBlank() ? NONE : form + "|" + box;
    }

    private Form1099Allocation() {}
}

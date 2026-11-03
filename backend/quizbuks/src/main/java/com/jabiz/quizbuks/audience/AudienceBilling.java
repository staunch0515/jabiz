package com.jabiz.quizbuks.audience;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a publication's audience rules cost (Q47c, D-Q47c-2 / D-Q47c-3), pure Java: the price list that applies (the
 * sponsor's operator's, else the fallback), the days of the publication's run rounded up, and the fee
 * = max(least fee, Σ the daily prices of the charged rules it uses × days). A rule counts once however many of its
 * options are chosen; the regions are free; a publication using no charged rule pays nothing (not even the least fee).
 * The quote the wizard shows and the charge on submit (Q47c-2) both come from here.
 */
public final class AudienceBilling {

    /** Where a sponsor whose operator has no price list is priced (`qb.audience.price.fallback`). */
    public static final String FALLBACK_DIRECT = "DIRECT";
    public static final String FALLBACK_FREE = "FREE";
    public static final List<String> FALLBACKS = List.of(FALLBACK_DIRECT, FALLBACK_FREE);

    private static final Duration DAY = Duration.ofDays(1);

    private AudienceBilling() {}

    /** A rule as managed (a kind without a record: open and charged for). */
    public record Rule(boolean enabled, boolean billable) {
        public static final Rule DEFAULT = new Rule(true, true);
    }

    /** An owner's price list: the least fee, and the daily price of each rule (a rule without one costs 0). */
    public record PriceList(String owner, BigDecimal minimumFee, Map<String, BigDecimal> dailyPrices) {
        public PriceList {
            dailyPrices = Map.copyOf(dailyPrices);
        }

        public BigDecimal priceOf(String ruleKey) {
            BigDecimal price = dailyPrices.get(ruleKey);
            return price == null ? BigDecimal.ZERO : price;
        }
    }

    /**
     * @param owner  whose price list was used ({@code null} when none applies: free)
     * @param ruleKey a charged rule the publication uses
     */
    public record Item(String ruleKey, BigDecimal dailyPrice) {}

    /**
     * @param enabled   whether rules are charged for at all (`qb.audience.billing.enabled`)
     * @param owner     whose price list applies; null when none does (free)
     * @param days      the days of the run, rounded up, at least 1
     * @param items     the charged rules used, with their daily prices
     * @param subtotal  Σ daily prices × days
     * @param minimumFee the least fee of the list (0 without a list)
     * @param fee       what the publication pays: 0 when not enabled or no charged rule is used
     */
    public record Quote(boolean enabled, String owner, int days, List<Item> items, BigDecimal subtotal,
        BigDecimal minimumFee, BigDecimal fee) {
        public Quote {
            items = List.copyOf(items);
        }
    }

    /** The days a run from {@code start} to {@code end} is charged for: whole days rounded up, at least 1. */
    public static int days(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return 1;
        }
        long seconds = Duration.between(start, end).getSeconds();
        long days = (seconds + DAY.getSeconds() - 1) / DAY.getSeconds();
        return (int) Math.max(1, Math.min(days, Integer.MAX_VALUE));
    }

    /**
     * The price list a sponsor is priced by: their operator's when it has one; else headquarters' when the fallback
     * is {@link #FALLBACK_DIRECT} (and it has one); else none (free). A direct sponsor (no operator) is priced by
     * headquarters' list.
     */
    public static PriceList applicable(String operatorId, PriceList operatorList, PriceList directList,
        String fallback) {
        if (operatorId == null) {
            return directList;
        }
        if (operatorList != null) {
            return operatorList;
        }
        return FALLBACK_FREE.equals(fallback) ? null : directList;
    }

    /**
     * The quote of a publication using {@code usedKeys} (its criteria's keys, repeats allowed) from {@code start} to
     * {@code end}, under the managed {@code rules} (a key without one: {@link Rule#DEFAULT}).
     */
    public static Quote quote(boolean enabled, Collection<String> usedKeys, Map<String, Rule> rules, PriceList list,
        Instant start, Instant end) {
        int days = days(start, end);
        BigDecimal minimum = list == null ? BigDecimal.ZERO : list.minimumFee();
        Set<String> used = new LinkedHashSet<>(usedKeys);
        List<Item> items = new ArrayList<>();
        BigDecimal perDay = BigDecimal.ZERO;
        for (String key : used) {
            Rule rule = rules.getOrDefault(key, Rule.DEFAULT);
            if (!rule.billable()) {
                continue;
            }
            BigDecimal price = list == null ? BigDecimal.ZERO : list.priceOf(key);
            items.add(new Item(key, price));
            perDay = perDay.add(price);
        }
        BigDecimal subtotal = perDay.multiply(BigDecimal.valueOf(days));
        BigDecimal fee = !enabled || list == null || items.isEmpty() ? BigDecimal.ZERO : subtotal.max(minimum);
        return new Quote(enabled, list == null ? null : list.owner(), days, items, subtotal, minimum, fee);
    }

    /** The keys of a publication's criteria that refer to a closed rule (the save refuses them). */
    public static List<String> closed(Collection<String> usedKeys, Map<String, Rule> rules) {
        return new LinkedHashSet<>(usedKeys).stream()
            .filter(key -> !rules.getOrDefault(key, Rule.DEFAULT).enabled()).toList();
    }
}

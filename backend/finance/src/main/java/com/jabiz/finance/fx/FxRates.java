package com.jabiz.finance.fx;

import com.jabiz.entity.Violation;
import com.jabiz.finance.calc.Money;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The rate a document is converted at (F7 plan decision D1; FIN-FX-003): the spot rate of its day into US dollars,
 * or, when the day has none, the latest of the days just before it, as many as the settings allow (5 by default);
 * a rate given on the document is taken as it is. Older than that, the document is refused rather than converted at
 * a stale rate.
 */
public final class FxRates {

    public static final String NO_RATE = "FIN_FX_NO_RATE";
    public static final String USD = "USD";

    private FxRates() {}

    /** The spot rates of {@code currency} into US dollars from {@code tolerance} days before {@code date} to it. */
    public static EntityQuery spot(String currency, LocalDate date, EntityInstance settings) {
        return rates(currency, date, FxEntities.SPOT, settings);
    }

    /** As {@link #spot}, of a rate type. */
    public static EntityQuery rates(String currency, LocalDate date, String rateType, EntityInstance settings) {
        if (currency == null || USD.equals(currency) || date == null) {
            return EntityQuery.builder().where(new QueryPredicate.In("rateDate", List.of())).limit(1).build();
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("fromCurrency", currency),
                new QueryPredicate.Eq("toCurrency", USD),
                new QueryPredicate.Eq("rateType", rateType),
                new QueryPredicate.Gte("rateDate", date.minusDays(tolerance(settings))),
                new QueryPredicate.Lte("rateDate", date))))
            .orderBy("rateDate", false).limit(1).build();
    }

    /** The days back a rate may be taken from. */
    public static int tolerance(EntityInstance settings) {
        BigDecimal days = settings == null ? null : settings.get("toleranceDays");
        return days == null ? FxEntities.DEFAULT_TOLERANCE_DAYS : days.intValueExact();
    }

    /**
     * The rate to use: one for US dollars, the one given, else the latest found; null when there is none.
     *
     * @param found the rows of {@link #spot}
     */
    public static BigDecimal rate(String currency, BigDecimal given, List<EntityInstance> found) {
        if (currency == null || USD.equals(currency)) {
            return BigDecimal.ONE;
        }
        if (given != null) {
            return given;
        }
        return found.isEmpty() ? null : found.getFirst().get("rate");
    }

    public static Violation missing(String field, String currency, LocalDate date, EntityInstance settings) {
        int days = tolerance(settings);
        return new Violation(field, NO_RATE, "There is no spot rate of " + currency + " into USD on " + date
            + " or the " + days + " days before; enter the rate or the day's rate",
            Map.of("currency", currency, "date", date.toString(), "days", days));
    }

    /** Foreign {@code amount} in US dollars at {@code rate}, to the cent. */
    public static BigDecimal usd(BigDecimal amount, BigDecimal rate) {
        return Money.usd(amount.multiply(rate));
    }
}

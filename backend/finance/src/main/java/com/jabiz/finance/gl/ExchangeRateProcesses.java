package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jabiz.finance.gl.AccountProcesses.list;

/**
 * {@code FIN_EXCHANGE_RATE_SET} (FIN-FX-002): the rate of a currency pair, a day and a rate type, entered by hand or
 * imported (FIN-DI-001) through the same process. A rate set again for the same day and type is corrected: the
 * earlier value stays in the rate's history.
 */
public final class ExchangeRateProcesses {

    public static final String SET = "FIN_EXCHANGE_RATE_SET";

    public static final String UNKNOWN_CURRENCY = "FIN_EXCHANGE_RATE_UNKNOWN_CURRENCY";
    public static final String INVALID_TYPE = "FIN_EXCHANGE_RATE_INVALID_TYPE";

    /**
     * @param rate     units of {@code toCurrency} for one unit of {@code fromCurrency}
     * @param rateType {@code SPOT} (when absent), {@code CLOSING} or {@code AVERAGE}
     */
    public record RateInput(@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String fromCurrency,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String toCurrency, @NotNull LocalDate rateDate, String rateType,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 10) BigDecimal rate) {}

    /** @param changed false when the same rate stood already */
    public record RateOutput(String rateId, String fromCurrency, String toCurrency, LocalDate rateDate,
        String rateType, BigDecimal rate, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RATES = "rates";
    static final String CURRENCIES = "currencies";

    public static final ProcessDefinition<RateInput, RateOutput, ProcessContext> SET_PROCESS =
        ProcessDefinition.define(SET, 1, RateInput.class, RateOutput.class, ProcessContext.class, pb -> pb
            .description("Sets the exchange rate of a currency pair for a day and a rate type.")
            .permissions(FinancePermissions.FX_MAINTAIN)
            .contextFactory(AccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RateOutput.class))
            .step("Load the rate", QueryEntities.of(GlEntities.EXCHANGE_RATE_DATASET, ctx -> {
                RateInput input = input(ctx);
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("fromCurrency", code(input.fromCurrency())),
                    new QueryPredicate.Eq("toCurrency", code(input.toCurrency())),
                    new QueryPredicate.Eq("rateDate", input.rateDate()),
                    new QueryPredicate.Eq("rateType", type(input))))).limit(1).build();
            }, RATES))
            .step("Load the currencies", QueryEntities.of(GlEntities.CURRENCY_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("currencyCode", List.of(code(input(ctx).fromCurrency()),
                    code(input(ctx).toCurrency())))).limit(2).build(), CURRENCIES))
            .compute("Set the rate", (metadata, ctx) -> set(ctx)));

    static void set(ProcessContext ctx) {
        RateInput input = input(ctx);
        String type = type(input);
        if (!GlEntities.RATE_TYPE_VALUES.contains(type)) {
            ctx.reject(new Violation("rateType", INVALID_TYPE, "rateType must be one of "
                + GlEntities.RATE_TYPE_VALUES, Map.of("value", type)));
        }
        for (String field : List.of("fromCurrency", "toCurrency")) {
            String code = code("fromCurrency".equals(field) ? input.fromCurrency() : input.toCurrency());
            boolean known = list(ctx, CURRENCIES).stream().anyMatch(c -> code.equals(c.get("currencyCode"))
                && Boolean.TRUE.equals(c.get("active")));
            if (!known) {
                ctx.reject(new Violation(field, UNKNOWN_CURRENCY, code + " is not an active currency",
                    Map.of("currencyCode", code)));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        String from = code(input.fromCurrency());
        String to = code(input.toCurrency());
        List<EntityInstance> found = list(ctx, RATES);
        if (found.isEmpty()) {
            Map<String, Object> rate = new LinkedHashMap<>();
            rate.put("fromCurrency", from);
            rate.put("toCurrency", to);
            rate.put("rateDate", input.rateDate());
            rate.put("rateType", type);
            rate.put("rate", input.rate());
            Object id = ctx.changes().insert(GlEntities.EXCHANGE_RATE, rate);
            ctx.put(OUTPUT, new RateOutput(String.valueOf(id), from, to, input.rateDate(), type, input.rate(), true));
            return;
        }
        EntityInstance rate = found.getFirst();
        boolean changed = input.rate().compareTo(rate.get("rate")) != 0;
        if (changed) {
            ctx.changes().update(GlEntities.EXCHANGE_RATE, rate.id(), rate.version(), Map.of("rate", input.rate()));
        }
        ctx.put(OUTPUT, new RateOutput(String.valueOf(rate.id()), from, to, input.rateDate(), type,
            changed ? input.rate() : rate.get("rate"), changed));
    }

    private static RateInput input(ProcessContext ctx) {
        return ctx.get(INPUT, RateInput.class);
    }

    private static String code(String currency) {
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private static String type(RateInput input) {
        return input.rateType() == null || input.rateType().isBlank() ? "SPOT"
            : input.rateType().trim().toUpperCase(Locale.ROOT);
    }

    private ExchangeRateProcesses() {}
}

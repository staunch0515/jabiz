package com.jabiz.app;

import com.jabiz.entity.Violation;
import com.jabiz.process.ComputeStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Sample process: adjusts a price by a percentage (docs/design/06-process.md section 2.2). The platform loads the
 * price through its dataset, the computation checks the rules and registers the new amount, and the platform
 * commits it as a new version of the temporal entity. The computation is plain synchronous Java.
 */
public final class PriceAdjustment {

    public static final String NAME = "PRICE_ADJUST";
    public static final String PERMISSION = "price.adjust";
    public static final String RANGE_RULE = "PRICE_ADJUSTMENT_RANGE";
    public static final String POSITIVE_RULE = "PRICE_NOT_POSITIVE";

    static final BigDecimal MIN_PERCENT = new BigDecimal("-50");
    static final BigDecimal MAX_PERCENT = new BigDecimal("100");

    /**
     * @param version the version of the price the caller read
     * @param percent change in percent; -50 to 100
     */
    public record Input(@NotBlank String priceId, @NotNull Long version, @NotNull BigDecimal percent) {}

    public record Output(String priceId, BigDecimal oldAmount, BigDecimal newAmount) {}

    /** Typed access to what the steps hand each other. */
    public static final class Context extends ProcessContext {

        static final String PRICE_ID = "priceId";
        static final String PRICE = "price";
        static final String NEW_AMOUNT = "newAmount";

        private final Input input;

        Context(ProcessStart start, Input input) {
            super(start);
            this.input = input;
            put(PRICE_ID, input.priceId());
        }

        Input input() {
            return input;
        }

        EntityInstance price() {
            return get(PRICE, EntityInstance.class);
        }
    }

    public static final ProcessDefinition<Input, Output, Context> DEFINITION =
        ProcessDefinition.define(NAME, 1, Input.class, Output.class, Context.class, pb -> pb
            .description("Adjusts a price by a percentage; the new amount must stay positive.")
            .permissions(PERMISSION)
            // Changing prices is sensitive: the caller confirms with a recent second factor (docs/design/10-security.md
            // section 10).
            .requiresMfa()
            .contextFactory(Context::new)
            .outputMapper(ctx -> new Output(ctx.input().priceId(), ctx.price().get("amount"),
                ctx.get(Context.NEW_AMOUNT, BigDecimal.class)))
            .step("Load price", LoadEntity.by("urn:jabiz:dataset:default:Price", Context.PRICE_ID, Context.PRICE))
            .step("Apply adjustment", Apply.class, NoMetadata.INSTANCE));

    /** Checks the percentage and the resulting amount, then registers the update. */
    @Component
    public static class Apply implements ComputeStep<NoMetadata, Context> {

        @Override
        public void compute(NoMetadata metadata, Context ctx) {
            BigDecimal percent = ctx.input().percent();
            BigDecimal amount = ctx.price().get("amount");
            if (percent.compareTo(MIN_PERCENT) < 0 || percent.compareTo(MAX_PERCENT) > 0) {
                ctx.reject(new Violation("percent", RANGE_RULE, "percent must be between -50 and 100",
                    Map.of("min", MIN_PERCENT, "max", MAX_PERCENT)));
            }
            // JPY has no minor unit.
            BigDecimal adjusted = amount.multiply(BigDecimal.valueOf(100).add(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            if (adjusted.signum() <= 0) {
                ctx.reject(new Violation("percent", POSITIVE_RULE, "the adjusted price must be positive"));
            }
            if (ctx.hasViolations()) {
                return;
            }
            ctx.put(Context.NEW_AMOUNT, adjusted);
            ctx.changes().update("Price", ctx.input().priceId(), ctx.input().version(), Map.of("amount", adjusted));
        }
    }

    private PriceAdjustment() {}
}

package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saving an operator's exposure price (F-M8, docs/quizbuks/plans/impl/F-M8.md §3.2): {@value #OPERATOR_SET} by the
 * operator's lead for their own operator, {@value #SET} by the headquarters for any. Checked against the headquarters'
 * range as it stands ({@link ExposureFeeRules#priceProblems}); the reason is required. A price affects only the
 * publications submitted after it: the price per exposure is frozen at the submission. There is no way to "charge 0": an
 * operator that charges nothing has no price.
 */
public final class ExposurePrices {

    public static final String SET = "QB_EXPOSURE_PRICE_SET";
    public static final String OPERATOR_SET = "QB_OPERATOR_EXPOSURE_PRICE_SET";

    static final String INPUT = "input";
    static final String OPERATOR_ID = "operatorId";
    static final String OPERATOR = "operator";
    static final String PRICE = "price";
    static final String RANGE = "range";
    static final String OUTPUT = "output";

    private ExposurePrices() {}

    /**
     * @param operatorId the operator whose price it is: headquarters only (required); the lead's process takes the
     *                   caller's operator
     * @param unit       yen per exposure, more than 0
     * @param reason     why it changes (the audit keeps it)
     */
    public record PriceInput(@Size(max = 36) String operatorId,
        @NotNull @DecimalMin("0") @DecimalMax("99999999") @Digits(integer = 8, fraction = 4) BigDecimal unit,
        @NotBlank @Size(max = QbExposureFees.REASON_LENGTH) String reason) {}

    public record PriceOutput(String operatorId, String priceId, BigDecimal unit) {}

    public static ProcessDefinition<PriceInput, PriceOutput, ProcessContext> set() {
        return ProcessDefinition.define(SET, 1, PriceInput.class, PriceOutput.class, ProcessContext.class, pb -> {
            // Headquarters' fee management (finance), not qb.fee.billing: the operators hold that one for their
            // own sponsors' billing mode, and this process names any operator.
            pb.description("Saves an operator's exposure price (headquarters).")
                .permissions(QbPermissions.FEE_MANAGE)
                .outputMapper(ctx -> ctx.get(OUTPUT, PriceOutput.class));
            common(pb);
            pb.compute("Whose price", (metadata, ctx) -> {
                    String operator = ctx.get(INPUT, PriceInput.class).operatorId();
                    ctx.put(OPERATOR_ID, operator == null ? "" : operator.strip());
                })
                .step("Load the operator named", QueryEntities.<ProcessContext>of(
                    QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("operatorId", operatorId(ctx).isEmpty()
                            ? List.of() : List.of(operatorId(ctx)))).limit(1).build(), OPERATOR))
                .compute("Refuse an unknown operator", (metadata, ctx) -> {
                    if (FeeGate.first(ctx, OPERATOR) == null) {
                        throw new EntityNotFoundException(QbOperators.OPERATOR + " " + operatorId(ctx) + " not found");
                    }
                });
            save(pb);
        });
    }

    public static ProcessDefinition<PriceInput, PriceOutput, ProcessContext> operatorSet() {
        return ProcessDefinition.define(OPERATOR_SET, 1, PriceInput.class, PriceOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Saves the caller operator's exposure price (its lead).")
                    .permissions(QbPermissions.OPERATOR_USE)
                    .outputMapper(ctx -> ctx.get(OUTPUT, PriceOutput.class));
                common(pb);
                OperatorChecks.member(pb);
                pb.compute("Only the lead, for their own operator", (metadata, ctx) -> {
                    if (!Boolean.TRUE.equals(ctx.get(OperatorChecks.LEAD))) {
                        throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_LEAD,
                            "Only the operator's lead sets its prices", Map.of()));
                    }
                    ctx.put(OPERATOR_ID, OperatorChecks.operatorId(ctx));
                });
                save(pb);
            });
    }

    private static <I, O> void common(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            });
        CallerChecks.refuseBanned(pb);
    }

    private static <I, O> void save(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the headquarters' range", LoadParams.<ProcessContext>optional(ProcessContext::opTime, RANGE,
                QbParams.EXPOSURE_RANGE.toArray(String[]::new)))
            .step("Take the price's turn", HoldLock.<ProcessContext>exclusive(
                ctx -> QbExposureFees.priceLockName(operatorId(ctx))))
            .step("Load the operator's price", QueryEntities.<ProcessContext>of(
                QbExposureFees.defaultDataset(QbExposureFees.PRICE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("operatorId", operatorId(ctx))).limit(1).build(), PRICE))
            .compute("Save the price", (metadata, ctx) -> save(ctx));
    }

    static void save(ProcessContext ctx) {
        PriceInput input = ctx.get(INPUT, PriceInput.class);
        List<Violation> problems = ExposureFeeRules.priceProblems(input.unit(), range(ctx.get(RANGE, ParamValues.class)));
        if (!problems.isEmpty()) {
            problems.forEach(ctx::reject);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("unit", input.unit());
        values.put("reason", input.reason().strip());
        values.put("updatedAt", ctx.opTime());
        values.put("updatedBy", ctx.request().actorId());
        EntityInstance row = FeeGate.first(ctx, PRICE);
        Object priceId;
        if (row == null) {
            values.put("operatorId", operatorId(ctx));
            priceId = ctx.changes().insert(QbExposureFees.PRICE, values);
        } else {
            priceId = row.id();
            ctx.changes().update(QbExposureFees.PRICE, row.id(), row.version(), values);
        }
        ctx.put(OUTPUT, new PriceOutput(operatorId(ctx), String.valueOf(priceId), input.unit()));
    }

    /** The headquarters' range as the parameters give it now (a bound not set does not bound: the range is incomplete). */
    public static ExposureFeeRules.Range range(ParamValues params) {
        return new ExposureFeeRules.Range(bound(params, QbParams.EXPOSURE_UNIT_MIN),
            bound(params, QbParams.EXPOSURE_UNIT_MAX));
    }

    private static BigDecimal bound(ParamValues params, String key) {
        return params == null ? null : params.find(key, BigDecimal.class).orElse(null);
    }

    static String operatorId(ProcessContext ctx) {
        return String.valueOf(ctx.get(OPERATOR_ID));
    }
}

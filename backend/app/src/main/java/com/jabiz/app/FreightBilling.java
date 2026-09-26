package com.jabiz.app;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.event.DomainEvent;
import com.jabiz.ledger.Direction;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample of business parameters and scenario replay (ROADMAP phase 8): freight charges of waybills with a fuel
 * surcharge, closed month by month.
 * <ul>
 *   <li>{@code FREIGHT_CHARGE} charges a waybill: its freight plus the fuel surcharge at the rate
 *       {@value #SURCHARGE_RATE} in effect <em>when the waybill was shipped</em> (the business time, not the time of
 *       charging; docs/design/04-temporal-append-only.md section 9). A rate change scheduled for the first of a month
 *       thus applies to waybills shipped from then on, even those charged earlier or later.</li>
 *   <li>Each month has one statement, kept up to date by every charge of the month (number and total).</li>
 *   <li>{@code FREIGHT_MONTH_CLOSE} closes a month (Japan time) once it has ended: the statement is closed with the
 *       number and total of its charges, which are marked settled. A closed month takes no more charges. The job
 *       {@value #CLOSE_JOB} runs it for the previous month on the first of each month; a successful close publishes
 *       {@value #MONTH_CLOSED_EVENT}.</li>
 *   <li>{@code FREIGHT_POST_REVENUE}, the consumer of that event, posts the month's total to the ledger (accounts
 *       receivable to freight revenue), booked at the end of the month (ROADMAP phase 9).</li>
 * </ul>
 * Charges and the close both update the month's statement under its optimistic lock (or insert it, under its unique
 * month), so a charge racing a close of the same month makes one of them fail (409 or 400 {@code UNIQUE_VIOLATION})
 * instead of leaving an unsettled charge behind a closed month; the caller retries.
 */
public final class FreightBilling extends BaseEntityDefinitions {

    public static final String CHARGE = "FreightCharge";
    public static final String STATEMENT = "FreightStatement";
    public static final String CHARGE_DATASET = "urn:jabiz:dataset:default:FreightCharge";
    public static final String STATEMENT_DATASET = "urn:jabiz:dataset:default:FreightStatement";
    static final String WAYBILL_DATASET = "urn:jabiz:dataset:default:WaybillTracking";

    /** Business parameter: fuel surcharge as a fraction of the freight, {@code numeric(5,4)}. */
    public static final String SURCHARGE_RATE = "logistics.fuel-surcharge-rate";

    /** Months are business months in Japan. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    /** Published by a successful close (ROADMAP phase 9): its payload is the {@link CloseOutput}. */
    public static final String MONTH_CLOSED_EVENT = "logistics.freight-month-closed";
    /** Consumer of {@link #MONTH_CLOSED_EVENT} that posts the month's revenue to the ledger. */
    public static final String REVENUE_CONSUMER = "logistics.freight-revenue";
    /** The job that closes the previous month shortly after midnight on the first of each month (Japan time). */
    public static final String CLOSE_JOB = "logistics.freight-month-close";
    /** Ledger accounts of the revenue posting: accounts receivable (debit) and freight revenue (credit). */
    public static final String RECEIVABLE_ACCOUNT = "1130";
    public static final String REVENUE_ACCOUNT = "4110";

    public static final String MONTH_CLOSED = "FREIGHT_MONTH_CLOSED";
    public static final String MONTH_NOT_ENDED = "FREIGHT_MONTH_NOT_ENDED";
    public static final String WAYBILL_INCOMPLETE = "FREIGHT_WAYBILL_INCOMPLETE";
    public static final String MONTH_TOO_LARGE = "FREIGHT_MONTH_TOO_LARGE";

    /** Most charges one close settles in one operation. */
    static final int MAX_CHARGES_PER_CLOSE = 1000;
    /** One more than can be closed is read, to tell "exactly the maximum" from "more". */
    static final int CLOSE_QUERY_LIMIT = MAX_CHARGES_PER_CLOSE + 1;

    public static final EntityDefinition FREIGHT_CHARGE = EntityDefinition.define(CHARGE, eb -> {
        eb.physicalTable("t_freight_charge");
        eb.primaryKey("chargeId");
        eb.field("chargeId", semanticIdentity("f_charge_id", "urn:jabiz:entity:logistics:freight-charge")
            .andThen(f -> f.generated(true)));
        eb.field("waybillId", f -> f.physicalColumn("f_wb_sn").immutable(true).required(true)
            .asReference("WaybillTracking"));
        eb.field("chargeMonth", f -> f.physicalColumn("f_charge_month").immutable(true).required(true).asText(7));
        eb.field("shippedTime", f -> f.physicalColumn("f_shipped_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("baseAmount", f -> f.physicalColumn("f_base_amt").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("surchargeRate", f -> f.physicalColumn("f_surcharge_rate").immutable(true).required(true)
            .asNumeric(5, 4));
        eb.field("surchargeAmount", f -> f.physicalColumn("f_surcharge_amt").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("totalAmount", f -> f.physicalColumn("f_total_amt").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("settled", f -> f.physicalColumn("f_settled").required(true).asBool());
        eb.field("recordedTime", systemRecordedTime("f_sys_created_at"));
        eb.field("rowVersion", rowVersion("f_version"));
        eb.unique("uk_freight_charge_waybill", "waybillId");
        eb.listView("default", lv -> lv
            .columns("waybillId", "chargeMonth", "baseAmount", "surchargeRate", "surchargeAmount", "totalAmount",
                "settled")
            .filters("waybillId", "chargeMonth", "settled")
            .sorts("chargeMonth", "waybillId")
            .defaultSort("waybillId", true));
    });

    public static final EntityDefinition FREIGHT_STATEMENT = EntityDefinition.define(STATEMENT, eb -> {
        eb.physicalTable("t_freight_statement");
        eb.primaryKey("statementId");
        eb.field("statementId", semanticIdentity("f_statement_id", "urn:jabiz:entity:logistics:freight-statement")
            .andThen(f -> f.generated(true)));
        eb.field("statementMonth", f -> f.physicalColumn("f_statement_month").immutable(true).required(true)
            .asText(7));
        eb.field("chargeCount", f -> f.physicalColumn("f_charge_count").required(true).asNumeric(9, 0));
        eb.field("totalAmount", f -> f.physicalColumn("f_total_amt").required(true).asMonetary("JPY", 0));
        eb.field("closed", f -> f.physicalColumn("f_closed").required(true).asBool());
        eb.field("closedTime", f -> f.physicalColumn("f_closed_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("recordedTime", systemRecordedTime("f_sys_created_at"));
        eb.field("rowVersion", rowVersion("f_version"));
        eb.unique("uk_freight_statement_month", "statementMonth");
        eb.listView("default", lv -> lv
            .columns("statementMonth", "chargeCount", "totalAmount", "closed", "closedTime")
            .filters("statementMonth")
            .sorts("statementMonth")
            .defaultSort("statementMonth", false));
    });

    public record ChargeInput(@NotBlank String waybillId) {}

    public record ChargeOutput(String chargeId, String waybillId, String chargeMonth, BigDecimal surchargeRate,
        BigDecimal surchargeAmount, BigDecimal totalAmount) {}

    /** @param month {@code yyyy-MM}, Japan time */
    public record CloseInput(@NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String month) {}

    public record CloseOutput(String statementId, String month, int chargeCount, BigDecimal totalAmount) {}

    /** @param totalAmount the month's total, as published by the close */
    public record RevenueInput(@NotBlank String month, String statementId, @NotNull BigDecimal totalAmount) {}

    /** @param transactionId the ledger transaction; null for a month without charges */
    public record RevenueOutput(String month, String transactionId) {}

    /** What the charging steps hand each other. */
    public static final class ChargeContext extends ProcessContext {

        static final String WAYBILL_ID = "waybillId";
        static final String WAYBILL = "waybill";
        static final String STATEMENTS = "statements";
        static final String PARAMS = "params";

        private ChargeOutput output;

        ChargeContext(ProcessStart start, ChargeInput input) {
            super(start);
            put(WAYBILL_ID, input.waybillId());
        }

        EntityInstance waybill() {
            return get(WAYBILL, EntityInstance.class);
        }

        /** The business time of the charge: when the waybill was shipped. */
        Instant shippedTime() {
            return waybill().get("shippedTime");
        }

        /** The month of the charge; null while the waybill has no shipping time. */
        String month() {
            Instant shipped = shippedTime();
            return shipped == null ? null : YearMonth.from(shipped.atZone(ZONE)).toString();
        }
    }

    public static final ProcessDefinition<ChargeInput, ChargeOutput, ChargeContext> CHARGE_PROCESS =
        ProcessDefinition.define("FREIGHT_CHARGE", 1, ChargeInput.class, ChargeOutput.class, ChargeContext.class,
            pb -> pb
                .description("Charges the freight of a waybill with the fuel surcharge in effect when it shipped.")
                .permissions("logistics.freight.charge")
                .contextFactory(ChargeContext::new)
                .outputMapper(ctx -> ctx.output)
                .step("Load the waybill", LoadEntity.by(WAYBILL_DATASET, ChargeContext.WAYBILL_ID,
                    ChargeContext.WAYBILL))
                .step("Load the statement of its month", QueryEntities.of(STATEMENT_DATASET,
                    ctx -> statementOf(ctx.month() == null ? "" : ctx.month()), ChargeContext.STATEMENTS))
                // The rate in effect when the waybill shipped, not now (docs/design/04 section 9).
                .step("Load the surcharge rate", LoadParams.of(
                    ctx -> ctx.shippedTime() == null ? ctx.opTime() : ctx.shippedTime(), ChargeContext.PARAMS,
                    SURCHARGE_RATE))
                .compute("Compute the charge", (metadata, ctx) -> charge(ctx)));

    public static final ProcessDefinition<CloseInput, CloseOutput, ProcessContext> CLOSE_PROCESS =
        ProcessDefinition.define("FREIGHT_MONTH_CLOSE", 1, CloseInput.class, CloseOutput.class,
            ProcessContext.class, pb -> pb
                .description("Closes an ended month into a statement and settles its charges.")
                .permissions("logistics.freight.close")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("month", input.month());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get("output", CloseOutput.class))
                .step("Load the statement of the month", QueryEntities.of(STATEMENT_DATASET,
                    ctx -> statementOf(ctx.get("month", String.class)), "statements"))
                .step("Load the open charges of the month", QueryEntities.of(CHARGE_DATASET,
                    ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("chargeMonth", ctx.get("month", String.class)),
                            new QueryPredicate.Eq("settled", false))))
                        .limit(CLOSE_QUERY_LIMIT)
                        .build(), "charges"))
                .compute("Close the month", (metadata, ctx) -> close(ctx))
                .step("Publish the closed month", PublishEvent.<ProcessContext>when(ctx -> ctx.contains("output"),
                    MONTH_CLOSED_EVENT, ctx -> ctx.get("output", CloseOutput.class))));

    public static final ProcessDefinition<RevenueInput, RevenueOutput, ProcessContext> REVENUE_PROCESS =
        ProcessDefinition.define("FREIGHT_POST_REVENUE", 1, RevenueInput.class, RevenueOutput.class,
            ProcessContext.class, pb -> pb
                .description("Posts the freight revenue of a closed month to the ledger.")
                .permissions("logistics.freight.post")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new RevenueOutput(ctx.get("input", RevenueInput.class).month(),
                    ctx.contains("posting") ? ctx.get("posting", LedgerProcesses.PostOutput.class).transactionId()
                        : null))
                .step("Post the revenue", CallProcess.<ProcessContext>when(
                    ctx -> ctx.get("input", RevenueInput.class).totalAmount().signum() > 0,
                    LedgerProcesses.POST, 1, FreightBilling::revenuePosting, "posting")));

    /** Accounts receivable to freight revenue, booked at the last moment of the month (Japan time). */
    static LedgerProcesses.PostInput revenuePosting(ProcessContext ctx) {
        RevenueInput input = ctx.get("input", RevenueInput.class);
        Instant monthEnd = YearMonth.parse(input.month()).plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant()
            .minusSeconds(1);
        return new LedgerProcesses.PostInput(monthEnd, "Freight revenue " + input.month(), input.statementId(),
            List.of(new LedgerProcesses.Line(RECEIVABLE_ACCOUNT, Direction.DEBIT, input.totalAmount()),
                new LedgerProcesses.Line(REVENUE_ACCOUNT, Direction.CREDIT, input.totalAmount())));
    }

    /** The close of the previous month, for the job firing at {@code scheduledTime}. */
    static CloseInput previousMonth(Instant scheduledTime) {
        return new CloseInput(YearMonth.from(scheduledTime.atZone(ZONE)).minusMonths(1).toString());
    }

    /** The revenue posting for a {@link #MONTH_CLOSED_EVENT}; the payload is the close's output. */
    static RevenueInput revenueOf(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        return new RevenueInput(String.valueOf(payload.get("month")),
            payload.get("statementId") == null ? null : String.valueOf(payload.get("statementId")),
            new BigDecimal(String.valueOf(payload.get("totalAmount"))));
    }

    private static EntityQuery statementOf(String month) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("statementMonth", month)).limit(1).build();
    }

    private static void charge(ChargeContext ctx) {
        EntityInstance waybill = ctx.waybill();
        BigDecimal freight = waybill.get("freightCharge");
        if (freight == null || ctx.shippedTime() == null) {
            ctx.reject(new Violation("waybillId", WAYBILL_INCOMPLETE,
                "Waybill " + waybill.id() + " has no freight or shipping time yet"));
            return;
        }
        @SuppressWarnings("unchecked")
        List<EntityInstance> statements = (List<EntityInstance>) ctx.get(ChargeContext.STATEMENTS);
        EntityInstance statement = statements.isEmpty() ? null : statements.getFirst();
        if (statement != null && Boolean.TRUE.equals(statement.get("closed"))) {
            ctx.reject(new Violation("waybillId", MONTH_CLOSED, "Month " + ctx.month() + " is closed",
                Map.of("month", ctx.month())));
            return;
        }
        BigDecimal rate = ctx.get(ChargeContext.PARAMS, ParamValues.class).get(SURCHARGE_RATE, BigDecimal.class);
        // JPY has no minor unit.
        BigDecimal surcharge = freight.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        BigDecimal total = freight.add(surcharge);
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("waybillId", waybill.id());
        charge.put("chargeMonth", ctx.month());
        charge.put("shippedTime", ctx.shippedTime());
        charge.put("baseAmount", freight);
        charge.put("surchargeRate", rate);
        charge.put("surchargeAmount", surcharge);
        charge.put("totalAmount", total);
        charge.put("settled", false);
        Object chargeId = ctx.changes().insert(CHARGE, charge);
        if (statement == null) {
            Map<String, Object> opened = new LinkedHashMap<>();
            opened.put("statementMonth", ctx.month());
            opened.put("chargeCount", BigDecimal.ONE);
            opened.put("totalAmount", total);
            opened.put("closed", false);
            ctx.changes().insert(STATEMENT, opened);
        } else {
            BigDecimal count = statement.get("chargeCount");
            BigDecimal sum = statement.get("totalAmount");
            ctx.changes().update(STATEMENT, statement.id(), statement.version(),
                Map.of("chargeCount", count.add(BigDecimal.ONE), "totalAmount", sum.add(total)));
        }
        ctx.output = new ChargeOutput(String.valueOf(chargeId), String.valueOf(waybill.id()), ctx.month(), rate,
            surcharge, total);
    }

    private static void close(ProcessContext ctx) {
        String month = ctx.get("month", String.class);
        Instant monthEnd = YearMonth.parse(month).plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
        if (ctx.opTime().isBefore(monthEnd)) {
            ctx.reject(new Violation("month", MONTH_NOT_ENDED, "Month " + month + " has not ended yet",
                Map.of("month", month)));
            return;
        }
        @SuppressWarnings("unchecked")
        List<EntityInstance> statements = (List<EntityInstance>) ctx.get("statements");
        EntityInstance statement = statements.isEmpty() ? null : statements.getFirst();
        if (statement != null && Boolean.TRUE.equals(statement.get("closed"))) {
            ctx.reject(new Violation("month", MONTH_CLOSED, "Month " + month + " is closed", Map.of("month", month)));
            return;
        }
        @SuppressWarnings("unchecked")
        List<EntityInstance> charges = (List<EntityInstance>) ctx.get("charges");
        if (charges.size() > MAX_CHARGES_PER_CLOSE) {
            // Settling only part of the month would leave charges behind a closed month.
            ctx.reject(new Violation("month", MONTH_TOO_LARGE, "Month " + month + " has too many charges to close",
                Map.of("max", MAX_CHARGES_PER_CLOSE)));
            return;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (EntityInstance charge : charges) {
            total = total.add(charge.get("totalAmount"));
            ctx.changes().update(CHARGE, charge.id(), charge.version(), Map.of("settled", true));
        }
        Map<String, Object> closed = new LinkedHashMap<>();
        closed.put("chargeCount", BigDecimal.valueOf(charges.size()));
        closed.put("totalAmount", total);
        closed.put("closed", true);
        closed.put("closedTime", ctx.opTime());
        Object statementId;
        if (statement == null) {
            // A month without charges is closed all the same.
            closed.put("statementMonth", month);
            statementId = ctx.changes().insert(STATEMENT, closed);
        } else {
            // Under the statement's optimistic lock: a charge committed since it was read makes this fail (409).
            statementId = statement.id();
            ctx.changes().update(STATEMENT, statement.id(), statement.version(), closed);
        }
        ctx.put("output", new CloseOutput(String.valueOf(statementId), month, charges.size(), total));
    }

    /** Datasets of the charges and statements; the write batch fits the largest close. */
    static DatasetDefinition dataset(String id, String entity, String permission, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(permission + ".read", permission + ".write")
            .policy(p -> p.maxQueryBatchSize(CLOSE_QUERY_LIMIT).maxWriteBatchSize(MAX_CHARGES_PER_CLOSE + 1))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private FreightBilling() {}
}

package com.jabiz.quizbuks.operator;

import com.jabiz.approval.ContentHash;
import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.prospect.QbProspects;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.BankTransfers;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.TransferProcesses;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Operators' monthly settlements (docs/quizbuks/plans/Q48-operators.md, Q48b; D-Q48-6, D-Q48-7).
 *
 * <ul>
 *   <li>{@value #RUN} ({@code internal()}, the item of the daily job {@value #JOB}): one operator's month (Japan's
 *       calendar month, {@code jabiz.fiscal-year-zone}). F-M12 counts each platform fee of the operator's sponsors (by
 *       the operator frozen on the fee) and each part of a payment made with granted credit exactly once, writing a
 *       line ({@link QbSettlementLines}) for each; the part of the fees in the base ({@code qb.operator.share.kinds}),
 *       less the credit used the platform bears, less the platform's share of the operator's plan that month, is the
 *       operator's share, posted as an expense owed (debit 5130 / credit 2140 / 4110). The credit the operator bears
 *       is taken back from it (debit 2140 / credit 5140), carried when the share does not cover it. The month's
 *       prospecting cost (Q48d), the plan's fixed fee and F-M7's credit-note deduction are deducted; Q48b's commission
 *       (4110) is gone (Q92a). Finance issues the statement ({@value #STATEMENT}).</li>
 *   <li>{@value #PAY} (finance, {@code qb.operator.settlement.pay}): records the payment made by bank (the day and the
 *       statement's reference) for another administrator's approval ({@value #APPROVAL_SUBJECT}); nothing is posted
 *       before it.</li>
 *   <li>{@value #APPROVAL_RESULT} ({@code internal()}, on the approval events): an approval of the settlement's own
 *       request posts the payment (debit 2140 / credit 1120) and marks it paid; a rejection returns it to issued.</li>
 * </ul>
 * Each takes the operator's lock {@code qb.operator:<operator>} before it reads the settlement.
 */
public final class OperatorSettlements {

    public static final String SETTLEMENT = "QbOperatorSettlement";
    public static final String RUN = "QB_OPERATOR_SETTLEMENT_RUN";
    public static final String PAY = "QB_OPERATOR_SETTLEMENT_PAY";
    public static final String APPROVAL_RESULT = "QB_OPERATOR_SETTLEMENT_APPROVAL_RESULT";
    public static final String STATEMENT = "QB_OPERATOR_SETTLEMENT_STATEMENT";
    public static final String JOB = "qb.operator-settlement";
    public static final String DUE_TEMPLATE = "qb.system.operator-settlements-due";
    /** F-M12 b (§3.1): the steps before the settlement not done yet (distribution fees, invoices). */
    public static final String READINESS_TEMPLATE = "qb.system.operator-settlement-readiness";
    /** F-M12 b: the source key of a finance task not made yet. */
    public static final String ALERT_TEMPLATE = "qb.system.operator-settlement-alert";
    public static final String CAPPED_TASK = "qb.operator-settlement.capped";
    public static final String CAPPED_TASK_TITLE = "task.qb.operator-settlement.capped";
    /** F-M12 §3.2: the fees to count (every kind, from the switch point on, not already counted, not voided). */
    public static final String CHARGES_TEMPLATE = "qb.system.operator-settlement-charges";
    /** F-M12 §3.5: counted fees whose invoice was voided since. */
    public static final String VOIDS_TEMPLATE = "qb.system.operator-settlement-voids";
    /** F-M12 §3.3: the parts of payments made with granted credit to count, resolved to the fee they paid. */
    public static final String GIFTS_TEMPLATE = "qb.system.operator-settlement-gifts";
    /** F-M7 (C3): the credit notes issued in the month on the operator's fees (before tax). */
    public static final String CREDIT_NOTES_TEMPLATE = "qb.system.operator-credit-notes";
    /**
     * F-M12 §3.9 (option B): the cap on one settlement's lines (fees, voids and credit used together), far above a
     * month's lines of a middling operator (thousands). The fees that do not fit wait for the next settlement.
     */
    public static final int MAX_LINES = 20_000;
    public static final String DOCUMENT_TEMPLATE = "qb.admin.operator-settlement-doc";
    public static final String ISSUER_TEMPLATE = "qb.admin.settlement-issuer";
    public static final String LAYOUT_ID = "qb.operator_settlement";
    public static final String APPROVAL_SUBJECT = "qb.operator-settlement";
    public static final String REVIEW_RULE = "qb.operator-settlement.review";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:operator-settlement-status";
    /**
     * The language of the statements of an operator without one: the first operators are in Japan (§6 item 5). An
     * operator with a language (Q59c) gets them in that one.
     */
    public static final String LANGUAGE = "ja";

    public static final String ISSUED = "ISSUED";
    /** Q48e-2: credited to the operator's account, which the operator withdraws from (no payment of its own). */
    public static final String CREDITED = "CREDITED";
    public static final String PAYING = "PAYING";
    public static final String PAID = "PAID";
    public static final List<String> STATUSES = List.of(ISSUED, PAYING, PAID, CREDITED);

    /** The ledger's references of the two postings: the settlement's month and operator. */
    static final String ISSUE_DESCRIPTION = "Operator share";
    static final String GIFT_DESCRIPTION = "Operator gift recovery";
    static final String PAYMENT_DESCRIPTION = "Operator share paid";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    public static final DocumentLayout LAYOUT = DocumentLayout.define(LAYOUT_ID, d -> d
        .permissions(QbPermissions.ADMIN_FINANCE_READ)
        .subject(SETTLEMENT, "settlementId")
        .number(DOCUMENT_TEMPLATE, "settlementNo")
        .party("issuer", ISSUER_TEMPLATE, "name", "address", "registrationNo")
        .party("operator", DOCUMENT_TEMPLATE, "operatorName", "contactName")
        .facts(DOCUMENT_TEMPLATE, "month", "plan", "platformFees", "costExcluded", "giftPaid", "platformShare",
            "operatorShare", "fixedFee", "prospectingCost", "creditNoteDeduction", "giftShareDeduction",
            "voidDeduction", "carriedIn", "giftDue", "giftRecovered", "giftCarriedIn", "giftCarriedOut", "carriedOut")
        .totals(DOCUMENT_TEMPLATE, "payable")
        .note("note"));

    public static final EntityDefinition SETTLEMENT_ENTITY = EntityDefinition.define(SETTLEMENT, eb -> {
        eb.physicalTable("qb_operator_settlement");
        eb.primaryKey("settlementId");
        eb.field("settlementId", f -> f.physicalColumn("settlement_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:operator-settlement"));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true)
            .asReference(QbOperators.OPERATOR));
        eb.field("month", f -> f.physicalColumn("month").immutable(true).required(true).asText(7));
        eb.field("periodStart", f -> f.physicalColumn("period_start").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("periodEnd", f -> f.physicalColumn("period_end").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // Q48b's commission (4110). Kept for the old rows; F-M12 (Q92a) drops it: always 0 on the new settlements.
        eb.field("revenue", f -> f.physicalColumn("revenue").immutable(true).required(true).asMonetary("JPY", 0));
        // Q87: the platform fees counted in the month (before tax), before the cost part and the credit used are
        // taken off.
        eb.field("platformFees", f -> f.physicalColumn("platform_fees").immutable(true).required(true)
            .asMonetary("JPY", 0));
        // F-M12: the switch point (the end of the operator's last old settlement); empty on the old rows, which tells
        // the two apart.
        eb.field("coveredFrom", f -> f.physicalColumn("covered_from").immutable(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // F-M12: the credit used in this batch not shared in (before tax, signed); the share of the credit used in
        // earlier months taken back (or given back) now; the share of fees voided now; the cost part taken out of the
        // base this month.
        eb.field("giftPaid", f -> f.physicalColumn("gift_paid").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("giftShareDeduction", f -> f.physicalColumn("gift_share_deduction").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("voidDeduction", f -> f.physicalColumn("void_deduction").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("costExcluded", f -> f.physicalColumn("cost_excluded").immutable(true).required(true)
            .asMonetary("JPY", 0));
        // F-M12: the credit taken back (with tax). What is due of it, what was carried in, what was taken, what is
        // carried.
        eb.field("giftUsed", f -> f.physicalColumn("gift_used").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("giftDue", f -> f.physicalColumn("gift_due").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("giftCarriedIn", f -> f.physicalColumn("gift_carried_in").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("giftRecovered", f -> f.physicalColumn("gift_recovered").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("giftCarriedOut", f -> f.physicalColumn("gift_carried_out").immutable(true).required(true)
            .asMonetary("JPY", 0));
        // F-M12 b: the part of a gift refund that offset the cost carried (posted to 4110); the cap on the lines (plan
        // §3.9, option B): whether the fees waiting did not all fit, how many were left, the last fee counted, and the
        // settings kept for the fees left (they are counted later at these and at platformShare).
        eb.field("costOffset", f -> f.physicalColumn("cost_offset").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("capped", f -> f.physicalColumn("capped").immutable(true).required(true).asBool());
        eb.field("uncounted", f -> f.physicalColumn("uncounted").immutable(true).required(true).asNumeric(9, 0));
        eb.field("watermarkChargedAt", f -> f.physicalColumn("watermark_charged_at").immutable(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("watermarkChargeId", f -> f.physicalColumn("watermark_charge_id").immutable(true).asText(36));
        eb.field("cappedTerms", f -> f.physicalColumn("capped_terms").immutable(true).asText(1000));
        eb.field("platformShare", f -> f.physicalColumn("platform_share").immutable(true).required(true)
            .asNumeric(5, 4));
        eb.field("operatorShare", f -> f.physicalColumn("operator_share").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("prospectingCost", f -> f.physicalColumn("prospecting_cost").immutable(true).required(true)
            .asMonetary("JPY", 0));
        // Q48e-2: the operator's plan that month and its fixed fee (0 while the operator is suspended).
        eb.field("plan", f -> f.physicalColumn("plan").immutable(true).asText(2));
        eb.field("fixedFee", f -> f.physicalColumn("fixed_fee").immutable(true).required(true)
            .asMonetary("JPY", 0));
        // F-M7 (C3): the credit notes issued in the month on its sponsors' fees (before tax), and the operator's share of
        // them taken back as a cost of the month (carried as the prospecting cost is).
        eb.field("creditNotes", f -> f.physicalColumn("credit_notes").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("creditNoteDeduction", f -> f.physicalColumn("credit_note_deduction").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("carriedIn", f -> f.physicalColumn("carried_in").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("payable", f -> f.physicalColumn("payable").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("carriedOut", f -> f.physicalColumn("carried_out").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("documentNo", f -> f.physicalColumn("document_no").asText(100));
        eb.field("documentRunId", f -> f.physicalColumn("document_run_id").asText(36));
        eb.field("issueTransactionId", f -> f.physicalColumn("issue_transaction_id").asText(36));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("paidOn", f -> f.physicalColumn("paid_on").asDate());
        eb.field("bankReference", f -> f.physicalColumn("bank_reference").asText(100));
        eb.field("paymentRecordedBy", f -> f.physicalColumn("payment_recorded_by").asText(128));
        eb.field("paymentRecordedAt", f -> f.physicalColumn("payment_recorded_at")
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").asText(36));
        eb.field("paymentTransactionId", f -> f.physicalColumn("payment_transaction_id").asText(36));
        eb.field("paidAt", f -> f.physicalColumn("paid_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_operator_settlement_month", "operatorId", "month");
        eb.listView("default", lv -> lv
            .columns("operatorId", "month", "revenue", "operatorShare", "payable", "status", "paidAt")
            .filters("operatorId", "month", "status")
            .sorts("month", "payable")
            .defaultSort("month", false));
    });

    // ---- inputs and outputs ------------------------------------------------------------------------------------------

    /** @param month {@code yyyy-MM}, Japan's */
    public record RunInput(@NotBlank @Size(max = 36) String operatorId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String month) {}

    /**
     * @param paidOn        the bank statement's day of the payment, in Japan
     * @param bankReference the statement's reference of the transfer
     */
    public record PayInput(@NotBlank @Size(max = 36) String settlementId, @NotNull LocalDate paidOn,
        @NotBlank @Size(max = BankTransfers.REFERENCE_LENGTH) String bankReference) {}

    /** @param approvalRequestId the request another administrator decides (a payment) */
    public record SettlementOutput(String settlementId, String status, BigDecimal payable, String documentNo,
        String approvalRequestId) {}

    static final String INPUT = "input";
    static final String OPERATOR = "operator";
    static final String SETTLEMENT_ROW = "settlement";
    static final String FOUND = "found";
    static final String PREVIOUS = "previous";
    static final String PARAMS = "params";
    static final String PLANS = "plans";
    static final String USAGE = "usage";
    static final String LAST_OLD = "lastOld";
    static final String CREDIT_NOTES = "creditNotes";
    static final String CHARGES = "charges";
    static final String VOIDS = "voids";
    static final String GIFTS = "gifts";
    static final String COVERED_FROM = "coveredFrom";
    static final String COUNTED_NOW = "countedNow";
    static final String SKIP = "skip";
    static final String READINESS = "readiness";
    static final String ALERT_SOURCE = "alertSource";
    static final String ALERT_ROWS = "alertRows";
    static final String DEFERRED = "deferred";
    static final String CHARGE_TERMS = "chargeTerms";
    static final String CAPPED = "capped";
    static final String UNCOUNTED = "uncounted";
    static final String WATERMARK = "watermark";
    static final String OLDEST_UNCOUNTED = "oldestUncounted";
    static final String WAIT_UNTIL = "waitUntil";
    static final String NEW_ID = "newId";
    static final String NEW_ROW = "newRow";
    static final String POST_INPUT = "postInput";
    static final String POST_INPUT_GIFT = "postInputGift";
    static final String POSTING = "posting";
    static final String POSTING_GIFT = "postingGift";
    static final String CHARGE_LINES = "chargeLines";
    static final String CHARGE_BY_ID = "chargeById";
    static final String TERMS = "terms";
    static final String ISSUED_DOCUMENT = "issuedDocument";
    static final String APPROVAL = "approval";
    static final String REQUEST = "request";
    static final String RULES = "rules";
    static final String STATUS_AFTER = "statusAfter";

    private OperatorSettlements() {}

    // ---- the month -----------------------------------------------------------------------------------------------------

    /** The month {@code time} falls in, in {@code zone}. */
    public static String currentMonth(Instant time, ZoneId zone) {
        return MONTH.format(YearMonth.from(time.atZone(zone)));
    }

    /** The month before the one {@code time} falls in, in {@code zone}. */
    public static String previousMonth(Instant time, ZoneId zone) {
        return MONTH.format(YearMonth.from(time.atZone(zone)).minusMonths(1));
    }

    public static Instant monthStart(String month, ZoneId zone) {
        return YearMonth.parse(month, MONTH).atDay(1).atStartOfDay(zone).toInstant();
    }

    public static Instant monthEnd(String month, ZoneId zone) {
        return YearMonth.parse(month, MONTH).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
    }

    /**
     * The operator's share of a month's commission, rounded down to the yen (D-Q48-7): never more than the commission
     * less the platform's share, and nothing of a negative month (a month whose reversals exceed its rewards).
     */
    public static BigDecimal operatorShare(BigDecimal revenue, BigDecimal platformShare) {
        if (revenue.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return revenue.multiply(BigDecimal.ONE.subtract(platformShare)).setScale(0, RoundingMode.DOWN);
    }

    /**
     * F-M7 (C3): what the operator gives back of the credit notes issued on its sponsors' fees in a month, before tax:
     * the share it was given of them, ⌊notes × (1 − the platform's share that month)⌋.
     */
    public static BigDecimal creditNoteDeduction(BigDecimal creditNotes, BigDecimal platformShare) {
        return operatorShare(creditNotes, platformShare);
    }

    /**
     * What is payable and carried out: the share less the month's prospecting cost and what was carried in, never
     * below 0 (§6 item 4); the rest is carried to the next month.
     */
    public static BigDecimal[] payable(BigDecimal share, BigDecimal cost, BigDecimal carriedIn) {
        BigDecimal net = share.subtract(cost).subtract(carriedIn);
        return net.signum() >= 0 ? new BigDecimal[] {net, BigDecimal.ZERO} : new BigDecimal[] {BigDecimal.ZERO,
            net.negate()};
    }

    // ---- the monthly run -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<RunInput, SettlementOutput, ProcessContext> run(ZoneId zone) {
        return run(zone, MAX_LINES);
    }

    /** @param maxLines the cap on a settlement's lines (plan §3.9, option B): {@link #MAX_LINES}, lower only in tests */
    public static ProcessDefinition<RunInput, SettlementOutput, ProcessContext> run(ZoneId zone, int maxLines) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("The cap on a settlement's lines must be at least 1");
        }
        return ProcessDefinition.define(RUN, 1, RunInput.class, SettlementOutput.class, ProcessContext.class, pb -> pb
            .description("Settles one operator's month: its share of the commission, posted as owed, and the "
                + "statement issued.")
            .permissions(QbPermissions.OPERATOR_SETTLEMENT_SYSTEM)
            .internal()
            .contextFactory(OperatorSettlements::context)
            .outputMapper(OperatorSettlements::output)
            .step("Lock the operator", HoldLock.<ProcessContext>exclusive(
                ctx -> QbOperators.operatorLockName(runInput(ctx).operatorId())))
            .step("Load the operator, its month and the month before", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("operatorId", runInput(ctx).operatorId())).limit(1).build(),
                    OPERATOR),
                QueryEntities.query(QbOperators.defaultDataset(SETTLEMENT), ctx -> monthOf(runInput(ctx)
                    .operatorId(), runInput(ctx).month()), SETTLEMENT_ROW),
                QueryEntities.query(QbOperators.defaultDataset(SETTLEMENT), ctx -> monthOf(runInput(ctx)
                    .operatorId(), MONTH.format(YearMonth.parse(runInput(ctx).month(), MONTH).minusMonths(1))),
                    PREVIOUS),
                QueryEntities.query(ParamEntities.DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In(ParamEntities.KEY, paramKeys())).limit(40).build(), PARAMS),
                QueryEntities.query(QbOperators.defaultDataset(OperatorPlans.PLAN),
                    ctx -> OperatorPlans.changesOf(runInput(ctx).operatorId()), PLANS),
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.USAGE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("owner", runInput(ctx).operatorId()),
                        new QueryPredicate.Eq("month", runInput(ctx).month())))).limit(1).build(), USAGE),
                // F-M12 §3.0: the operator's last old settlement (no switch point) gives the switch point.
                QueryEntities.query(QbOperators.defaultDataset(SETTLEMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("operatorId",
                        runInput(ctx).operatorId()), new QueryPredicate.IsNull("coveredFrom"))))
                    .orderBy("periodEnd", false).limit(1).build(), LAST_OLD)))
            .compute("Find the switch point", (metadata, ctx) -> prepare(ctx, zone))
            // F-M12 b (§3.1): only until the deadline; later the steps not done are counted next time as late.
            .step("Check the steps before it", RunTemplate.<ProcessContext>when(ctx -> !skip(ctx) && waiting(ctx),
                READINESS_TEMPLATE, ctx -> Map.of("operatorId", runInput(ctx).operatorId(), "month",
                    runInput(ctx).month(), "periodStart", monthStart(runInput(ctx).month(), zone), "periodEnd",
                    monthEnd(runInput(ctx).month(), zone)), READINESS))
            .compute("Wait for them or go on", (metadata, ctx) -> decideReadiness(ctx))
            .step("Add up the credit notes", RunTemplate.<ProcessContext>when(ctx -> !skip(ctx), CREDIT_NOTES_TEMPLATE,
                ctx -> Map.of("operatorId", runInput(ctx).operatorId(), "from", monthStart(runInput(ctx).month(), zone),
                    "to", monthEnd(runInput(ctx).month(), zone)), CREDIT_NOTES))
            .step("Read the fees to count", RunTemplate.<ProcessContext>when(ctx -> !skip(ctx), CHARGES_TEMPLATE,
                // One more than the cap: a cap that cuts is seen without trusting a read limit.
                ctx -> Map.of("operatorId", runInput(ctx).operatorId(), "coveredFrom", ctx.get(COVERED_FROM),
                    "month", runInput(ctx).month(), "maxFees", (long) maxLines + 1), CHARGES))
            .step("Read the voided fees", RunTemplate.<ProcessContext>when(ctx -> !skip(ctx), VOIDS_TEMPLATE,
                ctx -> Map.of("operatorId", runInput(ctx).operatorId()), VOIDS))
            .step("Read the credit used", RunTemplate.<ProcessContext>when(ctx -> !skip(ctx), GIFTS_TEMPLATE,
                // The gifts of every fee that waits (the cap decides below which are counted now).
                ctx -> Map.of("operatorId", runInput(ctx).operatorId(), "coveredFrom", ctx.get(COVERED_FROM),
                    "countedNow", candidateIds(ctx)), GIFTS))
            .compute("Count the fees", (metadata, ctx) -> countCharges(ctx, maxLines))
            .compute("Settle", (metadata, ctx) -> settle(ctx, zone))
            .step("Save the settlement", SaveChanges.now())
            .step("Post the share owed", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
            .step("Post the gift recovery", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT_GIFT),
                LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT_GIFT), POSTING_GIFT))
            .step("Read the settlement", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(SETTLEMENT),
                ctx -> byId(ctx.contains(NEW_ID) ? (String) ctx.get(NEW_ID) : null), NEW_ROW))
            .compute("Record the posting", (metadata, ctx) -> recordPosting(ctx))
            // F-M12 b: capped two months running, or a fee left for more than a month: finance is asked, once.
            .step("Check for the task", RunTemplate.<ProcessContext>when(ctx -> ctx.contains(ALERT_SOURCE),
                ALERT_TEMPLATE, ctx -> Map.of("sourceKey", ctx.get(ALERT_SOURCE, String.class)), ALERT_ROWS))
            .step("Ask finance", CreateTask.<ProcessContext>when(ctx -> !rows(ctx, ALERT_ROWS).isEmpty(),
                OperatorSettlements::cappedTask)));
    }

    /** Whether the deadline for the steps before the settlement has not passed (so they are checked). */
    static boolean waiting(ProcessContext ctx) {
        return ctx.contains(WAIT_UNTIL) && ctx.opTime().isBefore(ctx.get(WAIT_UNTIL, Instant.class));
    }

    /**
     * Something the settlement waits for that is not done: nothing is settled today (the daily job takes the operator
     * again tomorrow, as the same month: an earlier one is never skipped). Past the deadline nothing is checked.
     */
    static void decideReadiness(ProcessContext ctx) {
        if (!skip(ctx) && !rows(ctx, READINESS).isEmpty()) {
            ctx.put(SKIP, true);
        }
    }

    /**
     * F-M12 b: how many days after the month's end the settlement waits for the steps before it, 0 to 31; not
     * published or not a whole number in range: refused (nothing is assumed).
     */
    static int readinessMaxDays(ProcessContext ctx) {
        String key = QbParams.OPERATOR_SETTLEMENT_READINESS_MAX_DAYS;
        String value = setting(ctx, key);
        BigDecimal days;
        try {
            days = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw invalid(key, value);
        }
        if (days.signum() < 0 || days.compareTo(BigDecimal.valueOf(31)) > 0 || days.stripTrailingZeros().scale() > 0) {
            throw invalid(key, value);
        }
        return days.intValueExact();
    }

    static boolean skip(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.contains(SKIP) ? ctx.get(SKIP) : null);
    }

    @SuppressWarnings("unchecked")
    static List<Object> countedNow(ProcessContext ctx) {
        return ctx.contains(COUNTED_NOW) ? (List<Object>) ctx.get(COUNTED_NOW) : List.of();
    }

    /**
     * Decides whether there is anything to settle (the operator is there and the month is not settled yet, and it has
     * ended) and finds the switch point: the end of the operator's last old settlement, or the epoch for an operator
     * that never had one. The settlement counts only what came after it (F-M12 §3.0).
     */
    static void prepare(ProcessContext ctx, ZoneId zone) {
        RunInput input = runInput(ctx);
        if (first(ctx, OPERATOR) == null || first(ctx, SETTLEMENT_ROW) != null) {
            ctx.put(SKIP, true);
            ctx.put(COVERED_FROM, Instant.MAX);
            return;
        }
        if (!ctx.opTime().isAfter(monthEnd(input.month(), zone))) {
            throw new BusinessRuleViolationException(new Violation("month", OperatorCodes.MONTH_OPEN,
                "The month has not ended", Map.of("month", input.month())));
        }
        EntityInstance lastOld = first(ctx, LAST_OLD);
        Instant coveredFrom = lastOld == null ? Instant.EPOCH : time(lastOld.get("periodEnd"));
        ctx.put(COVERED_FROM, coveredFrom);
        // The settings are all read (and refused if bad) before anything is decided, whatever the deadline.
        ctx.put(TERMS, terms(ctx));
        ctx.put(WAIT_UNTIL, monthEnd(input.month(), zone).atZone(zone).plusDays(readinessMaxDays(ctx)).toInstant());
    }

    /** The ids of the fees that wait (the charges template's rows), for the credit-used read. */
    static List<Object> candidateIds(ProcessContext ctx) {
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> row : rows(ctx, CHARGES)) {
            ids.add(String.valueOf(row.get("chargeId")));
        }
        return ids;
    }

    /**
     * Builds a CHARGE line for each fee to count (every kind, F-M12 §3.2): the part out of the base ({@code excluded})
     * is the cost part or, for a kind outside the list, the whole fee. The line's rates are this month's, or, for a fee
     * a capped settlement left, those of that settlement (it is counted later at the rates of the month it was left in).
     *
     * <p>The cap (§3.9, option B): the settlement's lines are the voids, the credit used on fees counted before and,
     * for each fee counted now, one for the fee and one for each part of the credit used on it. The fees are taken
     * oldest first (chargedAt, charge id) while the next fee with its credit-used lines still fits; the credit used on
     * a fee counted is never cut. Voids go first (they are never cut).
     */
    @SuppressWarnings("unchecked")
    static void countCharges(ProcessContext ctx, int maxLines) {
        ctx.put(DEFERRED, Set.<String>of());
        ctx.put(CHARGE_TERMS, Map.<String, OperatorShareRules.Terms>of());
        ctx.put(CAPPED, false);
        ctx.put(UNCOUNTED, 0);
        if (skip(ctx)) {
            ctx.put(COUNTED_NOW, List.of());
            ctx.put(CHARGE_LINES, List.of());
            ctx.put(CHARGE_BY_ID, Map.of());
            return;
        }
        RunInput input = runInput(ctx);
        OperatorShareRules.Terms terms = ctx.get(TERMS, OperatorShareRules.Terms.class);
        BigDecimal share = planShare(ctx);

        List<Map<String, Object>> waiting = new ArrayList<>(rows(ctx, CHARGES));
        waiting.sort(Comparator.comparing((Map<String, Object> row) -> time(row.get("chargedAt")))
            .thenComparing(row -> String.valueOf(row.get("chargeId"))));
        Set<String> waitingIds = new java.util.HashSet<>();
        for (Map<String, Object> row : waiting) {
            waitingIds.add(String.valueOf(row.get("chargeId")));
        }
        // The credit-used lines each fee brings (one a part of a payment this operator's fee was paid with, not written
        // yet), and those of fees counted before, which no cap decides.
        Map<String, Integer> giftLines = new LinkedHashMap<>();
        int priorGiftLines = 0;
        for (Map<String, Object> row : rows(ctx, GIFTS)) {
            if (input.operatorId().equals(String.valueOf(row.get("chargeOperatorId")))
                && Boolean.TRUE.equals(row.get("counted")) && !Boolean.TRUE.equals(row.get("done"))) {
                String chargeId = String.valueOf(row.get("chargeId"));
                if (waitingIds.contains(chargeId)) {
                    giftLines.merge(chargeId, 1, Integer::sum);
                } else {
                    priorGiftLines++;
                }
            }
        }
        long budget = (long) maxLines - rows(ctx, VOIDS).size() - priorGiftLines;
        List<Map<String, Object>> taken = new ArrayList<>();
        for (Map<String, Object> row : waiting) {
            long cost = 1 + giftLines.getOrDefault(String.valueOf(row.get("chargeId")), 0);
            if (cost > budget) {
                break;
            }
            budget -= cost;
            taken.add(row);
        }
        long all = waiting.isEmpty() ? 0 : ((Number) waiting.getFirst().get("waitingTotal")).longValue();
        int uncounted = (int) (all - taken.size());
        Set<String> deferred = new java.util.HashSet<>();
        for (int i = taken.size(); i < waiting.size(); i++) {
            deferred.add(String.valueOf(waiting.get(i).get("chargeId")));
        }
        if (uncounted > 0) {
            ctx.put(CAPPED, true);
            ctx.put(UNCOUNTED, uncounted);
            ctx.put(DEFERRED, deferred);
            if (!taken.isEmpty()) {
                Map<String, Object> last = taken.getLast();
                ctx.put(WATERMARK, Map.of("at", time(last.get("chargedAt")), "id", String.valueOf(last.get("chargeId"))));
            }
            if (taken.size() < waiting.size()) {
                ctx.put(OLDEST_UNCOUNTED, time(waiting.get(taken.size()).get("chargedAt")));
            }
        }

        List<Object> countedNow = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, OperatorShareRules.Terms> termsOf = new LinkedHashMap<>();
        for (Map<String, Object> row : taken) {
            String chargeId = String.valueOf(row.get("chargeId"));
            String kind = String.valueOf(row.get("kind"));
            BigDecimal amount = money(row.get("amount"));
            // A fee a capped settlement left is counted at that settlement's share and settings.
            boolean left = row.get("originTerms") != null && row.get("originShare") != null;
            OperatorShareRules.Terms feeTerms = left ? OperatorShareRules.Terms.fromText(
                String.valueOf(row.get("originTerms"))) : terms;
            BigDecimal feeShare = left ? money(row.get("originShare")) : share;
            termsOf.put(chargeId, feeTerms);
            BigDecimal excluded = OperatorShareRules.excluded(kind, amount, money(row.get("shareExcluded")), feeTerms);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("operatorId", input.operatorId());
            line.put("sponsorId", row.get("sponsorId"));
            line.put("month", input.month());
            line.put("lineKind", QbSettlementLines.CHARGE);
            line.put("sourceId", chargeId);
            line.put("chargeId", chargeId);
            line.put("feeKind", kind);
            line.put("period", row.get("period"));
            line.put("late", Boolean.TRUE.equals(row.get("late")));
            line.put("occurredAt", time(row.get("chargedAt")));
            line.put("amount", amount);
            line.put("excluded", excluded);
            line.put("giftGross", BigDecimal.ZERO);
            line.put("inBase", BigDecimal.ZERO);
            line.put("recoverGross", BigDecimal.ZERO);
            line.put("noShare", false);
            line.put("shareRate", feeShare);
            line.put("recoveryRate", feeTerms.recoveryRate());
            line.put("recoverBase", OperatorShareRules.recoverBase(kind, amount, money(row.get("shareExcluded")),
                feeTerms));
            lines.add(line);
            byId.put(chargeId, line);
            countedNow.add(chargeId);
        }
        ctx.put(COUNTED_NOW, countedNow);
        ctx.put(CHARGE_LINES, lines);
        ctx.put(CHARGE_BY_ID, byId);
        ctx.put(CHARGE_TERMS, termsOf);
    }

    @SuppressWarnings("unchecked")
    static void settle(ProcessContext ctx, ZoneId zone) {
        if (skip(ctx)) {
            return;
        }
        RunInput input = runInput(ctx);
        String plan = OperatorPlans.planOf(instances(ctx, PLANS), input.month());
        BigDecimal share = planShare(ctx);
        OperatorShareRules.Terms terms = terms(ctx);
        BigDecimal fixedFee = QbOperators.ACTIVE.equals(first(ctx, OPERATOR).get("status")) ? planTerms(ctx).fixedFee()
            : BigDecimal.ZERO;
        BigDecimal cost = prospectingCost(ctx);
        if (cost == null) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NO_RATE,
                "The yen of a dollar the prospecting cost is deducted at is not set", Map.of()));
        }
        EntityInstance previous = first(ctx, PREVIOUS);
        BigDecimal carriedIn = previous == null ? BigDecimal.ZERO : money(previous.get("carriedOut"));
        BigDecimal giftCarriedIn = previous == null ? BigDecimal.ZERO : money(previous.get("giftCarriedOut"));

        List<Map<String, Object>> chargeLines = (List<Map<String, Object>>) ctx.get(CHARGE_LINES);
        Map<String, Map<String, Object>> chargeById = (Map<String, Map<String, Object>>) ctx.get(CHARGE_BY_ID);
        // The base: the part of the counted fees in it, less the part the credit used this batch (borne by the
        // platform) stands for.
        BigDecimal platformFees = BigDecimal.ZERO;
        BigDecimal costExcluded = BigDecimal.ZERO;
        // By the share the platform keeps: the fees a capped settlement left keep the share of that month.
        Map<BigDecimal, BigDecimal> baseByShare = new java.util.TreeMap<>();
        for (Map<String, Object> line : chargeLines) {
            platformFees = platformFees.add(money(line.get("amount")));
            costExcluded = costExcluded.add(money(line.get("excluded")));
            baseByShare.merge(money(line.get("shareRate")), money(line.get("amount")).subtract(
                money(line.get("excluded"))), BigDecimal::add);
        }

        // The voids of fees counted before: a signed cost, not in the base (M1).
        BigDecimal voidExact = BigDecimal.ZERO;
        List<Map<String, Object>> voidLines = new ArrayList<>();
        for (Map<String, Object> row : rows(ctx, VOIDS)) {
            String chargeId = String.valueOf(row.get("chargeId"));
            BigDecimal amount = money(row.get("amount"));
            BigDecimal excluded = money(row.get("excluded"));
            BigDecimal rate = money(row.get("shareRate"));
            voidExact = voidExact.add(OperatorShareRules.exactShare(amount.subtract(excluded), rate));
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("operatorId", input.operatorId());
            line.put("sponsorId", row.get("sponsorId"));
            line.put("month", input.month());
            line.put("lineKind", QbSettlementLines.VOID);
            line.put("sourceId", chargeId);
            line.put("chargeId", chargeId);
            line.put("feeKind", row.get("kind"));
            line.put("period", row.get("period"));
            line.put("late", String.valueOf(row.get("period")).compareTo(input.month()) < 0);
            line.put("occurredAt", time(row.get("chargedAt")));
            line.put("amount", amount.negate());
            line.put("excluded", excluded.negate());
            line.put("giftGross", BigDecimal.ZERO);
            line.put("inBase", BigDecimal.ZERO);
            line.put("recoverGross", BigDecimal.ZERO);
            line.put("noShare", false);
            line.put("recoverBase", BigDecimal.ZERO);
            line.put("shareRate", rate);
            line.put("recoveryRate", money(row.get("recoveryRate")));
            voidLines.add(line);
        }
        BigDecimal voidDeduction = OperatorShareRules.floor(voidExact);

        // The credit used, cut into the fees it paid, row by row (F-M12 §3.3).
        GiftResult gift = gifts(ctx, terms, chargeById, countedNowSet(ctx), deferredSet(ctx), chargeTerms(ctx), input);

        // The base: ⌊net base × (1 − platform share)⌋, net of the same-batch credit the platform bears.
        // Each share's fees on their own (one share unless a capped settlement's fees are counted now), the sums of
        // what is rounded down being added.
        BigDecimal operatorShare = BigDecimal.ZERO;
        BigDecimal negativeBase = BigDecimal.ZERO;
        Set<BigDecimal> shares = new java.util.TreeSet<>(baseByShare.keySet());
        shares.addAll(gift.sameBatchNoShareInBase.keySet());
        for (BigDecimal groupShare : shares) {
            BigDecimal netBase = baseByShare.getOrDefault(groupShare, BigDecimal.ZERO)
                .subtract(gift.sameBatchNoShareInBase.getOrDefault(groupShare, BigDecimal.ZERO));
            operatorShare = operatorShare.add(operatorShare(netBase, groupShare));
            // A net base below 0 (more of the credit the platform bears than fees, same batch): the operator's share
            // of the negative part is a cost (F-M12 review M1: positive).
            negativeBase = negativeBase.add(OperatorShareRules.negativeBaseCost(netBase, groupShare));
        }
        if (shares.isEmpty()) {
            operatorShare = operatorShare(BigDecimal.ZERO, share);
        }

        // F-M7 (C3) as F-M12 §3.5 has it: a note takes back only the part of its fee that entered the base, at the
        // rate of that fee's CHARGE line (a fee without one: as the fee is counted now).
        BigDecimal creditNotes = BigDecimal.ZERO;
        BigDecimal noteExact = BigDecimal.ZERO;
        for (Map<String, Object> row : rows(ctx, CREDIT_NOTES)) {
            BigDecimal amount = money(row.get("amount"));
            creditNotes = creditNotes.add(amount);
            BigDecimal feeAmount;
            BigDecimal excluded;
            BigDecimal rate;
            if (row.get("lineAmount") != null) {
                feeAmount = money(row.get("lineAmount"));
                excluded = money(row.get("lineExcluded"));
                rate = money(row.get("lineShareRate"));
            } else {
                // A fee not counted yet: at the rates it will be counted at, so that the note and the fee agree. A fee
                // a capped settlement left is counted at that month's (F-M12 b, option b of the review L5).
                boolean left = row.get("originTerms") != null && row.get("originShare") != null;
                feeAmount = money(row.get("chargeAmount"));
                excluded = OperatorShareRules.excluded(String.valueOf(row.get("feeKind")), feeAmount,
                    money(row.get("chargeShareExcluded")), left ? OperatorShareRules.Terms.fromText(
                        String.valueOf(row.get("originTerms"))) : terms);
                rate = left ? money(row.get("originShare")) : share;
            }
            noteExact = noteExact.add(OperatorShareRules.exactShare(OperatorShareRules.noteInBase(amount, feeAmount,
                excluded), rate));
        }
        BigDecimal creditNoteDeduction = OperatorShareRules.floor(noteExact);

        BigDecimal cost0 = cost.add(fixedFee).add(creditNoteDeduction).add(gift.giftShareDeduction)
            .add(voidDeduction).add(negativeBase);
        BigDecimal[] payable = payable(operatorShare, cost0, carriedIn);
        // What is left of the share pays the credit taken back; the rest is carried (its own column). A refund first
        // pays the cost carried, then what is left is payable.
        OperatorShareRules.Recovery recovery = OperatorShareRules.recover(gift.giftDue, giftCarriedIn, payable[0],
            payable[1]);
        BigDecimal finalPayable = payable[0].subtract(recovery.recovered()).subtract(recovery.costOffset());
        BigDecimal costCarriedOut = payable[1].subtract(recovery.costOffset());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operatorId", input.operatorId());
        values.put("month", input.month());
        values.put("periodStart", monthStart(input.month(), zone));
        values.put("periodEnd", monthEnd(input.month(), zone));
        values.put("revenue", BigDecimal.ZERO);
        values.put("platformFees", platformFees);
        values.put("platformShare", share);
        values.put("operatorShare", operatorShare);
        values.put("prospectingCost", cost);
        values.put("plan", plan);
        values.put("fixedFee", fixedFee);
        values.put("creditNotes", creditNotes);
        values.put("creditNoteDeduction", creditNoteDeduction);
        values.put("carriedIn", carriedIn);
        values.put("payable", finalPayable);
        values.put("carriedOut", costCarriedOut);
        values.put("coveredFrom", ctx.get(COVERED_FROM));
        values.put("giftPaid", gift.giftPaid);
        values.put("giftShareDeduction", gift.giftShareDeduction);
        values.put("voidDeduction", voidDeduction);
        values.put("costExcluded", costExcluded);
        values.put("giftUsed", gift.giftUsed);
        values.put("giftDue", gift.giftDue);
        values.put("giftCarriedIn", giftCarriedIn);
        values.put("giftRecovered", recovery.recovered());
        values.put("giftCarriedOut", recovery.carriedOut());
        values.put("costOffset", recovery.costOffset());
        values.put("capped", Boolean.TRUE.equals(ctx.get(CAPPED)));
        values.put("uncounted", BigDecimal.valueOf(((Number) ctx.get(UNCOUNTED)).longValue()));
        if (ctx.contains(WATERMARK)) {
            Map<String, Object> mark = (Map<String, Object>) ctx.get(WATERMARK);
            values.put("watermarkChargedAt", mark.get("at"));
            values.put("watermarkChargeId", mark.get("id"));
        }
        if (Boolean.TRUE.equals(ctx.get(CAPPED))) {
            // The fees left are counted later at these settings (and at platformShare).
            values.put("cappedTerms", terms.toText());
        }
        values.put("status", CREDITED);
        values.put("issuedAt", ctx.opTime());
        values.put("revision", BigDecimal.ONE);
        String id = String.valueOf(ctx.changes().insert(SETTLEMENT, values));
        ctx.put(NEW_ID, id);
        if (Boolean.TRUE.equals(ctx.get(CAPPED))) {
            alertIfCapped(ctx, zone, previous, input);
        }

        for (Map<String, Object> line : chargeLines) {
            insertLine(ctx, id, line);
        }
        for (Map<String, Object> line : voidLines) {
            insertLine(ctx, id, line);
        }
        for (Map<String, Object> line : gift.lines) {
            insertLine(ctx, id, line);
        }

        sharePosting(ctx, id, operatorShare, payable[0]);
        giftPosting(ctx, id, recovery.recovered(), recovery.costOffset());
    }

    private static void insertLine(ProcessContext ctx, String settlementId, Map<String, Object> line) {
        Map<String, Object> values = new LinkedHashMap<>(line);
        values.put("settlementId", settlementId);
        ctx.changes().insert(QbSettlementLines.LINE, values);
    }

    /** The share posting (F-M12 §4): debit 5130, credit 2140 what is payable before gift recovery, 4110 the rest. */
    private static void sharePosting(ProcessContext ctx, String id, BigDecimal operatorShare, BigDecimal payable) {
        if (operatorShare.signum() == 0 && payable.signum() == 0) {
            return;
        }
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        if (operatorShare.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES, Direction.DEBIT, operatorShare));
        }
        if (payable.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES_PAYABLE, Direction.CREDIT, payable));
        }
        BigDecimal recovered = operatorShare.subtract(payable);
        if (recovered.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.SERVICE_REVENUE, Direction.CREDIT, recovered));
        } else if (recovered.signum() < 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.SERVICE_REVENUE, Direction.DEBIT, recovered.negate()));
        }
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), ISSUE_DESCRIPTION,
            reference(runInput(ctx).operatorId(), runInput(ctx).month()), List.copyOf(lines), SETTLEMENT, id));
    }

    /**
     * The gift recovery posting (F-M12 §4): debit 2140 / credit 5140 what is taken back; when given back, the reverse:
     * debit 5140, credit 2140 what is paid out, and credit 4110 what paid the cost carried instead (it is recovered as
     * a share would have recovered it).
     */
    private static void giftPosting(ProcessContext ctx, String id, BigDecimal recovered, BigDecimal costOffset) {
        if (recovered.signum() == 0) {
            return;
        }
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        BigDecimal magnitude = recovered.abs();
        if (recovered.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES_PAYABLE, Direction.DEBIT, magnitude));
            lines.add(new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.CREDIT, magnitude));
        } else {
            lines.add(new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.DEBIT, magnitude));
            BigDecimal paidOut = magnitude.subtract(costOffset);
            if (paidOut.signum() > 0) {
                lines.add(new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES_PAYABLE, Direction.CREDIT, paidOut));
            }
            if (costOffset.signum() > 0) {
                lines.add(new LedgerProcesses.Line(QbLedger.SERVICE_REVENUE, Direction.CREDIT, costOffset));
            }
        }
        ctx.put(POST_INPUT_GIFT, new LedgerProcesses.PostInput(ctx.opTime(), GIFT_DESCRIPTION,
            giftReference(runInput(ctx).operatorId(), runInput(ctx).month()), List.copyOf(lines), SETTLEMENT, id));
    }

    /** The outcome of cutting the credit used into the fees it paid: the lines and the month's totals. */
    private record GiftResult(List<Map<String, Object>> lines, BigDecimal giftPaid,
        Map<BigDecimal, BigDecimal> sameBatchNoShareInBase,
        BigDecimal giftShareDeduction, BigDecimal giftUsed, BigDecimal giftDue) {}

    /** One contribution of the credit used to a fee, before the cumulative rounding. */
    private record Contribution(String chargeId, String paymentId, Instant paidAt, BigDecimal gross,
        String grantSource, String grantId, Boolean priorNoShare) {}

    @SuppressWarnings("unchecked")
    private static GiftResult gifts(ProcessContext ctx, OperatorShareRules.Terms terms,
        Map<String, Map<String, Object>> chargeById, Set<String> countedNow, Set<String> deferred,
        Map<String, OperatorShareRules.Terms> chargeTerms, RunInput input) {
        List<Map<String, Object>> rows = rows(ctx, GIFTS);
        // A payment's part is cut over ALL the fees it paid (an invoice's fees, of every operator and counted or not, by
        // their totals, the yen left by the largest remainder), and only then are this operator's counted fees that
        // have no GIFT line yet kept: cutting what is left would give them what other fees were paid with.
        Map<String, List<Map<String, Object>>> payments = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            payments.computeIfAbsent(String.valueOf(row.get("paymentId")), k -> new ArrayList<>()).add(row);
        }
        List<Contribution> contributions = new ArrayList<>();
        for (List<Map<String, Object>> group : payments.values()) {
            group.sort(Comparator.comparing(r -> String.valueOf(r.get("chargeId"))));
            List<BigDecimal> weights = group.stream().map(r -> money(r.get("chargeTotal"))).toList();
            List<String> order = group.stream().map(r -> String.valueOf(r.get("chargeId"))).toList();
            List<BigDecimal> parts = OperatorShareRules.split(money(group.getFirst().get("paymentAmount")), weights,
                order);
            for (int i = 0; i < group.size(); i++) {
                Map<String, Object> row = group.get(i);
                if (input.operatorId().equals(String.valueOf(row.get("chargeOperatorId")))
                    && Boolean.TRUE.equals(row.get("counted")) && !Boolean.TRUE.equals(row.get("done"))
                    && !deferred.contains(String.valueOf(row.get("chargeId")))) {
                    contributions.add(contribution(row, parts.get(i)));
                }
            }
        }
        // Each fee's contributions, in the order they were paid, fed to the cumulative rounding seeded with its prior
        // gift rows.
        Map<String, List<Contribution>> byCharge = new LinkedHashMap<>();
        Map<String, Map<String, Object>> rowByCharge = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            rowByCharge.putIfAbsent(String.valueOf(row.get("chargeId")), row);
        }
        for (Contribution c : contributions) {
            byCharge.computeIfAbsent(c.chargeId(), k -> new ArrayList<>()).add(c);
        }
        Map<String, Boolean> treatedAs = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal giftPaid = BigDecimal.ZERO;
        Map<BigDecimal, BigDecimal> sameBatchNoShareInBase = new java.util.TreeMap<>();
        BigDecimal crossNoShareExact = BigDecimal.ZERO;
        BigDecimal giftUsed = BigDecimal.ZERO;
        BigDecimal dueExact = BigDecimal.ZERO;
        for (Map.Entry<String, List<Contribution>> entry : byCharge.entrySet()) {
            String chargeId = entry.getKey();
            Map<String, Object> row = rowByCharge.get(chargeId);
            boolean sameBatch = countedNow.contains(chargeId);
            Map<String, Object> charge = chargeById.get(chargeId);
            BigDecimal amount = money(row.get("chargeAmount"));
            BigDecimal total = money(row.get("chargeTotal"));
            BigDecimal excluded = sameBatch ? money(charge.get("excluded")) : money(row.get("lineExcluded"));
            BigDecimal inBaseAmount = amount.subtract(excluded);
            // Decided when the fee was counted (its CHARGE line), whatever the settings are now.
            BigDecimal recoverBase = sameBatch ? money(charge.get("recoverBase")) : money(row.get("lineRecoverBase"));
            BigDecimal shareRate = sameBatch ? money(charge.get("shareRate")) : money(row.get("lineShareRate"));
            BigDecimal recoveryRate = sameBatch ? money(charge.get("recoveryRate"))
                : money(row.get("lineRecoveryRate"));
            OperatorShareRules.ChargeGifts cumulative = new OperatorShareRules.ChargeGifts(amount, total, inBaseAmount,
                recoverBase, money(row.get("priorGross")), money(row.get("priorOperatorGross")),
                money(row.get("priorAmount")), money(row.get("priorInBase")), money(row.get("priorRecover")));
            List<Contribution> list = entry.getValue();
            list.sort(Comparator.comparing(Contribution::paidAt).thenComparing(Contribution::paymentId));
            for (Contribution c : list) {
                // A grant's credit on a fee is treated as the first of it was (a refund reverses exactly), whatever
                // the settings are now; the first of it takes the settings of today.
                String key = chargeId + "|" + c.grantId();
                if (!treatedAs.containsKey(key)) {
                    treatedAs.put(key, c.priorNoShare() != null ? c.priorNoShare()
                        : !OperatorShareRules.OPERATOR.equals(chargeTerms.getOrDefault(chargeId, terms)
                            .treatmentOf(c.grantSource())));
                }
                boolean noShare = treatedAs.get(key);
                boolean operatorBears = !noShare;
                OperatorShareRules.Gift value = cumulative.next(c.gross(), operatorBears);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("operatorId", input.operatorId());
                line.put("sponsorId", row.get("sponsorId"));
                line.put("month", input.month());
                line.put("lineKind", QbSettlementLines.GIFT);
                line.put("sourceId", c.paymentId());
                line.put("chargeId", chargeId);
                line.put("feeKind", row.get("feeKind"));
                line.put("period", row.get("period"));
                line.put("late", false);
                line.put("occurredAt", c.paidAt());
                line.put("amount", value.amount());
                line.put("excluded", BigDecimal.ZERO);
                line.put("giftGross", c.gross());
                line.put("grantSource", c.grantSource());
                line.put("grantId", c.grantId());
                line.put("inBase", value.inBase());
                line.put("recoverGross", value.recoverGross());
                line.put("noShare", noShare);
                line.put("recoverBase", BigDecimal.ZERO);
                line.put("shareRate", shareRate);
                line.put("recoveryRate", recoveryRate);
                lines.add(line);
                giftUsed = giftUsed.add(value.recoverGross());
                dueExact = dueExact.add(value.recoverGross().multiply(recoveryRate));
                if (noShare) {
                    if (sameBatch) {
                        sameBatchNoShareInBase.merge(shareRate, value.inBase(), BigDecimal::add);
                        giftPaid = giftPaid.add(value.amount());
                    } else {
                        crossNoShareExact = crossNoShareExact.add(OperatorShareRules.exactShare(value.inBase(),
                            shareRate));
                    }
                }
            }
        }
        return new GiftResult(lines, giftPaid, sameBatchNoShareInBase, OperatorShareRules.floor(crossNoShareExact),
            giftUsed, OperatorShareRules.due(dueExact));
    }

    private static Contribution contribution(Map<String, Object> row, BigDecimal gross) {
        return new Contribution(String.valueOf(row.get("chargeId")), String.valueOf(row.get("paymentId")),
            time(row.get("paidAt")), gross, row.get("grantSource") == null ? null
                : String.valueOf(row.get("grantSource")), row.get("grantId") == null ? null
                : String.valueOf(row.get("grantId")), row.get("priorNoShare") == null ? null
                : Boolean.valueOf(String.valueOf(row.get("priorNoShare"))));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> deferredSet(ProcessContext ctx) {
        return (Set<String>) ctx.get(DEFERRED);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, OperatorShareRules.Terms> chargeTerms(ProcessContext ctx) {
        return (Map<String, OperatorShareRules.Terms>) ctx.get(CHARGE_TERMS);
    }

    /**
     * Capped two months running, or a fee left for more than a month: finance is asked (the task is made after the
     * settlement is written; its source key names the operator and the month, and is looked up first).
     */
    private static void alertIfCapped(ProcessContext ctx, ZoneId zone, EntityInstance previous, RunInput input) {
        boolean again = previous != null && Boolean.TRUE.equals(previous.get("capped"));
        Instant oldest = ctx.contains(OLDEST_UNCOUNTED) ? ctx.get(OLDEST_UNCOUNTED, Instant.class) : null;
        boolean old = oldest != null && oldest.isBefore(ctx.opTime().atZone(zone).minusMonths(1).toInstant());
        if (again || old) {
            ctx.put(ALERT_SOURCE, CAPPED_TASK + ":" + input.operatorId() + ":" + input.month());
        }
    }

    /** F-M12 b: finance's task: the settlement left fees for the next one, and how many. */
    static com.jabiz.runtime.task.TaskSpec cappedTask(ProcessContext ctx) {
        RunInput input = runInput(ctx);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operator", input.operatorId());
        params.put("month", input.month());
        params.put("uncounted", String.valueOf(ctx.get(UNCOUNTED)));
        return com.jabiz.runtime.task.TaskSpec.forPermission(CAPPED_TASK, CAPPED_TASK_TITLE, params,
                QbPermissions.RECONCILE)
            .about(SETTLEMENT, String.valueOf(ctx.get(NEW_ID))).source(ctx.get(ALERT_SOURCE, String.class));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> countedNowSet(ProcessContext ctx) {
        List<Object> ids = ctx.contains(COUNTED_NOW) ? (List<Object>) ctx.get(COUNTED_NOW) : List.of();
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    /** The plan's terms in effect that month (Q48e-2); the plan must be set. */
    static OperatorPlans.Terms planTerms(ProcessContext ctx) {
        String plan = OperatorPlans.planOf(instances(ctx, PLANS), runInput(ctx).month());
        OperatorPlans.Terms terms = OperatorPlans.terms(plan, params(ctx));
        if (terms == null) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NO_PLAN,
                "The terms of the operator's plan are not set", Map.of("plan", plan)));
        }
        return terms;
    }

    static BigDecimal planShare(ProcessContext ctx) {
        return planTerms(ctx).platformShare();
    }

    /** A share setting that is not published (or empty) refuses the settlement: no default is assumed. */
    private static String setting(ProcessContext ctx, String key) {
        Object value = param(ctx, key);
        if (value == null || value.toString().isBlank()) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NO_SHARE_SETTING,
                "A setting of the operators' share is not published", Map.of("param", key)));
        }
        return value.toString().strip();
    }

    private static BusinessRuleViolationException invalid(String key, String value) {
        return new BusinessRuleViolationException(new Violation(null, OperatorCodes.SHARE_SETTING_INVALID,
            "A setting of the operators' share is not valid", Map.of("param", key, "value", value)));
    }

    private static boolean flag(ProcessContext ctx, String key) {
        String value = setting(ctx, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw invalid(key, value);
        }
        return Boolean.parseBoolean(value);
    }

    /** F-M12 §5: the fraction of the credit used taken back, 0 to 1; not published or not a fraction: refused. */
    static BigDecimal recoveryRate(ProcessContext ctx) {
        String value = setting(ctx, QbParams.OPERATOR_CREDIT_RECOVERY_RATE);
        BigDecimal rate;
        try {
            rate = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw invalid(QbParams.OPERATOR_CREDIT_RECOVERY_RATE, value);
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw invalid(QbParams.OPERATOR_CREDIT_RECOVERY_RATE, value);
        }
        return rate;
    }

    /** F-M12 §5: the terms the settlement reads of the share parameters; none is assumed, a bad one refuses. */
    static OperatorShareRules.Terms terms(ProcessContext ctx) {
        String kindsText = setting(ctx, QbParams.OPERATOR_SHARE_KINDS);
        if (!OperatorShareRules.validKinds(kindsText)) {
            throw invalid(QbParams.OPERATOR_SHARE_KINDS, kindsText);
        }
        Set<String> kinds = OperatorShareRules.parseKinds(kindsText);
        boolean aiCost = flag(ctx, QbParams.OPERATOR_SHARE_AI_COST_IN_BASE);
        boolean recoverOutside = flag(ctx, QbParams.OPERATOR_GIFT_RECOVER_OUTSIDE_BASE);
        Map<String, String> treatments = new LinkedHashMap<>();
        for (String source : new String[] {"ONBOARDING", "LIGHT", "MANUAL", "CERTIFIED_TOP_UP"}) {
            String key = QbParams.operatorGiftTreatment(source);
            String value = setting(ctx, key);
            if (!OperatorShareRules.treatment(value)) {
                throw invalid(key, value);
            }
            treatments.put(source, value);
        }
        return new OperatorShareRules.Terms(kinds, aiCost, recoverOutside, recoveryRate(ctx), treatments);
    }

    /** F-M12 §4: the gift recovery posting's reference: {@code opg_<month>_<operator's first eight>}. */
    static String giftReference(String operatorId, String month) {
        return "opg_" + month + "_" + operatorId.substring(0, Math.min(8, operatorId.length()));
    }

    /** An instant from a stored temporal value. */
    static Instant time(Object value) {
        if (value == null || value instanceof Instant && value.equals(Instant.MAX)) {
            return Instant.EPOCH;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.time.OffsetDateTime time) {
            return time.toInstant();
        }
        return Instant.parse(value.toString());
    }

    /** The ledger's reference of a settlement's postings: {@code op_<month>_<operator's first eight>}. */
    static String reference(String operatorId, String month) {
        return "op_" + month + "_" + operatorId.substring(0, Math.min(8, operatorId.length()));
    }

    static void recordPosting(ProcessContext ctx) {
        EntityInstance row = first(ctx, NEW_ROW);
        if (row != null && ctx.contains(POSTING)) {
            ctx.changes().update(SETTLEMENT, row.id(), row.version(), Map.of("issueTransactionId",
                ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId()));
        }
    }

    // ---- the statement -------------------------------------------------------------------------------------------------

    /** @param settlementId the settlement whose statement is issued, or given again */
    public record StatementInput(@NotBlank @Size(max = 36) String settlementId) {}

    /**
     * {@value #STATEMENT} (finance): issues the settlement's statement ({@value #LAYOUT_ID}, kept exactly as issued) the
     * first time, and gives the same one after. A person issues it: the platform issues a document only for a caller
     * holding its layout's permissions, and the monthly job runs as the system, which holds none.
     */
    public static ProcessDefinition<StatementInput, SettlementOutput, ProcessContext> statement() {
        return ProcessDefinition.define(STATEMENT, 1, StatementInput.class, SettlementOutput.class,
            ProcessContext.class, pb -> pb
                .description("Issues an operator settlement's statement, or gives the one already issued.")
                .permissions(QbPermissions.OPERATOR_SETTLEMENT_PAY, QbPermissions.ADMIN_FINANCE_READ)
                .requiresVerifiedEmail()
                .actsOn(SETTLEMENT, "settlementId")
                .contextFactory(OperatorSettlements::context)
                .outputMapper(OperatorSettlements::output)
                .step("Find the settlement", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(SETTLEMENT),
                    ctx -> byId(ctx.get(INPUT, StatementInput.class).settlementId()), FOUND))
                .step("Lock the operator", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null
                    ? null : QbOperators.operatorLockName(first(ctx, FOUND).get("operatorId"))))
                .step("Read it again", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(SETTLEMENT),
                    ctx -> byId(first(ctx, FOUND) == null ? null : String.valueOf(first(ctx, FOUND).id())),
                    SETTLEMENT_ROW))
                .compute("Check it", (metadata, ctx) -> {
                    if (first(ctx, SETTLEMENT_ROW) == null) {
                        throw new EntityNotFoundException(SETTLEMENT + " "
                            + ctx.get(INPUT, StatementInput.class).settlementId() + " not found");
                    }
                })
                .step("Load the operator", QueryEntities.<ProcessContext>of(
                    QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("operatorId",
                            List.of(String.valueOf((Object) first(ctx, SETTLEMENT_ROW).get("operatorId")))))
                        .limit(1).build(), OPERATOR))
                .step("Issue the statement", CallProcess.<ProcessContext>when(
                    ctx -> first(ctx, SETTLEMENT_ROW).get("documentRunId") == null,
                    DocumentProcesses.ISSUE, 1, ctx -> new DocumentProcesses.IssueInput(LAYOUT_ID,
                        Map.of("settlementId", String.valueOf(first(ctx, SETTLEMENT_ROW).id())), null, null,
                        statementLanguage(first(ctx, OPERATOR))),
                    ISSUED_DOCUMENT))
                .compute("Record the statement", (metadata, ctx) -> {
                    if (!ctx.contains(ISSUED_DOCUMENT)) {
                        return;
                    }
                    EntityInstance row = first(ctx, SETTLEMENT_ROW);
                    DocumentProcesses.IssueOutput issued = ctx.get(ISSUED_DOCUMENT, DocumentProcesses.IssueOutput.class);
                    ctx.changes().update(SETTLEMENT, row.id(), row.version(), Map.of("documentNo", issued.documentNo(),
                        "documentRunId", issued.runId()));
                }));
    }

    /**
     * The month's prospecting cost in yen (Q48d, §6 item 4): its dollars ({@code QbProspectUsage}, both months by
     * Japan's calendar) at {@code qb.prospect.usd-jpy}, rounded up to the yen; 0 without usage, null when there is
     * usage and no rate.
     */
    static BigDecimal prospectingCost(ProcessContext ctx) {
        EntityInstance usage = first(ctx, USAGE);
        BigDecimal dollars = usage == null ? BigDecimal.ZERO : money(usage.get("costUsd"));
        if (dollars.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Object rate = param(ctx, QbParams.PROSPECT_USD_JPY);
        if (rate == null || rate.toString().isBlank() || new BigDecimal(rate.toString()).signum() <= 0) {
            return null;
        }
        return dollars.multiply(new BigDecimal(rate.toString())).setScale(0, RoundingMode.CEILING);
    }

    private static Object param(ProcessContext ctx, String key) {
        Object found = ctx.contains(PARAMS) ? ctx.get(PARAMS) : null;
        if (found instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof EntityInstance param && key.equals(param.get(ParamEntities.KEY))) {
                    return param.get(ParamEntities.VALUE);
                }
            }
        }
        return null;
    }

    /** The parameters a month's settlement reads: the plans' terms, the dollar's yen and F-M12's share settings. */
    static List<Object> paramKeys() {
        List<Object> keys = new ArrayList<>(OperatorPlans.paramKeys());
        keys.add(QbParams.PROSPECT_USD_JPY);
        keys.addAll(QbParams.OPERATOR_SHARE);
        keys.addAll(QbParams.OPERATOR_SETTLEMENT);
        return keys;
    }

    /** The parameters read, by key. */
    private static Map<String, Object> params(ProcessContext ctx) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object found = ctx.contains(PARAMS) ? ctx.get(PARAMS) : null;
        if (found instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof EntityInstance param) {
                    values.put(String.valueOf((Object) param.get(ParamEntities.KEY)), param.get(ParamEntities.VALUE));
                }
            }
        }
        return values;
    }

    // ---- the payment ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PayInput, SettlementOutput, ProcessContext> pay() {
        return ProcessDefinition.define(PAY, 1, PayInput.class, SettlementOutput.class, ProcessContext.class, pb -> pb
            .description("Records the bank payment of an operator's settlement, for another administrator's "
                + "approval; nothing is posted before it.")
            .permissions(QbPermissions.OPERATOR_SETTLEMENT_PAY)
            .requiresVerifiedEmail()
            .contextFactory(OperatorSettlements::context)
            .outputMapper(OperatorSettlements::output)
            .actsOn(SETTLEMENT, "settlementId")
            .step("Find the settlement", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(SETTLEMENT),
                ctx -> byId(payInput(ctx).settlementId()), FOUND))
            .step("Lock the operator", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null ? null
                : QbOperators.operatorLockName(first(ctx, FOUND).get("operatorId"))))
            .step("Read the settlement again, and the review rule", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbOperators.defaultDataset(SETTLEMENT), ctx -> byId(
                    first(ctx, FOUND) == null ? null : payInput(ctx).settlementId()), SETTLEMENT_ROW),
                QueryEntities.query(ApprovalEntities.RULE_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("subject", APPROVAL_SUBJECT),
                        new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES)))
            .compute("Record the payment", (metadata, ctx) -> recordPayment(ctx))
            // Written first, so that the approval binds what is stored (read back, as its result reads it).
            .step("Save the payment", SaveChanges.now())
            .step("Read it back", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(SETTLEMENT),
                ctx -> byId(payInput(ctx).settlementId()), NEW_ROW))
            .step("Ask for another administrator's approval", RequireApproval.<ProcessContext>of(APPROVAL_SUBJECT,
                ctx -> approvalCase(first(ctx, NEW_ROW)), APPROVAL))
            .compute("Wait for the approval", (metadata, ctx) -> waitForApproval(ctx)));
    }

    static void recordPayment(ProcessContext ctx) {
        EntityInstance settlement = first(ctx, SETTLEMENT_ROW);
        if (settlement == null) {
            throw new EntityNotFoundException(SETTLEMENT + " " + payInput(ctx).settlementId() + " not found");
        }
        PayInput input = payInput(ctx);
        List<Violation> problems = new ArrayList<>();
        if (!ISSUED.equals(settlement.get("status"))) {
            problems.add(new Violation(null, OperatorCodes.SETTLEMENT_STATE, "Only an issued settlement is paid",
                Map.of("status", String.valueOf((Object) settlement.get("status")))));
        } else if (money(settlement.get("payable")).signum() <= 0) {
            problems.add(new Violation(null, OperatorCodes.SETTLEMENT_NOTHING, "Nothing is payable", Map.of()));
        }
        Violation future = BankTransfers.futureProblem("paidOn", input.paidOn(), ctx.opTime());
        if (future != null) {
            problems.add(future);
        }
        if (first(ctx, RULES) == null) {
            problems.add(noReview());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paidOn", input.paidOn());
        values.put("bankReference", BankTransfers.reference(input.bankReference()));
        values.put("paymentRecordedBy", ctx.request().actorId());
        values.put("paymentRecordedAt", ctx.opTime());
        values.put("revision", money(settlement.get("revision")).add(BigDecimal.ONE));
        ctx.changes().update(SETTLEMENT, settlement.id(), settlement.version(), values);
    }

    private static Violation noReview() {
        return new Violation(null, OperatorCodes.SETTLEMENT_NO_REVIEW, "No approval rule asks for a second person on "
            + "the operators' payments", Map.of());
    }

    static void waitForApproval(ProcessContext ctx) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
            // A rule whose condition no longer applies: never paid by one person. Nothing is kept.
            throw new BusinessRuleViolationException(List.of(noReview()));
        }
        EntityInstance settlement = first(ctx, NEW_ROW);
        ctx.put(STATUS_AFTER, PAYING);
        ctx.changes().update(SETTLEMENT, settlement.id(), settlement.version(), Map.of("status", PAYING,
            "approvalRequestId", approval.requestId()));
    }

    /**
     * The case: the rule's facts and the content approved (whose settlement, which month, how much, the statement's
     * day and reference), from the stored record. The recorder is the preparer: the platform refuses their approval.
     */
    static ApprovalCase approvalCase(EntityInstance settlement) {
        BigDecimal payable = money(settlement.get("payable")).setScale(0);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("operatorPayment", true);
        facts.put("amount", payable);
        facts.put("operatorId", settlement.get("operatorId"));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("settlementId", String.valueOf(settlement.id()));
        content.put("operatorId", settlement.get("operatorId"));
        content.put("month", settlement.get("month"));
        content.put("payable", payable);
        Object paidOn = settlement.get("paidOn");
        content.put("paidOn", paidOn == null ? null : paidOn.toString());
        content.put("bankReference", settlement.get("bankReference"));
        return ApprovalCase.of(String.valueOf(settlement.id()), facts, content)
            .preparedBy(settlement.get("paymentRecordedBy"))
            .reference(settlement.get("month") + " " + settlement.get("operatorId"));
    }

    // ---- the approval --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TransferProcesses.ApprovalResultInput, SettlementOutput, ProcessContext>
        approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, TransferProcesses.ApprovalResultInput.class,
            SettlementOutput.class, ProcessContext.class, pb -> pb
                .description("Posts an operator's payment once another administrator approved it, or returns it to "
                    + "issued when they rejected it.")
                .permissions(QbPermissions.OPERATOR_SETTLEMENT_SYSTEM)
                .internal()
                .contextFactory(OperatorSettlements::context)
                .outputMapper(OperatorSettlements::output)
                .step("Find the settlement", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(SETTLEMENT),
                    ctx -> byId(APPROVAL_SUBJECT.equals(resultInput(ctx).subject()) ? resultInput(ctx).entityId()
                        : null), FOUND))
                .step("Lock the operator", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null
                    ? null : QbOperators.operatorLockName(first(ctx, FOUND).get("operatorId"))))
                .step("Read it again, and the request", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbOperators.defaultDataset(SETTLEMENT), ctx -> byId(first(ctx, FOUND) == null
                        ? null : String.valueOf(first(ctx, FOUND).id())), SETTLEMENT_ROW),
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", resultInput(ctx).requestId() == null
                            ? new ArrayList<>() : new ArrayList<>(List.of(resultInput(ctx).requestId()))))
                        .limit(1).build(), REQUEST)))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(resultInput(ctx).status()),
                    APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, SETTLEMENT_ROW))
                        .preparedBy(resultInput(ctx).preparerId()), APPROVAL))
                .compute("Pay or return", (metadata, ctx) -> decided(ctx))
                .step("Post the payment", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                .compute("Mark it paid", (metadata, ctx) -> paid(ctx)));
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance settlement = first(ctx, SETTLEMENT_ROW);
        TransferProcesses.ApprovalResultInput input = resultInput(ctx);
        return settlement != null && PAYING.equals(settlement.get("status")) && input.requestId() != null
            && input.requestId().equals(settlement.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance settlement = first(ctx, SETTLEMENT_ROW);
        TransferProcesses.ApprovalResultInput input = resultInput(ctx);
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval == null || approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                return;
            }
            BigDecimal payable = money(settlement.get("payable"));
            ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), PAYMENT_DESCRIPTION,
                reference(settlement.get("operatorId"), settlement.get("month")), List.of(
                    new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES_PAYABLE, Direction.DEBIT, payable),
                    new LedgerProcesses.Line(QbLedger.BANK, Direction.CREDIT, payable)),
                SETTLEMENT, String.valueOf(settlement.id())));
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, settlement)) {
            // The reviewer's note stays with the approval; finance records the payment again.
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", ISSUED);
            values.put("paidOn", null);
            values.put("bankReference", null);
            values.put("paymentRecordedBy", null);
            values.put("paymentRecordedAt", null);
            values.put("approvalRequestId", null);
            values.put("revision", money(settlement.get("revision")).add(BigDecimal.ONE));
            ctx.put(STATUS_AFTER, ISSUED);
            ctx.changes().update(SETTLEMENT, settlement.id(), settlement.version(), values);
        }
    }

    /** Whether the stored request (not the event) says that the payment, as recorded, was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance settlement) {
        EntityInstance request = first(ctx, REQUEST);
        if (request == null) {
            return false;
        }
        String hash = ContentHash.of(approvalCase(settlement).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && APPROVAL_SUBJECT.equals(request.get("subject"))
            && String.valueOf(settlement.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    static void paid(ProcessContext ctx) {
        if (!ctx.contains(POSTING)) {
            return;
        }
        EntityInstance settlement = first(ctx, SETTLEMENT_ROW);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", PAID);
        values.put("paidAt", ctx.opTime());
        values.put("paymentTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
        values.put("revision", money(settlement.get("revision")).add(BigDecimal.ONE));
        ctx.put(STATUS_AFTER, PAID);
        ctx.changes().update(SETTLEMENT, settlement.id(), settlement.version(), values);
    }

    // ---- shared --------------------------------------------------------------------------------------------------------

    private static EntityQuery monthOf(String operatorId, String month) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("operatorId", operatorId), new QueryPredicate.Eq("month", month)))).limit(1).build();
    }

    /** The language of an operator's statements: its own (Q59c), else {@value #LANGUAGE}. */
    static String statementLanguage(EntityInstance operator) {
        Object language = operator == null ? null : operator.get("language");
        return language == null ? LANGUAGE : String.valueOf(language);
    }

    static EntityQuery byId(String settlementId) {
        List<Object> ids = new ArrayList<>();
        if (settlementId != null) {
            ids.add(settlementId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("settlementId", ids)).limit(1).build();
    }

    private static ProcessContext context(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static RunInput runInput(ProcessContext ctx) {
        return ctx.get(INPUT, RunInput.class);
    }

    private static PayInput payInput(ProcessContext ctx) {
        return ctx.get(INPUT, PayInput.class);
    }

    private static TransferProcesses.ApprovalResultInput resultInput(ProcessContext ctx) {
        return ctx.get(INPUT, TransferProcesses.ApprovalResultInput.class);
    }

    private static SettlementOutput output(ProcessContext ctx) {
        EntityInstance row = first(ctx, NEW_ROW) != null ? first(ctx, NEW_ROW) : first(ctx, SETTLEMENT_ROW);
        if (row == null) {
            return new SettlementOutput(null, null, null, null, null);
        }
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER) : row.get("status");
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        String requestId = approval != null && approval.status() == ApprovalOutcome.Status.PENDING
            ? approval.requestId() : row.get("approvalRequestId");
        String documentNo = ctx.contains(ISSUED_DOCUMENT)
            ? ctx.get(ISSUED_DOCUMENT, DocumentProcesses.IssueOutput.class).documentNo() : row.get("documentNo");
        return new SettlementOutput(String.valueOf(row.id()), status, money(row.get("payable")), documentNo,
            requestId);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        return found instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> instances(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        return found instanceof List<?> list ? (List<EntityInstance>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    static EntityInstance first(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        List<EntityInstance> rows = found instanceof List<?> list ? (List<EntityInstance>) list : List.of();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}

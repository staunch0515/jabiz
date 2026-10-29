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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Operators' monthly settlements (docs/quizbuks/plans/Q48-operators.md, Q48b; D-Q48-6, D-Q48-7).
 *
 * <ul>
 *   <li>{@value #RUN} ({@code internal()}, the item of the monthly job {@value #JOB}): one operator's month (Japan's
 *       calendar month, {@code jabiz.fiscal-year-zone}). The commission of its publications ({@code 4110} by the
 *       publication, whose card names the operator: {@value #REVENUE_TEMPLATE}) less the platform's share
 *       ({@code qb.operator.platform-share}) is the operator's share, rounded down to the yen; it is posted as an
 *       expense owed (debit 5130 / credit 2140). Finance issues its statement ({@value #STATEMENT}).
 *       The month's prospecting cost (Q48d, in yen at {@code qb.prospect.usd-jpy}) and what was carried in are
 *       deducted from what is paid; what they exceed it by is carried to the next month, never a negative payment.</li>
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
    public static final String REVENUE_TEMPLATE = "qb.system.operator-revenue";
    public static final String DOCUMENT_TEMPLATE = "qb.admin.operator-settlement-doc";
    public static final String ISSUER_TEMPLATE = "qb.admin.settlement-issuer";
    public static final String LAYOUT_ID = "qb.operator_settlement";
    public static final String APPROVAL_SUBJECT = "qb.operator-settlement";
    public static final String REVIEW_RULE = "qb.operator-settlement.review";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:operator-settlement-status";
    /** The language of the statements: the operators are in Japan (§6 item 5). */
    public static final String LANGUAGE = "ja";

    public static final String ISSUED = "ISSUED";
    public static final String PAYING = "PAYING";
    public static final String PAID = "PAID";
    public static final List<String> STATUSES = List.of(ISSUED, PAYING, PAID);

    /** The ledger's references of the two postings: the settlement's month and operator. */
    static final String ISSUE_DESCRIPTION = "Operator share";
    static final String PAYMENT_DESCRIPTION = "Operator share paid";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    public static final DocumentLayout LAYOUT = DocumentLayout.define(LAYOUT_ID, d -> d
        .permissions(QbPermissions.ADMIN_FINANCE_READ)
        .subject(SETTLEMENT, "settlementId")
        .number(DOCUMENT_TEMPLATE, "settlementNo")
        .party("issuer", ISSUER_TEMPLATE, "name", "address", "registrationNo")
        .party("operator", DOCUMENT_TEMPLATE, "operatorName", "contactName")
        .facts(DOCUMENT_TEMPLATE, "month", "revenue", "platformShare", "operatorShare", "prospectingCost",
            "carriedIn", "carriedOut")
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
        eb.field("revenue", f -> f.physicalColumn("revenue").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("platformShare", f -> f.physicalColumn("platform_share").immutable(true).required(true)
            .asNumeric(5, 4));
        eb.field("operatorShare", f -> f.physicalColumn("operator_share").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("prospectingCost", f -> f.physicalColumn("prospecting_cost").immutable(true).required(true)
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
    static final String USAGE = "usage";
    static final String REVENUE = "revenue";
    static final String NEW_ID = "newId";
    static final String NEW_ROW = "newRow";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String ISSUED_DOCUMENT = "issuedDocument";
    static final String APPROVAL = "approval";
    static final String REQUEST = "request";
    static final String RULES = "rules";
    static final String STATUS_AFTER = "statusAfter";

    private OperatorSettlements() {}

    // ---- the month -----------------------------------------------------------------------------------------------------

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
                    .where(new QueryPredicate.In(ParamEntities.KEY, new ArrayList<>(List.of(
                        QbParams.OPERATOR_PLATFORM_SHARE, QbParams.PROSPECT_USD_JPY)))).limit(2).build(), PARAMS),
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.USAGE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("owner", runInput(ctx).operatorId()),
                        new QueryPredicate.Eq("month", runInput(ctx).month())))).limit(1).build(), USAGE)))
            .step("Add up the commission", RunTemplate.<ProcessContext>of(REVENUE_TEMPLATE, ctx -> Map.of(
                "operatorId", runInput(ctx).operatorId(),
                "from", monthStart(runInput(ctx).month(), zone),
                "to", monthEnd(runInput(ctx).month(), zone)), REVENUE))
            .compute("Settle", (metadata, ctx) -> settle(ctx, zone))
            .step("Save the settlement", SaveChanges.now())
            .step("Post the share owed", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
            .step("Read the settlement", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(SETTLEMENT),
                ctx -> byId(ctx.contains(NEW_ID) ? (String) ctx.get(NEW_ID) : null), NEW_ROW))
            .compute("Record the posting", (metadata, ctx) -> recordPosting(ctx)));
    }

    static void settle(ProcessContext ctx, ZoneId zone) {
        RunInput input = runInput(ctx);
        if (first(ctx, OPERATOR) == null || first(ctx, SETTLEMENT_ROW) != null) {
            return;
        }
        if (!ctx.opTime().isAfter(monthEnd(input.month(), zone))) {
            throw new BusinessRuleViolationException(new Violation("month", OperatorCodes.MONTH_OPEN,
                "The month has not ended", Map.of("month", input.month())));
        }
        BigDecimal share = platformShare(ctx);
        if (share == null) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NO_SHARE,
                "The platform's share of the operators' commission is not set", Map.of()));
        }
        Map<String, Object> revenueRow = rows(ctx, REVENUE).isEmpty() ? Map.of() : rows(ctx, REVENUE).getFirst();
        BigDecimal revenue = money(revenueRow.get("revenue"));
        BigDecimal operatorShare = operatorShare(revenue, share);
        EntityInstance previous = first(ctx, PREVIOUS);
        BigDecimal carriedIn = previous == null ? BigDecimal.ZERO : money(previous.get("carriedOut"));
        BigDecimal cost = prospectingCost(ctx);
        if (cost == null) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NO_RATE,
                "The yen of a dollar the prospecting cost is deducted at is not set", Map.of()));
        }
        BigDecimal[] payable = payable(operatorShare, cost, carriedIn);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operatorId", input.operatorId());
        values.put("month", input.month());
        values.put("periodStart", monthStart(input.month(), zone));
        values.put("periodEnd", monthEnd(input.month(), zone));
        values.put("revenue", revenue);
        values.put("platformShare", share);
        values.put("operatorShare", operatorShare);
        values.put("prospectingCost", cost);
        values.put("carriedIn", carriedIn);
        values.put("payable", payable[0]);
        values.put("carriedOut", payable[1]);
        values.put("status", ISSUED);
        values.put("issuedAt", ctx.opTime());
        values.put("revision", BigDecimal.ONE);
        String id = String.valueOf(ctx.changes().insert(SETTLEMENT, values));
        ctx.put(NEW_ID, id);
        if (operatorShare.signum() > 0) {
            // The expense is the operator's share: what is payable is owed (2140), the prospecting cost it recovers is
            // headquarters' revenue (4110, without a publication). A cost the share does not cover is carried, and
            // booked only when a later month recovers it.
            List<LedgerProcesses.Line> lines = new ArrayList<>();
            lines.add(new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES, Direction.DEBIT, operatorShare));
            if (payable[0].signum() > 0) {
                lines.add(new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES_PAYABLE, Direction.CREDIT, payable[0]));
            }
            BigDecimal recovered = operatorShare.subtract(payable[0]);
            if (recovered.signum() > 0) {
                lines.add(new LedgerProcesses.Line(QbLedger.SERVICE_REVENUE, Direction.CREDIT, recovered));
            }
            ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), ISSUE_DESCRIPTION,
                reference(input.operatorId(), input.month()), List.copyOf(lines), SETTLEMENT, id));
        }
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
                .step("Issue the statement", CallProcess.<ProcessContext>when(
                    ctx -> first(ctx, SETTLEMENT_ROW).get("documentRunId") == null,
                    DocumentProcesses.ISSUE, 1, ctx -> new DocumentProcesses.IssueInput(LAYOUT_ID,
                        Map.of("settlementId", String.valueOf(first(ctx, SETTLEMENT_ROW).id())), null, null, LANGUAGE),
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

    private static BigDecimal platformShare(ProcessContext ctx) {
        Object value = param(ctx, QbParams.OPERATOR_PLATFORM_SHARE);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        BigDecimal share = new BigDecimal(value.toString());
        return share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0 ? null : share;
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
    static EntityInstance first(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        List<EntityInstance> rows = found instanceof List<?> list ? (List<EntityInstance>) list : List.of();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}

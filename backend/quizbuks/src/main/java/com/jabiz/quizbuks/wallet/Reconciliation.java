package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The daily reconciliation (F-06; docs/quizbuks/plans/Q6-payments.md, D-Q6-8): {@value #RUN}, run by the job
 * {@value #JOB} every day at 18:37 UTC (03:37 in Tokyo), compares the wallets' cache with the ledger, the pending
 * rewards with the attempts, the totals, and each top-up with its postings. It takes no business lock and only reads
 * until it writes its result: one run ({@code QbReconRun}) per scheduled time, written once, and one item
 * ({@code QbReconItem}) per difference. With differences it asks a person and announces {@value #DIFF_EVENT} (Q9's
 * exports stop on a {@code DIFF} run).
 */
public final class Reconciliation {

    public static final String RUN = "QB_RECONCILE_RUN";
    public static final String JOB = "qb.reconcile";
    public static final String DIFF_EVENT = "qb.reconcile.diff";
    /** Every day at 18:37 UTC (03:37 in Tokyo); a six-field cron in UTC. Only the end-to-end tests change it (Q9d). */
    public static final String RUN_CRON = "0 37 18 * * *";
    public static final String RUN_CRON_SETTING = "quizbuks.reconcile.run-cron";

    public static final String RUN_ENTITY_NAME = "QbReconRun";
    public static final String ITEM_ENTITY_NAME = "QbReconItem";
    public static final String CHECK_DICTIONARY = "urn:jabiz:dict:quizbuks:recon-check";

    public static final String WALLETS_TEMPLATE = "qb.system.recon-sponsor-wallets";
    /** Q87a: the sponsors' cash accounts against account 2160. */
    public static final String CASH_TEMPLATE = "qb.system.recon-cash-accounts";
    public static final String PENDING_TEMPLATE = "qb.system.recon-sponsor-pending";
    public static final String TOTALS_TEMPLATE = "qb.system.recon-totals";
    public static final String TOPUPS_TEMPLATE = "qb.system.recon-topups";
    public static final String TAKERS_TEMPLATE = "qb.system.recon-taker-wallets";
    public static final String TRANSFERS_TEMPLATE = "qb.system.recon-transfers";
    /** Q87b: the fees receivable (1130 by sponsor) against the invoices and charges still owed. */
    static final String RECEIVABLE_TEMPLATE = "qb.system.recon-fee-receivable";
    /** Q88b: 2170 by sponsor is never negative. */
    static final String ENTRY_FEES_TEMPLATE = "qb.system.recon-entry-fees";
    /** F-M1: the fee credit and the cash that pay the platform's fees against their lots, grants and payments. */
    static final String FEE_CREDIT_TEMPLATE = "qb.system.recon-fee-credit";
    /** F-M4: an operator's account (2140) against its settlements, withdrawals and transfers. */
    static final String OPERATOR_ACCOUNT_TEMPLATE = "qb.system.recon-operator-account";
    /** F-M5, F-M6: the prepayments and the distribution fee's deposits, settlements and prices. */
    static final String PREPAYMENTS_TEMPLATE = "qb.system.recon-prepayments";
    /** F-M7: the audience fees' refunds and the credit notes. */
    static final String CREDIT_NOTES_TEMPLATE = "qb.system.recon-credit-notes";
    /** F-M8: the exposure fee's deposits, settlements and prices. */
    static final String EXPOSURE_TEMPLATE = "qb.system.recon-exposure";
    /** F-M10: everybody's row of the AI help's usage against the people's rows. */
    static final String HELP_USAGE_TEMPLATE = "qb.system.recon-help-usage";

    public record ReconcileInput(@NotNull Instant scheduledTime) {}

    /** @param already whether the scheduled time had its run before (nothing was done) */
    public record ReconcileOutput(String runId, String status, int differences, boolean already) {}

    public static final EntityDefinition RUN_ENTITY = EntityDefinition.define(RUN_ENTITY_NAME, eb -> {
        eb.physicalTable("qb_recon_run_version");
        eb.primaryKey("runId");
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:recon-run"));
        eb.field("scheduledTime", f -> f.physicalColumn("scheduled_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("status", f -> f.physicalColumn("status").immutable(true).required(true).asText(10));
        eb.field("checkedAt", f -> f.physicalColumn("checked_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("itemCount", f -> f.physicalColumn("item_count").immutable(true).required(true).asNumeric(9, 0));
        eb.field("summary", f -> f.physicalColumn("summary").immutable(true).required(true).asText(2000));
        eb.unique("uk_qb_recon_run_time", "scheduledTime");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("scheduledTime", "status", "itemCount", "checkedAt")
            .filters("scheduledTime", "status")
            .sorts("scheduledTime")
            .defaultSort("scheduledTime", false));
    });

    public static final EntityDefinition ITEM_ENTITY = EntityDefinition.define(ITEM_ENTITY_NAME, eb -> {
        eb.physicalTable("qb_recon_item_version");
        eb.primaryKey("itemId");
        eb.field("itemId", f -> f.physicalColumn("item_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:recon-item"));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true)
            .asReference(RUN_ENTITY_NAME));
        eb.field("check", f -> f.physicalColumn("check_code").immutable(true).required(true)
            .asCode(CHECK_DICTIONARY, ReconChecks.ALL.toArray(String[]::new)));
        eb.field("partyId", f -> f.physicalColumn("party_id").immutable(true).asText(128));
        eb.field("reference", f -> f.physicalColumn("reference").immutable(true).asText(100));
        eb.field("expected", f -> f.physicalColumn("expected").immutable(true).required(true).asNumeric(19, 0));
        eb.field("actual", f -> f.physicalColumn("actual").immutable(true).required(true).asNumeric(19, 0));
        eb.field("difference", f -> f.physicalColumn("difference").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("runId", "check", "partyId", "reference", "expected", "actual", "difference")
            .filters("runId", "check", "partyId", "reference")
            .sorts("check", "difference")
            .defaultSort("check", true));
    });

    static final String INPUT = "input";
    static final String EXISTING = "existing";
    static final String WALLETS = "wallets";
    static final String CASH_ACCOUNTS = "cashAccounts";
    static final String PENDING = "pending";
    static final String TOTALS = "totals";
    static final String TOPUPS = "topUps";
    static final String TAKERS = "takers";
    static final String TRANSFERS = "transfers";
    static final String RECEIVABLE = "receivable";
    static final String ENTRY_FEES = "entryFees";
    static final String FEE_CREDIT = "feeCredit";
    static final String OPERATOR_ACCOUNT = "operatorAccount";
    static final String PREPAYMENTS = "prepayments";
    static final String CREDIT_NOTES = "creditNotes";
    static final String EXPOSURE = "exposure";
    static final String HELP_USAGE = "helpUsage";
    static final String DISTRIBUTION_PARAMS = "distributionParams";
    static final String PREVIOUS = "previous";
    static final String PREVIOUS_TAKERS = "previousTakers";
    static final String RESYNC = "resync";
    static final String RESULT = "result";
    static final String RUN_ID = "runId";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** @param zone the fiscal year's zone: F-M6's months of the distribution fee (the unsettled publications) */
    public static ProcessDefinition<ReconcileInput, ReconcileOutput, ProcessContext> process(java.time.ZoneId zone) {
        return ProcessDefinition.define(RUN, 1, ReconcileInput.class, ReconcileOutput.class, ProcessContext.class,
            pb -> pb
                .description("Reconciles the wallets, the pending rewards and the top-ups with the ledger.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(Reconciliation::output)
                .step("Find the run of this time", QueryEntities.<ProcessContext>of(defaultDataset(RUN_ENTITY_NAME),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("scheduledTime",
                        ctx.get(INPUT, ReconcileInput.class).scheduledTime())).limit(1).build(), EXISTING))
                // Each comparison is one statement: its snapshot is consistent, whatever commits meanwhile.
                .step("Compare the sponsor wallets", RunTemplate.<ProcessContext>of(WALLETS_TEMPLATE,
                    ctx -> Map.of(), WALLETS))
                .step("Compare the cash accounts", RunTemplate.<ProcessContext>of(CASH_TEMPLATE,
                    ctx -> Map.of(), CASH_ACCOUNTS))
                .step("Compare the pending rewards", RunTemplate.<ProcessContext>of(PENDING_TEMPLATE,
                    ctx -> Map.of(), PENDING))
                .step("Compare the totals", RunTemplate.<ProcessContext>of(TOTALS_TEMPLATE, ctx -> Map.of(), TOTALS))
                .step("Compare the top-ups", RunTemplate.<ProcessContext>of(TOPUPS_TEMPLATE, ctx -> Map.of(),
                    TOPUPS))
                .step("Compare the taker wallets", RunTemplate.<ProcessContext>of(TAKERS_TEMPLATE, ctx -> Map.of(),
                    TAKERS))
                .step("Compare the transfers", RunTemplate.<ProcessContext>of(TRANSFERS_TEMPLATE, ctx -> Map.of(),
                    TRANSFERS))
                // Q87b: the fees receivable (1130 by sponsor) against the invoices and uninvoiced charges.
                .step("Compare the fees receivable", RunTemplate.<ProcessContext>of(RECEIVABLE_TEMPLATE,
                    ctx -> Map.of(), RECEIVABLE))
                .step("Check the entry fees unsettled", RunTemplate.<ProcessContext>of(ENTRY_FEES_TEMPLATE,
                    ctx -> Map.of(), ENTRY_FEES))
                // F-M6 (review L4): the distribution checks' hours are the parameters' values, none written in the SQL.
                .step("Load the distribution checks' hours", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                    DISTRIBUTION_PARAMS, QbParams.DISTRIBUTION_UNSETTLED_AFTER_HOURS,
                    QbParams.DISTRIBUTION_FINAL_MISSING_AFTER_HOURS, QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS,
                    QbParams.EXPOSURE_UNSETTLED_AFTER_HOURS, QbParams.EXPOSURE_FINAL_MISSING_AFTER_HOURS))
                // The fee account's checks are four statements by domain (F-M14 G10), each with the parameters it declares.
                .step("Compare the fee credit", RunTemplate.<ProcessContext>of(FEE_CREDIT_TEMPLATE,
                    ctx -> Map.of(), FEE_CREDIT))
                .step("Compare the operator accounts", RunTemplate.<ProcessContext>of(OPERATOR_ACCOUNT_TEMPLATE,
                    ctx -> Map.of(), OPERATOR_ACCOUNT))
                .step("Compare the prepayments", RunTemplate.<ProcessContext>of(PREPAYMENTS_TEMPLATE,
                    ctx -> feeAccountParams(ctx, zone, "at", "zone", "unsettledHours", "missingHours"), PREPAYMENTS))
                // F-M8: the exposure fee's, with its own hours.
                .step("Compare the exposure fees", RunTemplate.<ProcessContext>of(EXPOSURE_TEMPLATE,
                    ctx -> exposureParams(ctx, zone), EXPOSURE))
                .step("Compare the credit notes", RunTemplate.<ProcessContext>of(CREDIT_NOTES_TEMPLATE,
                    ctx -> feeAccountParams(ctx, zone, "at", "refundHours"), CREDIT_NOTES))
                .step("Compare the AI help's usage", RunTemplate.<ProcessContext>of(HELP_USAGE_TEMPLATE,
                    ctx -> Map.of(), HELP_USAGE))
                // The takers' cache follows by events: one found behind counts only when the previous run found them.
                .step("Find the previous run", QueryEntities.<ProcessContext>of(defaultDataset(RUN_ENTITY_NAME),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Lt("scheduledTime",
                        ctx.get(INPUT, ReconcileInput.class).scheduledTime()))
                        .orderBy("scheduledTime", false).limit(1).build(), PREVIOUS))
                .step("Find whom it found", QueryEntities.<ProcessContext>of(defaultDataset(ITEM_ENTITY_NAME),
                    ctx -> {
                        List<Object> runs = new ArrayList<>();
                        if (TopUpCrediting.first(ctx, PREVIOUS) != null) {
                            runs.add(TopUpCrediting.first(ctx, PREVIOUS).id());
                        }
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("runId", runs),
                            new QueryPredicate.In("check", List.of(ReconChecks.TAKER_WALLET,
                                ReconChecks.TAKER_IN_TRANSFER, ReconChecks.TAKER_WALLET_LAG)))))
                            .limit(WalletLimits.MAX_RECON_ITEMS).build();
                    }, PREVIOUS_TAKERS))
                .compute("Record the result", (metadata, ctx) -> record(ctx))
                .step("Ask a person", CreateTask.<ProcessContext>when(Reconciliation::differs, Reconciliation::task))
                .step("Resync the takers found", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(RESYNC),
                    TakerWallets.RESYNC_EVENT, ctx -> Map.of("userIds", ctx.get(RESYNC))))
                .step("Announce the differences", PublishEvent.<ProcessContext>when(Reconciliation::differs,
                    DIFF_EVENT, ctx -> {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("runId", String.valueOf(ctx.get(RUN_ID)));
                        payload.put("scheduledTime", ctx.get(INPUT, ReconcileInput.class).scheduledTime().toString());
                        payload.put("differences", result(ctx).differences());
                        return payload;
                    }))
                .afterCommit("Show the differences on the gauge", ReconcileGauge.class,
                    com.jabiz.process.NoMetadata.INSTANCE));
    }

    @SuppressWarnings("unchecked")
    static void record(ProcessContext ctx) {
        if (TopUpCrediting.first(ctx, EXISTING) != null) {
            return;
        }
        List<ReconChecks.Item> found = new ArrayList<>();
        found.addAll(ReconChecks.perParty(ReconChecks.SPONSOR_WALLET, rows(ctx, WALLETS)));
        found.addAll(ReconChecks.perParty(ReconChecks.CASH_ACCOUNT, rows(ctx, CASH_ACCOUNTS)));
        found.addAll(ReconChecks.pending(rows(ctx, PENDING)));
        found.addAll(ReconChecks.coded(rows(ctx, TOTALS)));
        found.addAll(ReconChecks.coded(rows(ctx, TOPUPS)));
        found.addAll(ReconChecks.coded(rows(ctx, TRANSFERS)));
        found.addAll(ReconChecks.coded(rows(ctx, RECEIVABLE)));
        found.addAll(ReconChecks.coded(rows(ctx, ENTRY_FEES)));
        found.addAll(ReconChecks.coded(rows(ctx, FEE_CREDIT)));
        found.addAll(ReconChecks.coded(rows(ctx, OPERATOR_ACCOUNT)));
        found.addAll(ReconChecks.coded(rows(ctx, PREPAYMENTS)));
        found.addAll(ReconChecks.coded(rows(ctx, CREDIT_NOTES)));
        found.addAll(ReconChecks.coded(rows(ctx, EXPOSURE)));
        found.addAll(ReconChecks.coded(rows(ctx, HELP_USAGE)));
        java.util.Set<String> foundBefore = new java.util.HashSet<>();
        TopUpCrediting.list(ctx, PREVIOUS_TAKERS).forEach(item -> foundBefore.add(item.get("partyId")));
        ReconChecks.TakerItems takers = ReconChecks.takers(rows(ctx, TAKERS), foundBefore);
        found.addAll(takers.items());
        if (!takers.resync().isEmpty()) {
            ctx.put(RESYNC, takers.resync().size() <= WalletLimits.MAX_RECON_ITEMS ? takers.resync()
                : List.copyOf(takers.resync().subList(0, WalletLimits.MAX_RECON_ITEMS)));
        }
        ReconChecks.Result result = ReconChecks.result(found, WalletLimits.MAX_RECON_ITEMS);
        ctx.put(RESULT, result);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("scheduledTime", ctx.get(INPUT, ReconcileInput.class).scheduledTime());
        run.put("status", result.status());
        run.put("checkedAt", ctx.opTime());
        run.put("itemCount", result.total());
        run.put("summary", result.summary());
        Object runId = ctx.changes().insert(RUN_ENTITY_NAME, run);
        ctx.put(RUN_ID, runId);
        for (ReconChecks.Item item : result.items()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("runId", runId);
            values.put("check", item.check());
            values.put("partyId", clip(item.partyId(), 128));
            values.put("reference", clip(item.reference(), 100));
            values.put("expected", item.expected());
            values.put("actual", item.actual());
            values.put("difference", item.difference());
            ctx.changes().insert(ITEM_ENTITY_NAME, values);
        }
    }

    private static boolean differs(ProcessContext ctx) {
        return ctx.contains(RESULT) && ReconChecks.DIFF.equals(result(ctx).status());
    }

    private static TaskSpec task(ProcessContext ctx) {
        String day = ctx.get(INPUT, ReconcileInput.class).scheduledTime().atOffset(ZoneOffset.UTC).toLocalDate()
            .toString();
        // F-M14: with how many checks differ; one a day to qb.reconcile, whose every check today's differences are
        // (ReconChecks.owner: a check owned by another permission is asked about in its own task, F-M10).
        return TaskSpec.forPermission("qb.recon-diff", "task.qb.recon-diff", Map.of("date", day,
                "differences", String.valueOf(result(ctx).differences()),
                "checks", String.valueOf(result(ctx).checksDiffering())), QbPermissions.RECONCILE)
            .about(RUN_ENTITY_NAME, ctx.get(RUN_ID)).source("recon:" + day);
    }

    private static ReconcileOutput output(ProcessContext ctx) {
        var existing = TopUpCrediting.first(ctx, EXISTING);
        if (existing != null) {
            return new ReconcileOutput(String.valueOf(existing.id()), existing.get("status"),
                ((Number) existing.get("itemCount")).intValue(), true);
        }
        ReconChecks.Result result = result(ctx);
        return new ReconcileOutput(String.valueOf(ctx.get(RUN_ID)), result.status(), result.differences(), false);
    }

    private static ReconChecks.Result result(ProcessContext ctx) {
        return ctx.get(RESULT, ReconChecks.Result.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    private static String clip(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private Reconciliation() {}

    /**
     * The fee account templates' parameters: the run's time and zone, and the checks' hours (or none); a template is
     * given only the {@code names} it declares.
     */
    static Map<String, Object> feeAccountParams(ProcessContext ctx, java.time.ZoneId zone, String... names) {
        com.jabiz.param.ParamValues params = ctx.get(DISTRIBUTION_PARAMS, com.jabiz.param.ParamValues.class);
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("at", ctx.get(INPUT, ReconcileInput.class).scheduledTime());
        values.put("zone", zone.getId());
        values.put("unsettledHours", params.find(QbParams.DISTRIBUTION_UNSETTLED_AFTER_HOURS, java.math.BigDecimal.class)
            .orElse(null));
        values.put("missingHours", params.find(QbParams.DISTRIBUTION_FINAL_MISSING_AFTER_HOURS,
            java.math.BigDecimal.class).orElse(null));
        // F-M7 (N9): the audience fees not decided yet.
        values.put("refundHours", params.find(QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS,
            java.math.BigDecimal.class).orElse(null));
        values.keySet().retainAll(java.util.Set.of(names));
        return values;
    }

    /** F-M8: the exposure fee's checks' parameters: when it runs and the two hours (none handed in: not checked). */
    static Map<String, Object> exposureParams(ProcessContext ctx, java.time.ZoneId zone) {
        com.jabiz.param.ParamValues params = ctx.get(DISTRIBUTION_PARAMS, com.jabiz.param.ParamValues.class);
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("at", ctx.get(INPUT, ReconcileInput.class).scheduledTime());
        values.put("zone", zone.getId());
        values.put("unsettledHours", params.find(QbParams.EXPOSURE_UNSETTLED_AFTER_HOURS, java.math.BigDecimal.class)
            .orElse(null));
        values.put("missingHours", params.find(QbParams.EXPOSURE_FINAL_MISSING_AFTER_HOURS, java.math.BigDecimal.class)
            .orElse(null));
        return values;
    }
}

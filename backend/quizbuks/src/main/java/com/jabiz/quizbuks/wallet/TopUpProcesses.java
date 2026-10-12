package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.ledger.Direction;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.task.CreateTask;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.wallet.TopUpCrediting.TOPUP;
import static com.jabiz.quizbuks.wallet.TopUpCrediting.first;

/**
 * A sponsor's top-up from start to its checkout (docs/quizbuks/plans/Q6-payments.md, D-Q6-2, D-Q6-4, D-Q6-5):
 * <ul>
 *   <li>{@value #START} (the sponsor): checks the caller and the amount, records an {@code OPENING} top-up and starts
 *       the job that creates its checkout; the page polls the top-up until it is {@code OPEN} and sends the sponsor
 *       to the checkout's address;</li>
 *   <li>{@value #OPENED} / {@value #OPEN_FAILED} (the job's result): {@code OPEN} with the checkout, or
 *       {@code FAILED};</li>
 *   <li>{@value #CHECK} (each item of the hourly job {@code qb.topup-stale}): asks Stripe about a top-up whose checkout
 *       expired an hour ago without word from Stripe; {@value #CHECKED} credits or expires it as an event would
 *       ({@link TopUpCrediting}), {@value #CHECK_FAILED} asks a person;</li>
 *   <li>{@value #FEE_RECORD} / {@value #FEE_FAILED} (the fee's job after a credit): Stripe's fee, posted debit 5110 /
 *       credit 1110, with the charge and the balance transaction (F-04).</li>
 * </ul>
 * All but the first are {@code internal()} (platform decision D41) and take the sponsor's wallet lock before reading
 * the top-up again; the lock's name comes from the record.
 */
public final class TopUpProcesses {

    public static final String START = "QB_TOPUP_START";
    public static final String OPENED = "QB_TOPUP_OPENED";
    public static final String OPEN_FAILED = "QB_TOPUP_OPEN_FAILED";
    public static final String CHECK = "QB_TOPUP_CHECK";
    public static final String CHECKED = "QB_TOPUP_CHECKED";
    public static final String CHECK_FAILED = "QB_TOPUP_CHECK_FAILED";
    public static final String FEE_RECORD = "QB_TOPUP_FEE_RECORD";
    public static final String FEE_FAILED = "QB_TOPUP_FEE_FAILED";

    /** The hourly job asking Stripe about stale top-ups, and its selection. */
    public static final String STALE_JOB = "qb.topup-stale";
    public static final String STALE_TEMPLATE = "qb.system.topups-stale";

    /** @param amount whole Kudos within {@code qb.topup.min} – {@code qb.topup.max} */
    public record StartInput(@NotNull @DecimalMin("1") @DecimalMax("1000000000000")
        @Digits(integer = 13, fraction = 0) BigDecimal amount) {}

    /** @param jobId the job creating the checkout */
    public record StartOutput(String topUpId, String status, String jobId) {}

    public record TopUpRef(@NotBlank @Size(max = 36) String topUpId) {}

    /** @param status the top-up's state after the process; null when there is none */
    public record TopUpOutput(String topUpId, String status, String reason) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String UNFINISHED = "unfinished";
    static final String TOPUP_ID = "topUpId";
    static final String JOB_ID = "jobId";
    static final String CHECKING = "checking";
    static final String PROBLEM = "problem";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String STATUS_AFTER = "statusAfter";

    // ---- start -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<StartInput, StartOutput, ProcessContext> start(StripeSettings settings) {
        return ProcessDefinition.define(START, 1, StartInput.class, StartOutput.class, ProcessContext.class, pb -> pb
            .description("Starts a top-up of the sponsor wallet: Stripe's checkout opens shortly after.")
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new StartOutput((String) ctx.get(TOPUP_ID), TopUpStatus.OPENING,
                (String) ctx.get(JOB_ID)))
            .steps(SponsorChecks::load)
            .step("Load the limits", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.TOPUP_MIN, QbParams.TOPUP_MAX))
            // A soft limit, without a lock: a top-up or two more from concurrent starts do no harm, and the job type
            // allows the sponsor three open jobs at most besides. An opening one counts for an hour only: one whose
            // checkout never opened (its job's result lost) does not hold a place for ever.
            .step("Load the unfinished top-ups", QueryEntities.<ProcessContext>of(
                QbTopUps.defaultDataset(QbTopUps.TOPUP), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                        new QueryPredicate.Or(List.of(
                            new QueryPredicate.And(List.of(new QueryPredicate.Eq("status", TopUpStatus.OPENING),
                                new QueryPredicate.Gt("createdAt", ctx.opTime().minus(WalletLimits.OPENING_COUNTS)))),
                            new QueryPredicate.And(List.of(new QueryPredicate.Eq("status", TopUpStatus.OPEN),
                                new QueryPredicate.Gt("expiresAt", ctx.opTime()))))))))
                    .limit(WalletLimits.MAX_OPEN_TOPUPS).build(), UNFINISHED))
            .compute("Record the top-up", (metadata, ctx) -> startTopUp(ctx, settings))
            .step("Create the checkout", StartExternalJob.<ProcessContext>of(TopUpJobs.CHECKOUT_CREATE,
                    ctx -> CheckoutRequest.input((String) ctx.get(TOPUP_ID), ctx.request().actorId(),
                        ctx.get(INPUT, StartInput.class).amount(), ctx.opTime(), settings))
                .reference(ctx -> (String) ctx.get(TOPUP_ID))
                .outputKey(JOB_ID)));
    }

    static void startTopUp(ProcessContext ctx, StripeSettings settings) {
        List<Violation> problems = new ArrayList<>(SponsorChecks.problems(ctx));
        BigDecimal amount = ctx.get(INPUT, StartInput.class).amount();
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        BigDecimal min = params.get(QbParams.TOPUP_MIN, BigDecimal.class);
        BigDecimal max = params.get(QbParams.TOPUP_MAX, BigDecimal.class);
        if (amount.compareTo(min) < 0 || amount.compareTo(max) > 0) {
            problems.add(new Violation("amount", WalletCodes.TOPUP_AMOUNT_RANGE, "A top-up is between " + min
                + " and " + max + " Kudos", Map.of("min", min.toPlainString(), "max", max.toPlainString())));
        }
        if (TopUpCrediting.list(ctx, UNFINISHED).size() >= WalletLimits.MAX_OPEN_TOPUPS) {
            problems.add(new Violation(null, WalletCodes.TOPUP_TOO_MANY_OPEN, "Finish or let expire the unfinished "
                + "top-ups first", Map.of("max", WalletLimits.MAX_OPEN_TOPUPS)));
        }
        if (settings.sponsorLink().isEmpty()) {
            problems.add(new Violation(null, WalletCodes.TOPUP_UNAVAILABLE, "Top-ups are not configured",
                Map.of()));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", ctx.request().actorId());
        values.put("amount", amount);
        values.put("status", TopUpStatus.OPENING);
        values.put("createdAt", ctx.opTime());
        values.put("refundedAmount", BigDecimal.ZERO);
        values.put("refundShortfall", BigDecimal.ZERO);
        values.put("disputeClawback", BigDecimal.ZERO);
        values.put("disputeShortfall", BigDecimal.ZERO);
        values.put("disputeHeld", BigDecimal.ZERO);
        ctx.put(TOPUP_ID, String.valueOf(ctx.changes().insert(QbTopUps.TOPUP, values)));
    }

    // ---- the checkout's job ----------------------------------------------------------------------------------------

    public static ProcessDefinition<ExternalJobCompletion, TopUpOutput, ProcessContext> opened(
        StripeSettings settings) {
        return ProcessDefinition.define(OPENED, 1, ExternalJobCompletion.class, TopUpOutput.class,
            ProcessContext.class, pb -> pb
                .description("Opens a top-up with the checkout Stripe created for it.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory(TopUpProcesses::context)
                .outputMapper(TopUpProcesses::output)
                .steps(b -> TopUpCrediting.lockAndLoad(b, ctx -> ctx.get(INPUT, ExternalJobCompletion.class)
                    .reference()))
                .compute("Open the top-up", (metadata, ctx) -> open(ctx, settings))
                .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(PROBLEM), ctx -> {
                    ExternalJobCompletion job = ctx.get(INPUT, ExternalJobCompletion.class);
                    EntityInstance topUp = first(ctx, TOPUP);
                    return TopUpTasks.topUp((String) ctx.get(PROBLEM), topUp == null ? null
                        : String.valueOf(topUp.id()), topUp == null ? null : QbTopUps.money(topUp.get("amount")),
                        StripeService.CURRENCY, TopUpTasks.jobSource(String.valueOf(job.reference()), job.jobId()),
                        null);
                })));
    }

    static void open(ProcessContext ctx, StripeSettings settings) {
        EntityInstance topUp = first(ctx, TOPUP);
        if (topUp == null) {
            ctx.put(PROBLEM, TopUpChecks.UNKNOWN_TOPUP);
            return;
        }
        if (!TopUpStatus.OPENING.equals(topUp.get("status"))) {
            // Paid, expired or failed meanwhile: the checkout is no longer needed.
            return;
        }
        StripeObjects.CheckoutSession created = ctx.get(INPUT, ExternalJobCompletion.class)
            .result(StripeObjects.CheckoutSession.class);
        String problem = TopUpChecks.openProblem(QbTopUps.view(topUp), created, settings.livemode(),
            settings.checkoutUrlPrefix());
        Map<String, Object> values = new LinkedHashMap<>();
        if (problem == null) {
            values.put("status", TopUpStatus.OPEN);
            values.put("checkoutSessionId", created.id());
            values.put("checkoutUrl", created.url());
            values.put("expiresAt", Instant.ofEpochSecond(created.expiresAt()));
            values.put("livemode", created.livemode());
        } else {
            ctx.put(PROBLEM, problem);
            values.put("status", TopUpStatus.FAILED);
            values.put("failureCode", problem);
            values.put("checkReason", problem);
        }
        ctx.put(STATUS_AFTER, values.get("status"));
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
    }

    public static ProcessDefinition<ExternalJobFailure, TopUpOutput, ProcessContext> openFailed() {
        return ProcessDefinition.define(OPEN_FAILED, 1, ExternalJobFailure.class, TopUpOutput.class,
            ProcessContext.class, pb -> pb
                .description("Fails a top-up whose checkout Stripe could not create; the sponsor may start another.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory(TopUpProcesses::context)
                .outputMapper(TopUpProcesses::output)
                .steps(b -> TopUpCrediting.lockAndLoad(b, ctx -> ctx.get(INPUT, ExternalJobFailure.class)
                    .reference()))
                .compute("Fail the top-up", (metadata, ctx) -> {
                    EntityInstance topUp = first(ctx, TOPUP);
                    if (topUp == null || !TopUpStatus.OPENING.equals(topUp.get("status"))) {
                        return;
                    }
                    String code = ctx.get(INPUT, ExternalJobFailure.class).errorCode();
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", TopUpStatus.FAILED);
                    values.put("failureCode", code == null ? null : code.substring(0, Math.min(code.length(), 100)));
                    ctx.put(STATUS_AFTER, TopUpStatus.FAILED);
                    ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
                }));
    }

    // ---- stale top-ups ---------------------------------------------------------------------------------------------

    public static ProcessDefinition<TopUpRef, TopUpOutput, ProcessContext> check() {
        return ProcessDefinition.define(CHECK, 1, TopUpRef.class, TopUpOutput.class, ProcessContext.class, pb -> pb
            .description("Asks Stripe about a top-up whose checkout expired without word from Stripe.")
            .permissions(QbPermissions.WALLET_SYSTEM)
            .internal()
            .contextFactory(TopUpProcesses::context)
            .outputMapper(TopUpProcesses::output)
            .steps(b -> TopUpCrediting.lockAndLoad(b, ctx -> ctx.get(INPUT, TopUpRef.class).topUpId()))
            .compute("Decide whether to ask", (metadata, ctx) -> {
                EntityInstance topUp = first(ctx, TOPUP);
                if (!stale(topUp, ctx.opTime())) {
                    return;
                }
                ctx.put(CHECKING, true);
                ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(),
                    Map.of("checkRequestedAt", ctx.opTime()));
            })
            .step("Ask Stripe", StartExternalJob.<ProcessContext>of(TopUpJobs.SESSION_CHECK, ctx -> {
                    EntityInstance topUp = first(ctx, TOPUP);
                    return new TopUpJobs.SessionCheckInput(String.valueOf(topUp.id()), topUp.get("checkoutSessionId"));
                })
                .reference(ctx -> String.valueOf(first(ctx, TOPUP).id()))
                .outputKey(JOB_ID)
                .when(ctx -> ctx.contains(CHECKING))));
    }

    /**
     * Whether an open top-up's checkout expired more than {@link WalletLimits#CHECK_AFTER_EXPIRY} ago and Stripe was
     * not asked about it within {@link WalletLimits#CHECK_AGAIN_AFTER}. The selection asks the same; this reads the
     * record again under the lock.
     */
    static boolean stale(EntityInstance topUp, Instant now) {
        if (topUp == null || !TopUpStatus.OPEN.equals(topUp.get("status")) || topUp.get("checkoutSessionId") == null) {
            return false;
        }
        Instant expiresAt = topUp.get("expiresAt");
        Instant asked = topUp.get("checkRequestedAt");
        return expiresAt != null && !expiresAt.plus(WalletLimits.CHECK_AFTER_EXPIRY).isAfter(now)
            && (asked == null || !asked.plus(WalletLimits.CHECK_AGAIN_AFTER).isAfter(now));
    }

    public static ProcessDefinition<ExternalJobCompletion, TopUpCrediting.Outcome, ProcessContext> checked(
        StripeSettings settings) {
        return ProcessDefinition.define(CHECKED, 1, ExternalJobCompletion.class, TopUpCrediting.Outcome.class,
            ProcessContext.class, pb -> pb
                .description("Credits or expires a stale top-up as Stripe's answer says.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = context(start, input);
                    StripeObjects.CheckoutSession session = input.result(StripeObjects.CheckoutSession.class);
                    TopUpCrediting.Mode mode = switch (session == null ? "" : String.valueOf(session.status())) {
                        case "complete" -> TopUpCrediting.Mode.PAID;
                        case "expired" -> TopUpCrediting.Mode.EXPIRED;
                        default -> TopUpCrediting.Mode.NONE;
                    };
                    ctx.put(TopUpCrediting.REPORT, new TopUpCrediting.Report(mode, session, null, ctx.opTime(),
                        "job:" + input.jobId(), TopUpTasks.jobSource(String.valueOf(input.reference()),
                        "check:" + input.jobId()), null));
                    return ctx;
                })
                .outputMapper(TopUpCrediting::outcome)
                .steps(b -> TopUpCrediting.lockAndLoad(b, ctx -> ctx.get(INPUT, ExternalJobCompletion.class)
                    .reference()))
                .steps(b -> TopUpCrediting.apply(b, settings)));
    }

    public static ProcessDefinition<ExternalJobFailure, TopUpOutput, ProcessContext> checkFailed() {
        return failureTask(CHECK_FAILED, TopUpChecks.CHECK_FAILED,
            "Asks a person to look at a stale top-up Stripe could not be asked about.");
    }

    // ---- the fee ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ExternalJobCompletion, TopUpOutput, ProcessContext> feeRecord(
        StripeSettings settings) {
        return ProcessDefinition.define(FEE_RECORD, 1, ExternalJobCompletion.class, TopUpOutput.class,
            ProcessContext.class, pb -> pb
                .description("Records and posts Stripe's fee of a top-up's payment.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory(TopUpProcesses::context)
                .outputMapper(TopUpProcesses::output)
                .steps(b -> TopUpCrediting.lockAndLoad(b, ctx -> ctx.get(INPUT, ExternalJobCompletion.class)
                    .reference()))
                .compute("Check the fee", (metadata, ctx) -> feePlan(ctx, settings))
                .step("Post the fee", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                .compute("Record the fee", (metadata, ctx) -> feeWrite(ctx))
                .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(PROBLEM), ctx -> {
                    ExternalJobCompletion job = ctx.get(INPUT, ExternalJobCompletion.class);
                    EntityInstance topUp = first(ctx, TOPUP);
                    TopUpChecks.Fee fee = job.result(TopUpChecks.Fee.class);
                    return TopUpTasks.topUp((String) ctx.get(PROBLEM), topUp == null ? null
                        : String.valueOf(topUp.id()), fee == null ? null : fee.fee(), fee == null ? null
                        : fee.currency(), TopUpTasks.jobSource(String.valueOf(job.reference()), job.jobId()), null);
                })));
    }

    static void feePlan(ProcessContext ctx, StripeSettings settings) {
        EntityInstance topUp = first(ctx, TOPUP);
        if (topUp != null && topUp.get("stripeFee") != null) {
            return;
        }
        TopUpChecks.Fee fee = ctx.get(INPUT, ExternalJobCompletion.class).result(TopUpChecks.Fee.class);
        String problem = TopUpChecks.feeProblem(QbTopUps.view(topUp), fee, settings.settlementCurrency());
        if (problem != null) {
            ctx.put(PROBLEM, TopUpChecks.STATUS.equals(problem) || TopUpChecks.UNKNOWN_TOPUP.equals(problem)
                || TopUpChecks.FEE_CURRENCY.equals(problem) ? problem : TopUpChecks.FEE_MISMATCH);
            return;
        }
        if (fee.fee().signum() > 0) {
            Instant paidAt = topUp.get("paidAt");
            ctx.put(POST_INPUT, new LedgerProcesses.PostInput(paidAt, "Stripe fee",
                TopUpCrediting.reference(fee.balanceTransactionId()), List.of(
                    new LedgerProcesses.Line(QbLedger.PAYMENT_FEES, Direction.DEBIT, fee.fee()),
                    new LedgerProcesses.Line(QbLedger.STRIPE_BALANCE, Direction.CREDIT, fee.fee())),
                QbTopUps.TOPUP, String.valueOf(topUp.id())));
        }
        ctx.put("fee", fee);
    }

    static void feeWrite(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        TopUpChecks.Fee fee = (TopUpChecks.Fee) ctx.get("fee");
        if (fee == null) {
            if (ctx.contains(PROBLEM) && topUp != null) {
                ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(),
                    Map.of("checkReason", ctx.get(PROBLEM)));
            }
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stripeFee", fee.fee());
        values.put("netAmount", fee.net());
        values.put("chargeId", fee.chargeId());
        values.put("balanceTransactionId", fee.balanceTransactionId());
        values.put("feeTransactionId", ctx.contains(POSTING)
            ? ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId() : null);
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
    }

    public static ProcessDefinition<ExternalJobFailure, TopUpOutput, ProcessContext> feeFailed() {
        return failureTask(FEE_FAILED, TopUpChecks.FEE_FAILED,
            "Asks a person to look at a top-up whose Stripe fee could not be read.");
    }

    /** A job's failure that only asks a person (the top-up stays as it is). */
    private static ProcessDefinition<ExternalJobFailure, TopUpOutput, ProcessContext> failureTask(String name,
        String reason, String description) {
        return ProcessDefinition.define(name, 1, ExternalJobFailure.class, TopUpOutput.class, ProcessContext.class,
            pb -> pb
                .description(description)
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory(TopUpProcesses::context)
                .outputMapper(ctx -> {
                    EntityInstance topUp = first(ctx, TOPUP);
                    return new TopUpOutput(topUp == null ? null : String.valueOf(topUp.id()),
                        topUp == null ? null : topUp.get("status"), reason);
                })
                .step("Find the top-up", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(QbTopUps.TOPUP),
                    ctx -> TopUpCrediting.byId(ctx.get(INPUT, ExternalJobFailure.class).reference()), TOPUP))
                .step("Ask a person", CreateTask.<ProcessContext>of(ctx -> {
                    ExternalJobFailure job = ctx.get(INPUT, ExternalJobFailure.class);
                    EntityInstance topUp = first(ctx, TOPUP);
                    return TopUpTasks.topUp(reason, topUp == null ? null : String.valueOf(topUp.id()),
                        topUp == null ? null : QbTopUps.money(topUp.get("amount")), StripeService.CURRENCY,
                        TopUpTasks.jobSource(String.valueOf(job.reference()), job.jobId()), null);
                })));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static TopUpOutput output(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER)
            : topUp == null ? null : topUp.get("status");
        return new TopUpOutput(topUp == null ? null : String.valueOf(topUp.id()), status,
            (String) ctx.get(PROBLEM));
    }

    private TopUpProcesses() {}
}

package com.jabiz.quizbuks.wallet;

import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.task.CreateTask;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The one way a top-up is credited or expires (docs/quizbuks/plans/Q6-payments.md, D-Q6-3, D-Q6-4), shared by
 * Stripe's events ({@code QB_TOPUP_CONFIRM}, {@code QB_TOPUP_EXPIRE}) and Stripe's answer about a stale top-up
 * ({@code QB_TOPUP_CHECKED}). Whatever reports the session, the steps are the same:
 * <ol>
 *   <li>the top-up is found by its key (outside the lock: only to learn its sponsor, which never changes), the
 *       sponsor's wallet lock is taken (its name from the record, never from what Stripe sent), and the top-up and the
 *       wallet are read again under it;</li>
 *   <li>{@link TopUpChecks} decides from the record;</li>
 *   <li>a credit posts debit 1110 / credit 2110 (the sponsor) at the record's amount with the top-up as its source,
 *       raises the cached balance, marks the top-up {@code PAID} with its posting, asks for the sponsor's pending
 *       rewards to be paid ({@code qb.sponsor.settle-pending}, in a transaction of its own), announces
 *       {@code qb.topup.paid} and starts the job that reads Stripe's fee — all in one transaction;</li>
 *   <li>anything that does not fit becomes a task, and nothing else changes.</li>
 * </ol>
 * Only an opening or open top-up becomes {@code PAID}, under the lock and at the version read under it, so two
 * reports of the same payment (the event twice, two events, the event and the check, concurrently or not) credit it
 * once: the second sees {@code PAID} and does nothing. The database refuses a second posting of a top-up, payment or
 * session besides (V7).
 */
public final class TopUpCrediting {

    /** What reported the session. */
    public enum Mode {
        /** A completed checkout: credit. */
        PAID,
        /** An expired checkout: expire. */
        EXPIRED,
        /** Nothing to do (an open checkout, still). */
        NONE
    }

    /**
     * What a report says and where it comes from.
     *
     * @param eventLivemode  the event's {@code livemode}; null for Stripe's answer to a check
     * @param bookingTime    when the payment happened, as far as the report tells
     * @param confirmedBy    the inbound event's id, or {@code job:<id>} of the check
     * @param sourceKey      the key of a task it causes
     * @param inboundEventId the inbound event, if any (the subject of a task about an unknown top-up)
     */
    public record Report(Mode mode, StripeObjects.CheckoutSession session, Boolean eventLivemode, Instant bookingTime,
        String confirmedBy, String sourceKey, String inboundEventId) {}

    /** {@code CREDITED}, {@code EXPIRED}, {@code ALREADY}, {@code NONE} or {@code CHECK} (a task). */
    public record Outcome(String topUpId, String outcome, String reason) {}

    static final String REPORT = "topup.report";
    static final String FOUND = "topup.found";
    static final String TOPUP = "topup.row";
    static final String WALLET = "topup.wallet";
    static final String VERDICT = "topup.verdict";
    static final String POST_INPUT = "topup.postInput";
    static final String POSTING = "topup.posting";

    /** Finds the top-up of {@code topUpId}, takes its sponsor's wallet lock and reads it and the wallet again. */
    static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> topUpId) {
        pb.step("Find the top-up", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(QbTopUps.TOPUP),
                ctx -> byId(topUpId.apply(ctx)), FOUND))
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> {
                EntityInstance found = first(ctx, FOUND);
                return found == null ? null : QbWallets.lockName(QbWallets.SPONSOR, found.get("sponsorId"));
            }))
            .step("Read the top-up again", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(QbTopUps.TOPUP),
                ctx -> byId(first(ctx, FOUND) == null ? null : String.valueOf(first(ctx, FOUND).id())), TOPUP))
            .step("Read the wallet", QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET),
                ctx -> QbWallets.query(QbWallets.SPONSOR, first(ctx, TOPUP) == null ? null
                    : first(ctx, TOPUP).get("sponsorId")), WALLET));
    }

    /** Decides on the {@link Report} under the lock and acts: the posting, the record, the events, the fee's job. */
    static <I, O> void apply(ProcessDefinitionBuilder<I, O, ProcessContext> pb, StripeSettings settings) {
        pb.compute("Check the report against the record", (metadata, ctx) -> decide(ctx, settings))
            .step("Post the top-up", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
            .compute("Record the outcome", (metadata, ctx) -> record(ctx))
            .step("Ask for the pending rewards to be paid", PublishEvent.<ProcessContext>when(
                TopUpCrediting::credited, QbWallets.SETTLE_EVENT,
                ctx -> Map.of("sponsorId", first(ctx, TOPUP).get("sponsorId"))))
            .step("Announce the top-up", PublishEvent.<ProcessContext>when(TopUpCrediting::credited,
                QbTopUps.PAID_EVENT, ctx -> {
                    EntityInstance topUp = first(ctx, TOPUP);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("topUpId", String.valueOf(topUp.id()));
                    payload.put("sponsorId", topUp.get("sponsorId"));
                    payload.put("amount", QbTopUps.money(topUp.get("amount")));
                    return payload;
                }))
            .step("Ask Stripe for the fee", StartExternalJob.<ProcessContext>of(TopUpJobs.PAYMENT_FEE,
                    ctx -> new TopUpJobs.FeeInput(String.valueOf(first(ctx, TOPUP).id()),
                        report(ctx).session().paymentIntentId()))
                .reference(ctx -> String.valueOf(first(ctx, TOPUP).id()))
                .when(TopUpCrediting::credited))
            .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> verdict(ctx).outcome()
                == TopUpChecks.Outcome.CHECK, ctx -> {
                    EntityInstance topUp = first(ctx, TOPUP);
                    StripeObjects.CheckoutSession session = report(ctx).session();
                    return TopUpTasks.topUp(verdict(ctx).reason(), topUp == null ? null : String.valueOf(topUp.id()),
                        session == null ? null : session.amountTotal(), session == null ? null : session.currency(),
                        report(ctx).sourceKey(), report(ctx).inboundEventId());
                }));
    }

    static void decide(ProcessContext ctx, StripeSettings settings) {
        Report report = report(ctx);
        EntityInstance topUp = first(ctx, TOPUP);
        TopUpChecks.TopUpView view = QbTopUps.view(topUp);
        TopUpChecks.Verdict verdict = switch (report.mode()) {
            case PAID -> TopUpChecks.paid(view, report.session(), settings.livemode(), report.eventLivemode());
            case EXPIRED -> TopUpChecks.expired(view, report.session(), settings.livemode(), report.eventLivemode());
            case NONE -> new TopUpChecks.Verdict(TopUpChecks.Outcome.ALREADY, null);
        };
        ctx.put(VERDICT, verdict);
        if (verdict.outcome() == TopUpChecks.Outcome.CREDIT) {
            BigDecimal amount = view.amount();
            ctx.put(POST_INPUT, new LedgerProcesses.PostInput(report.bookingTime(), "Top-up",
                reference(report.session().paymentIntentId()), List.of(
                    new LedgerProcesses.Line(QbLedger.STRIPE_BALANCE, Direction.DEBIT, amount),
                    new LedgerProcesses.Line(QbLedger.SPONSOR_PREPAYMENTS, Direction.CREDIT, amount, null,
                        Map.of(QbLedger.PARTY, view.sponsorId()))), QbTopUps.TOPUP, view.topUpId()));
        }
    }

    static void record(ProcessContext ctx) {
        TopUpChecks.Verdict verdict = verdict(ctx);
        EntityInstance topUp = first(ctx, TOPUP);
        if (topUp == null) {
            return;
        }
        Report report = report(ctx);
        StripeObjects.CheckoutSession session = report.session();
        Map<String, Object> values = new LinkedHashMap<>();
        switch (verdict.outcome()) {
            case CREDIT -> {
                BigDecimal amount = QbTopUps.money(topUp.get("amount"));
                QbWallets.change(ctx, first(ctx, WALLET), QbWallets.SPONSOR, topUp.get("sponsorId"), amount, 0,
                    BigDecimal.ZERO);
                values.put("status", TopUpStatus.PAID);
                values.put("checkoutSessionId", session.id());
                values.put("paymentIntentId", session.paymentIntentId());
                values.put("livemode", session.livemode());
                values.put("paidAt", report.bookingTime());
                values.put("confirmedBy", report.confirmedBy());
                values.put("ledgerTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
                values.put("presentmentCurrency", StripeIds.valid(StripeIds.CURRENCY, session.presentmentCurrency()));
                values.put("presentmentAmount", presentment(session));
                values.put("fxRate", rate(session.fxRate()));
                values.put("checkReason", null);
            }
            case EXPIRE -> {
                values.put("status", TopUpStatus.EXPIRED);
                values.put("checkoutSessionId", session.id());
            }
            case CHECK -> values.put("checkReason", verdict.reason());
            case ALREADY -> {
                return;
            }
            default -> throw new IllegalStateException("Unexpected verdict " + verdict);
        }
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
    }

    /** The outcome of the steps, for a process's output. */
    static Outcome outcome(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        TopUpChecks.Verdict verdict = ctx.contains(VERDICT) ? verdict(ctx) : null;
        String outcome = verdict == null ? "NONE" : switch (verdict.outcome()) {
            case CREDIT -> "CREDITED";
            case EXPIRE -> "EXPIRED";
            case ALREADY -> report(ctx).mode() == Mode.NONE ? "NONE" : "ALREADY";
            case CHECK -> "CHECK";
        };
        return new Outcome(topUp == null ? null : String.valueOf(topUp.id()), outcome,
            verdict == null ? null : verdict.reason());
    }

    private static boolean credited(ProcessContext ctx) {
        return ctx.contains(POSTING);
    }

    /** The presentment amount when it is a whole number of the currency's minor unit; null otherwise. */
    private static BigDecimal presentment(StripeObjects.CheckoutSession session) {
        BigDecimal amount = session.presentmentAmount();
        return amount != null && amount.signum() >= 0 && amount.stripTrailingZeros().scale() <= 0
            && amount.precision() <= 19 && session.presentmentCurrency() != null ? amount : null;
    }

    /** The rate when it fits the column (numeric(20, 10)); null otherwise. */
    private static BigDecimal rate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            return null;
        }
        BigDecimal scaled = rate.setScale(10, java.math.RoundingMode.HALF_UP);
        return scaled.precision() - scaled.scale() <= 10 ? scaled : null;
    }

    /** A Stripe id as a posting's reference (at most 100 characters); null when longer. */
    static String reference(String stripeId) {
        return stripeId != null && stripeId.length() <= 100 ? stripeId : null;
    }

    static Report report(ProcessContext ctx) {
        return ctx.get(REPORT, Report.class);
    }

    static TopUpChecks.Verdict verdict(ProcessContext ctx) {
        return ctx.get(VERDICT, TopUpChecks.Verdict.class);
    }

    static EntityQuery byId(String topUpId) {
        List<Object> ids = new ArrayList<>();
        if (StripeIds.is(StripeIds.TOPUP, topUpId)) {
            ids.add(topUpId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("topUpId", ids)).limit(1).build();
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    @SuppressWarnings("unchecked")
    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(key);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private TopUpCrediting() {}
}

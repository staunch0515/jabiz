package com.jabiz.quizbuks.wallet;

import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.task.CreateTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.wallet.TopUpCrediting.first;

/**
 * Payouts of the platform's Stripe balance to its bank (docs/quizbuks/plans/Q6-payments.md, D-Q6-13), so that account
 * 1110 follows Stripe's balance: {@value #PAID} ({@code payout.paid}) records the payout once and posts debit 1120 /
 * credit 1110; {@value #FAILED} ({@code payout.failed}) reverses a recorded one, or records it failed so that a late
 * {@code payout.paid} changes nothing. Each payout is serialized by its own lock and recorded once (a unique id).
 */
public final class StripePayouts {

    public static final String PAID = "QB_STRIPE_PAYOUT_RECORD";
    public static final String FAILED = "QB_STRIPE_PAYOUT_FAILED";
    public static final String PAID_CONSUMER = "qb.stripe-payout";
    public static final String FAILED_CONSUMER = "qb.stripe-payout-failed";

    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_FAILED = "FAILED";

    /** @param outcome {@code RECORDED}, {@code REVERSED}, {@code NOTHING} or {@code CHECK} */
    public record PayoutOutput(String payoutId, String outcome, String reason) {}

    static final String INPUT = "input";
    static final String EVENT = "event";
    static final String PAYOUT = "payout";
    static final String ROW = "row";
    static final String ROW_AGAIN = "rowAgain";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String REVERSE_INPUT = "reverseInput";
    static final String REVERSAL = "reversal";
    static final String OUTCOME = "outcome";
    static final String REASON = "reason";

    public static ProcessDefinition<TopUpEvents.InboundRef, PayoutOutput, ProcessContext> paid(StripeSettings stripe) {
        return consumer(PAID, StripeService.PAYOUT_PAID, "Records a payout of the Stripe balance to the bank.", stripe,
            true);
    }

    public static ProcessDefinition<TopUpEvents.InboundRef, PayoutOutput, ProcessContext> failed(
        StripeSettings stripe) {
        return consumer(FAILED, StripeService.PAYOUT_FAILED, "Reverses a payout of the Stripe balance that failed.",
            stripe, false);
    }

    private static ProcessDefinition<TopUpEvents.InboundRef, PayoutOutput, ProcessContext> consumer(String name,
        String eventType, String description, StripeSettings stripe, boolean paid) {
        return ProcessDefinition.define(name, 1, TopUpEvents.InboundRef.class, PayoutOutput.class,
            ProcessContext.class, pb -> pb
                .description(description)
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new PayoutOutput(payout(ctx) == null ? null : payout(ctx).id(),
                    ctx.contains(OUTCOME) ? (String) ctx.get(OUTCOME) : "NOTHING", (String) ctx.get(REASON)))
                .step("Load the event", LoadInboundEvent.of(StripeService.PROVIDER, eventType,
                    ctx -> ctx.get(INPUT, TopUpEvents.InboundRef.class).inboundEventId(), EVENT))
                .compute("Read the payout", (metadata, ctx) -> ctx.put(PAYOUT,
                    PayoutObjects.payout(event(ctx).at("data.object"))))
                .step("Lock the payout", HoldLock.<ProcessContext>exclusive(ctx -> {
                    String id = StripeIds.valid(StripeIds.PAYOUT, payout(ctx).id());
                    return id == null ? null : "qb.stripe-payout:" + id;
                }))
                .step("Find the payout", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.STRIPE_PAYOUT), ctx -> byPayout(payout(ctx).id()), ROW))
                .compute("Decide", (metadata, ctx) -> decide(ctx, stripe, paid))
                .step("Save the payout", SaveChanges.now())
                .step("Read the payout again", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.STRIPE_PAYOUT), ctx -> byPayout(payout(ctx).id()), ROW_AGAIN))
                .compute("Prepare the posting", (metadata, ctx) -> {
                    EntityInstance row = first(ctx, ROW_AGAIN);
                    if (ctx.contains(POST_INPUT) && row != null) {
                        BigDecimal amount = QbPayouts.money(row.get("amount"));
                        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(TopUpEvents.happened(event(ctx)),
                            "Stripe payout", TopUpCrediting.reference(row.get("payoutId")), List.of(
                                new LedgerProcesses.Line(QbLedger.BANK, Direction.DEBIT, amount),
                                new LedgerProcesses.Line(QbLedger.STRIPE_BALANCE, Direction.CREDIT, amount)),
                            QbPayouts.STRIPE_PAYOUT, String.valueOf(row.id())));
                    }
                })
                .step("Post the payout", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                .step("Reverse the payout", CallProcess.<ProcessContext>when(ctx -> ctx.contains(REVERSE_INPUT),
                    LedgerProcesses.REVERSE, 1, ctx -> ctx.get(REVERSE_INPUT), REVERSAL))
                .compute("Record the posting", (metadata, ctx) -> {
                    EntityInstance row = first(ctx, ROW_AGAIN);
                    Map<String, Object> values = new LinkedHashMap<>();
                    if (ctx.contains(POSTING)) {
                        values.put("ledgerTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class)
                            .transactionId());
                    }
                    if (ctx.contains(REVERSAL)) {
                        values.put("reversalTransactionId", ctx.get(REVERSAL, LedgerProcesses.ReverseOutput.class)
                            .transactionId());
                    }
                    if (!values.isEmpty() && row != null) {
                        ctx.changes().update(QbPayouts.STRIPE_PAYOUT, row.id(), row.version(), values);
                    }
                })
                .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(REASON), ctx -> {
                    ReceivedEvent event = event(ctx);
                    PayoutObjects.Payout payout = payout(ctx);
                    return PayoutTasks.transfer((String) ctx.get(REASON), null, payout.amount(),
                        TopUpTasks.eventSource(event.externalEventId()), event.inboundEventId());
                })));
    }

    static void decide(ProcessContext ctx, StripeSettings stripe, boolean paid) {
        ReceivedEvent event = event(ctx);
        PayoutObjects.Payout payout = payout(ctx);
        if (!StripeIds.is(StripeIds.PAYOUT, payout.id()) || !StripeService.CURRENCY.equals(payout.currency())
            || !RefundMath.wholeAndNotNegative(payout.amount()) || payout.amount().precision() > 19
            || event.at("account") != null) {
            check(ctx, PayoutChecks.PAYOUT_MISMATCH);
            return;
        }
        if (!Boolean.valueOf(stripe.livemode()).equals(event.livemode())
            || !Boolean.valueOf(stripe.livemode()).equals(payout.livemode())) {
            check(ctx, PayoutChecks.LIVEMODE);
            return;
        }
        EntityInstance row = first(ctx, ROW);
        if (paid) {
            if (row != null) {
                // Recorded before (the event again), or failed first (events come in any order).
                ctx.put(OUTCOME, "NOTHING");
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("payoutId", payout.id());
            values.put("amount", payout.amount());
            values.put("status", STATUS_PAID);
            values.put("paidAt", TopUpEvents.happened(event));
            ctx.changes().insert(QbPayouts.STRIPE_PAYOUT, values);
            if (payout.amount().signum() > 0) {
                ctx.put(POST_INPUT, true);
            }
            ctx.put(OUTCOME, "RECORDED");
            return;
        }
        if (row == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("payoutId", payout.id());
            values.put("amount", payout.amount());
            values.put("status", STATUS_FAILED);
            ctx.changes().insert(QbPayouts.STRIPE_PAYOUT, values);
            ctx.put(OUTCOME, "RECORDED");
            return;
        }
        if (STATUS_FAILED.equals(row.get("status"))) {
            ctx.put(OUTCOME, "NOTHING");
            return;
        }
        ctx.changes().update(QbPayouts.STRIPE_PAYOUT, row.id(), row.version(), Map.of("status", STATUS_FAILED));
        if (row.get("ledgerTransactionId") != null) {
            ctx.put(REVERSE_INPUT, new LedgerProcesses.ReverseInput(row.get("ledgerTransactionId"),
                "Stripe payout failed", TopUpEvents.happened(event)));
        }
        ctx.put(OUTCOME, "REVERSED");
        ctx.put(REASON, PayoutChecks.PAYOUT_FAILED);
    }

    private static void check(ProcessContext ctx, String reason) {
        ctx.put(OUTCOME, "CHECK");
        ctx.put(REASON, reason);
    }

    private static EntityQuery byPayout(String payoutId) {
        List<Object> ids = new ArrayList<>();
        if (StripeIds.is(StripeIds.PAYOUT, payoutId)) {
            ids.add(payoutId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("payoutId", ids)).limit(1).build();
    }

    private static PayoutObjects.Payout payout(ProcessContext ctx) {
        return ctx.get(PAYOUT, PayoutObjects.Payout.class);
    }

    private static ReceivedEvent event(ProcessContext ctx) {
        return ctx.get(EVENT, ReceivedEvent.class);
    }

    private StripePayouts() {}
}

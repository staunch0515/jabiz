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
import com.jabiz.runtime.task.CreateTask;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refunds and disputes of a top-up's payment (docs/quizbuks/plans/Q6-payments.md, D-Q6-6). QuizBuks refunds nothing
 * itself: these happen in Stripe's dashboard, and the consumers keep the wallet and the ledger true to them.
 * <ul>
 *   <li>{@value #REFUNDED} ({@code charge.refunded}): takes back this time's part of the running total refunded
 *       ({@link RefundMath}): debit 2110 (the sponsor) / credit 1110, the wallet lowered. When the balance does not
 *       cover it, nothing is posted, the shortfall is recorded and a person is asked.</li>
 *   <li>{@value #DISPUTE_OPENED} ({@code charge.dispute.created}): records the dispute and asks a person to answer it
 *       at Stripe.</li>
 *   <li>{@value #DISPUTE_CLOSED} ({@code charge.dispute.closed}): a lost dispute is taken back as a refund is (once:
 *       the dispute's outcome is recorded); a won one is only recorded.</li>
 * </ul>
 * One process per event type, since a step loads events of one type only (platform gap 18). The top-up is found by
 * the payment (unique), and only a paid top-up has one: a refund of a payment whose confirmation is not processed yet
 * (events come in any order) fails and is retried for {@link WalletLimits#UNKNOWN_PAYMENT_GRACE}, then becomes a
 * task. Everything else Stripe says is checked against the record, as for the top-up itself.
 */
public final class TopUpClawback {

    public static final String REFUNDED = "QB_TOPUP_REFUNDED";
    public static final String DISPUTE_OPENED = "QB_TOPUP_DISPUTE_OPENED";
    public static final String DISPUTE_CLOSED = "QB_TOPUP_DISPUTE_CLOSED";
    public static final String REFUND_CONSUMER = "qb.topup-refund";
    public static final String DISPUTE_OPEN_CONSUMER = "qb.topup-dispute-open";
    public static final String DISPUTE_CLOSE_CONSUMER = "qb.topup-dispute-close";

    /** @param outcome {@code CLAWED_BACK}, {@code SHORTFALL}, {@code RECORDED}, {@code NOTHING} or {@code CHECK} */
    public record ClawbackOutput(String topUpId, String outcome, String reason, BigDecimal amount) {}

    private enum Kind { REFUND, DISPUTE_OPENED, DISPUTE_CLOSED }

    static final String INPUT = "input";
    static final String EVENT = "event";
    static final String PAYMENT = "payment";
    static final String FOUND = "found";
    static final String TOPUP = "topUp";
    static final String WALLET = "wallet";
    static final String OUTCOME = "outcome";
    static final String REASON = "reason";
    static final String AMOUNT = "amount";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String VALUES = "values";

    public static ProcessDefinition<TopUpEvents.InboundRef, ClawbackOutput, ProcessContext> refunded(
        StripeSettings settings) {
        return consumer(REFUNDED, StripeService.CHARGE_REFUNDED, Kind.REFUND,
            "Takes a refund of a top-up's payment back from the sponsor's wallet.", settings);
    }

    public static ProcessDefinition<TopUpEvents.InboundRef, ClawbackOutput, ProcessContext> disputeOpened(
        StripeSettings settings) {
        return consumer(DISPUTE_OPENED, StripeService.DISPUTE_CREATED, Kind.DISPUTE_OPENED,
            "Records a dispute of a top-up's payment and asks a person to answer it.", settings);
    }

    public static ProcessDefinition<TopUpEvents.InboundRef, ClawbackOutput, ProcessContext> disputeClosed(
        StripeSettings settings) {
        return consumer(DISPUTE_CLOSED, StripeService.DISPUTE_CLOSED, Kind.DISPUTE_CLOSED,
            "Records a dispute's outcome and takes a lost one back from the sponsor's wallet.", settings);
    }

    private static ProcessDefinition<TopUpEvents.InboundRef, ClawbackOutput, ProcessContext> consumer(String name,
        String eventType, Kind kind, String description, StripeSettings settings) {
        return ProcessDefinition.define(name, 1, TopUpEvents.InboundRef.class, ClawbackOutput.class,
            ProcessContext.class, pb -> pb
                .description(description)
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> {
                    EntityInstance topUp = TopUpCrediting.first(ctx, TOPUP);
                    return new ClawbackOutput(topUp == null ? null : String.valueOf(topUp.id()),
                        (String) ctx.get(OUTCOME), (String) ctx.get(REASON), (BigDecimal) ctx.get(AMOUNT));
                })
                .step("Load the event", LoadInboundEvent.of(StripeService.PROVIDER, eventType,
                    ctx -> ctx.get(INPUT, TopUpEvents.InboundRef.class).inboundEventId(), EVENT))
                .compute("Read the payment", (metadata, ctx) -> ctx.put(PAYMENT, kind == Kind.REFUND
                    ? StripeObjects.charge(event(ctx).at("data.object"))
                    : StripeObjects.dispute(event(ctx).at("data.object"))))
                .step("Find the top-up", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(QbTopUps.TOPUP),
                    ctx -> byPayment(paymentIntentId(ctx)), FOUND))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> {
                    EntityInstance found = TopUpCrediting.first(ctx, FOUND);
                    return found == null ? null : QbWallets.lockName(QbWallets.SPONSOR, found.get("sponsorId"));
                }))
                .step("Read the top-up again", QueryEntities.<ProcessContext>of(
                    QbTopUps.defaultDataset(QbTopUps.TOPUP), ctx -> TopUpCrediting.byId(
                        TopUpCrediting.first(ctx, FOUND) == null ? null
                            : String.valueOf(TopUpCrediting.first(ctx, FOUND).id())), TOPUP))
                .step("Read the wallet", QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.SPONSOR, TopUpCrediting.first(ctx, TOPUP) == null ? null
                        : TopUpCrediting.first(ctx, TOPUP).get("sponsorId")), WALLET))
                .compute("Decide", (metadata, ctx) -> decide(ctx, kind, settings))
                .step("Post the clawback", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                .compute("Record it", (metadata, ctx) -> record(ctx))
                .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(REASON), ctx -> {
                    EntityInstance topUp = TopUpCrediting.first(ctx, TOPUP);
                    ReceivedEvent event = event(ctx);
                    Object payment = ctx.get(PAYMENT);
                    BigDecimal amount = payment instanceof StripeObjects.Charge c ? c.amountRefunded()
                        : ((StripeObjects.Dispute) payment).amount();
                    String currency = payment instanceof StripeObjects.Charge c ? c.currency()
                        : ((StripeObjects.Dispute) payment).currency();
                    return TopUpTasks.refund((String) ctx.get(REASON), topUp == null ? null
                        : String.valueOf(topUp.id()), amount, currency,
                        TopUpTasks.eventSource(event.externalEventId()), event.inboundEventId());
                })));
    }

    static void decide(ProcessContext ctx, Kind kind, StripeSettings settings) {
        ReceivedEvent event = event(ctx);
        EntityInstance topUp = TopUpCrediting.first(ctx, TOPUP);
        Object payment = ctx.get(PAYMENT);
        Boolean objectLivemode = payment instanceof StripeObjects.Charge c ? c.livemode()
            : ((StripeObjects.Dispute) payment).livemode();
        if (topUp == null) {
            Instant received = event.receivedTime() == null ? ctx.opTime() : event.receivedTime();
            if (ctx.opTime().isBefore(received.plus(WalletLimits.UNKNOWN_PAYMENT_GRACE))) {
                // The top-up's confirmation may not be processed yet: the outbox tries this event again.
                throw new IllegalStateException("No paid top-up has this payment yet");
            }
            check(ctx, RefundMath.UNKNOWN_PAYMENT);
            return;
        }
        if (!Boolean.valueOf(settings.livemode()).equals(event.livemode())
            || !Boolean.valueOf(settings.livemode()).equals(objectLivemode)) {
            check(ctx, RefundMath.LIVEMODE);
            return;
        }
        BigDecimal amount = QbTopUps.money(topUp.get("amount"));
        BigDecimal refunded = QbTopUps.money(topUp.get("refundedAmount"));
        BigDecimal clawedBack = QbTopUps.money(topUp.get("disputeClawback"));
        BigDecimal balance = QbWallets.balance(TopUpCrediting.first(ctx, WALLET));
        Map<String, Object> values = new LinkedHashMap<>();
        switch (kind) {
            case REFUND -> {
                StripeObjects.Charge charge = (StripeObjects.Charge) payment;
                if (!StripeService.CURRENCY.equals(charge.currency())) {
                    check(ctx, RefundMath.CURRENCY);
                    return;
                }
                RefundMath.Plan plan = RefundMath.refund(amount, refunded, clawedBack, charge.amountRefunded(),
                    balance);
                apply(ctx, plan, topUp, "Refund", charge.id(), event);
                if (plan.kind() == RefundMath.Kind.CLAW_BACK) {
                    values.put("refundedAmount", refunded.add(plan.amount()));
                    values.put("refundShortfall", BigDecimal.ZERO);
                } else if (plan.kind() == RefundMath.Kind.SHORTFALL) {
                    values.put("refundShortfall", plan.amount());
                }
            }
            case DISPUTE_OPENED -> {
                StripeObjects.Dispute dispute = (StripeObjects.Dispute) payment;
                if (!sameDispute(topUp, dispute)) {
                    check(ctx, RefundMath.DISPUTE_MISMATCH);
                    return;
                }
                if (topUp.get("disputeStatus") != null) {
                    // Its outcome came first (events come in any order), or the event again.
                    ctx.put(OUTCOME, "NOTHING");
                    return;
                }
                values.put("disputeId", dispute.id());
                values.put("disputeStatus", TopUpStatus.DISPUTE_OPEN);
                values.put("disputeAmount", wholeOrNull(dispute.amount(), dispute.currency()));
                ctx.put(OUTCOME, "RECORDED");
                ctx.put(REASON, RefundMath.DISPUTE_OPENED);
            }
            case DISPUTE_CLOSED -> {
                StripeObjects.Dispute dispute = (StripeObjects.Dispute) payment;
                String status = topUp.get("disputeStatus");
                if (!sameDispute(topUp, dispute)) {
                    check(ctx, RefundMath.DISPUTE_MISMATCH);
                    return;
                }
                if (status != null && !TopUpStatus.DISPUTE_OPEN.equals(status)) {
                    ctx.put(OUTCOME, "NOTHING");
                    return;
                }
                values.put("disputeId", dispute.id());
                values.put("disputeAmount", wholeOrNull(dispute.amount(), dispute.currency()));
                switch (String.valueOf(dispute.status())) {
                    case "lost" -> {
                        values.put("disputeStatus", TopUpStatus.DISPUTE_LOST);
                        if (!StripeService.CURRENCY.equals(dispute.currency())) {
                            values.put("checkReason", RefundMath.CURRENCY);
                            ctx.put(OUTCOME, "CHECK");
                            ctx.put(REASON, RefundMath.CURRENCY);
                            break;
                        }
                        RefundMath.Plan plan = RefundMath.disputeLost(amount, refunded, clawedBack, dispute.amount(),
                            balance);
                        if (plan.kind() == RefundMath.Kind.CHECK) {
                            // The outcome is still recorded: it does not change, whatever a person decides.
                            values.put("checkReason", plan.reason());
                            ctx.put(OUTCOME, "CHECK");
                            ctx.put(REASON, plan.reason());
                            break;
                        }
                        apply(ctx, plan, topUp, "Lost dispute", dispute.id(), event);
                        if (plan.kind() == RefundMath.Kind.CLAW_BACK) {
                            values.put("disputeClawback", clawedBack.add(plan.amount()));
                        }
                    }
                    case "won" -> {
                        values.put("disputeStatus", TopUpStatus.DISPUTE_WON);
                        ctx.put(OUTCOME, "RECORDED");
                    }
                    case "warning_closed" -> {
                        values.put("disputeStatus", TopUpStatus.DISPUTE_CLOSED);
                        ctx.put(OUTCOME, "RECORDED");
                    }
                    default -> {
                        check(ctx, RefundMath.DISPUTE_MISMATCH);
                        return;
                    }
                }
            }
            default -> throw new IllegalStateException("Unexpected kind " + kind);
        }
        if (ctx.contains(REASON) && !values.containsKey("checkReason")) {
            values.put("checkReason", ctx.get(REASON));
        }
        ctx.put(VALUES, values);
    }

    /** Acts on a plan: the posting of a clawback, or the reason of a shortfall or a check. */
    private static void apply(ProcessContext ctx, RefundMath.Plan plan, EntityInstance topUp, String description,
        String stripeId, ReceivedEvent event) {
        switch (plan.kind()) {
            case NOTHING -> ctx.put(OUTCOME, "NOTHING");
            case CHECK -> {
                ctx.put(OUTCOME, "CHECK");
                ctx.put(REASON, plan.reason());
            }
            case SHORTFALL -> {
                ctx.put(OUTCOME, "SHORTFALL");
                ctx.put(REASON, plan.reason());
                ctx.put(AMOUNT, plan.amount());
            }
            case CLAW_BACK -> {
                String sponsorId = topUp.get("sponsorId");
                ctx.put(OUTCOME, "CLAWED_BACK");
                ctx.put(AMOUNT, plan.amount());
                ctx.put(POST_INPUT, new LedgerProcesses.PostInput(TopUpEvents.happened(event), description,
                    TopUpCrediting.reference(stripeId), List.of(
                        new LedgerProcesses.Line(QbLedger.SPONSOR_PREPAYMENTS, Direction.DEBIT, plan.amount(), null,
                            Map.of(QbLedger.PARTY, sponsorId)),
                        new LedgerProcesses.Line(QbLedger.STRIPE_BALANCE, Direction.CREDIT, plan.amount())),
                    QbTopUps.TOPUP, String.valueOf(topUp.id())));
            }
            default -> throw new IllegalStateException("Unexpected plan " + plan);
        }
    }

    @SuppressWarnings("unchecked")
    static void record(ProcessContext ctx) {
        EntityInstance topUp = TopUpCrediting.first(ctx, TOPUP);
        Map<String, Object> values = (Map<String, Object>) ctx.get(VALUES);
        if (topUp == null || values == null || values.isEmpty()) {
            return;
        }
        if (ctx.contains(POSTING)) {
            BigDecimal amount = (BigDecimal) ctx.get(AMOUNT);
            QbWallets.change(ctx, TopUpCrediting.first(ctx, WALLET), QbWallets.SPONSOR, topUp.get("sponsorId"),
                amount.negate(), 0, BigDecimal.ZERO);
        }
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
    }

    private static void check(ProcessContext ctx, String reason) {
        ctx.put(OUTCOME, "CHECK");
        ctx.put(REASON, reason);
        EntityInstance topUp = TopUpCrediting.first(ctx, TOPUP);
        if (topUp != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("checkReason", reason);
            ctx.put(VALUES, values);
        }
    }

    /** The dispute is the top-up's only one, or the top-up has none yet. */
    private static boolean sameDispute(EntityInstance topUp, StripeObjects.Dispute dispute) {
        Object known = topUp.get("disputeId");
        return StripeIds.is(StripeIds.DISPUTE, dispute.id()) && (known == null || known.equals(dispute.id()));
    }

    private static BigDecimal wholeOrNull(BigDecimal amount, String currency) {
        return StripeService.CURRENCY.equals(currency) && RefundMath.wholeAndNotNegative(amount)
            && amount.precision() <= 19 ? amount : null;
    }

    private static String paymentIntentId(ProcessContext ctx) {
        Object payment = ctx.get(PAYMENT);
        String id = payment instanceof StripeObjects.Charge c ? c.paymentIntentId()
            : ((StripeObjects.Dispute) payment).paymentIntentId();
        return StripeIds.valid(StripeIds.PAYMENT_INTENT, id);
    }

    private static EntityQuery byPayment(String paymentIntentId) {
        List<Object> ids = new ArrayList<>();
        if (paymentIntentId != null) {
            ids.add(paymentIntentId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("paymentIntentId", ids)).limit(1).build();
    }

    private static ReceivedEvent event(ProcessContext ctx) {
        return ctx.get(EVENT, ReceivedEvent.class);
    }

    private TopUpClawback() {}
}

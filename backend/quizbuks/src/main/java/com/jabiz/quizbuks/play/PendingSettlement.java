package com.jabiz.quizbuks.play;

import com.jabiz.event.DomainEvent;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.play.PlaySteps.INPUT;
import static com.jabiz.quizbuks.play.PlaySteps.first;
import static com.jabiz.quizbuks.play.PlaySteps.list;

/**
 * Paying a sponsor's pending rewards in the order they were submitted (5.3-8; docs/quizbuks/plans/Q5-answering.md,
 * D-Q5-5), under the sponsor's wallet lock only:
 * <ul>
 *   <li>{@code QB_PENDING_SETTLE}: reads the first {@link PlayLimits#SETTLE_BATCH} pending rewards and pays as many as
 *       the balance covers, one {@code QB_PENDING_PAY} each, stopping at the first it cannot pay (never skipping it).
 *       When the next one could be paid too, it publishes {@code qb.pending-settle.more} so that the next batch runs
 *       in a transaction of its own; once none is left, {@code qb.sponsor.funds-restored} (the underfunded
 *       publications go back on, without the wallet's lock);</li>
 *   <li>{@code QB_PENDING_PAY}: pays one reward, if it is still the first pending and the balance covers it: the
 *       posting with the commission in effect now, the attempt paid, the wallet's balance and counts, an event for
 *       the taker's message. It takes the wallet's lock again (the same transaction holds it already when it runs as
 *       {@code QB_PENDING_SETTLE}'s sub-process), so it is safe on its own too.</li>
 * </ul>
 * Run as the system (an event, a top-up); their permission is granted to no role.
 */
public final class PendingSettlement {

    public static final String SETTLE = QbWallets.PENDING_SETTLE;
    public static final String PAY = "QB_PENDING_PAY";

    /** The consumer of {@code qb.pending-settle.more}: the next batch. */
    public static final String SUBSCRIPTION = "qb.pending-settle";
    public static final String MORE_EVENT = "qb.pending-settle.more";

    /**
     * @param paid    the rewards paid by this run
     * @param pending the rewards still pending after it
     */
    public record SettleOutput(String sponsorId, int paid, long pending) {}

    public record PayInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String attemptId) {}

    /** @param transactionId the posting; null when it was not paid (no longer first, or the balance is short) */
    public record PayOutput(String attemptId, boolean paid, String transactionId) {}

    static final String WALLET = "play.wallet";
    static final String QUEUE = "play.queue";
    static final String PAYS = "play.pays";
    static final String PAID = "play.paid";
    static final String WALLET_AFTER = "play.walletAfter";
    static final String HEAD = "play.head";
    static final String PARAMS = "play.params";
    static final String POST_INPUT = "play.postInput";
    static final String POSTING = "play.posting";

    /** The pending rewards of a sponsor in their order: by submission, then by attempt. */
    static EntityQuery queue(Object sponsorId, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("sponsorId", PlaySteps.ids(sponsorId)),
                new QueryPredicate.Eq("rewardStatus", RewardStatus.PENDING))))
            .orderBy("submittedAt", true).orderBy("attemptId", true).limit(limit).build();
    }

    // ---- settle ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<QbWallets.SponsorRef, SettleOutput, ProcessContext> settle() {
        return ProcessDefinition.define(SETTLE, 1, QbWallets.SponsorRef.class, SettleOutput.class,
            ProcessContext.class, pb -> pb
                .description("Pays a sponsor's pending rewards in the order they were submitted, as far as the "
                    + "balance goes.")
                .permissions(QbPermissions.PLAY_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new SettleOutput(sponsorId(ctx), list(ctx, PAYS).size(),
                    QbWallets.pendingCount(first(ctx, WALLET_AFTER))))
                .step("Lock the wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, sponsorId(ctx))))
                .step("Load the wallet", QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.SPONSOR, sponsorId(ctx)), WALLET))
                // One more than a batch: whether more could follow.
                .step("Load the pending rewards", QueryEntities.<ProcessContext>of(
                    QbPlay.defaultDataset(QbPlay.ATTEMPT), ctx -> queue(sponsorId(ctx), PlayLimits.SETTLE_BATCH),
                    QUEUE))
                .compute("Choose what the balance pays", (metadata, ctx) -> choose(ctx))
                .step("Pay each", CallProcess.<ProcessContext>forEach(PAY, 1, ctx -> list(ctx, PAYS), PAID))
                .step("Load the wallet again", QueryEntities.<ProcessContext>of(
                    QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(QbWallets.SPONSOR,
                        sponsorId(ctx)), WALLET_AFTER))
                .step("Load the next pending reward", QueryEntities.<ProcessContext>of(
                    QbPlay.defaultDataset(QbPlay.ATTEMPT), ctx -> queue(sponsorId(ctx), 1), HEAD))
                .step("Continue in another transaction", PublishEvent.<ProcessContext>when(PendingSettlement::more,
                    MORE_EVENT, PendingSettlement::payload))
                .step("Announce the funds restored", PublishEvent.<ProcessContext>when(PendingSettlement::restored,
                    QbWallets.FUNDS_RESTORED_EVENT, PendingSettlement::payload)));
    }

    /** The rewards to pay, in order, as long as the balance covers each; stops at the first it does not. */
    static void choose(ProcessContext ctx) {
        BigDecimal balance = QbWallets.balance(first(ctx, WALLET));
        List<PayInput> pays = new ArrayList<>();
        for (EntityInstance attempt : list(ctx, QUEUE)) {
            BigDecimal reward = PlaySteps.money(attempt.get("reward"));
            if (balance.compareTo(reward) < 0) {
                break;
            }
            balance = balance.subtract(reward);
            pays.add(new PayInput(sponsorId(ctx), String.valueOf(attempt.id())));
        }
        ctx.put(PAYS, List.copyOf(pays));
    }

    /** More are pending and the next one can be paid: another batch, in its own transaction. */
    private static boolean more(ProcessContext ctx) {
        EntityInstance head = first(ctx, HEAD);
        return !ctx.hasViolations() && head != null
            && QbWallets.balance(first(ctx, WALLET_AFTER)).compareTo(PlaySteps.money(head.get("reward"))) >= 0;
    }

    /**
     * Nothing is pending after this run: the sponsor's underfunded publications may go back on. Published whenever
     * the queue is empty, not only when this run emptied it, so that a publication left underfunded by a payment made
     * some other way (a direct {@code QB_PENDING_PAY}) goes back on with the next top-up; the consumer finds nothing to
     * do otherwise.
     */
    private static boolean restored(ProcessContext ctx) {
        return !ctx.hasViolations() && first(ctx, HEAD) == null;
    }

    private static Map<String, Object> payload(ProcessContext ctx) {
        return Map.of("sponsorId", sponsorId(ctx));
    }

    private static String sponsorId(ProcessContext ctx) {
        return ctx.get(INPUT, QbWallets.SponsorRef.class).sponsorId();
    }

    /** The input of {@code QB_PENDING_SETTLE} from {@code qb.pending-settle.more}. */
    static QbWallets.SponsorRef input(DomainEvent event) {
        Object sponsorId = event.payload().get("sponsorId");
        return new QbWallets.SponsorRef(sponsorId == null ? null : String.valueOf(sponsorId));
    }

    // ---- pay -------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PayInput, PayOutput, ProcessContext> pay() {
        return ProcessDefinition.define(PAY, 1, PayInput.class, PayOutput.class, ProcessContext.class, pb -> pb
            .description("Pays one pending reward when it is the sponsor's first pending and the balance covers it.")
            .permissions(QbPermissions.PLAY_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new PayOutput(payInput(ctx).attemptId(), ctx.contains(POSTING),
                ctx.contains(POSTING) ? ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId() : null))
            .step("Lock the wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, payInput(ctx).sponsorId())))
            .step("Load the wallet", QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET),
                ctx -> QbWallets.query(QbWallets.SPONSOR, payInput(ctx).sponsorId()), WALLET))
            .step("Load the first pending reward", QueryEntities.<ProcessContext>of(
                QbPlay.defaultDataset(QbPlay.ATTEMPT), ctx -> queue(payInput(ctx).sponsorId(), 1), HEAD))
            .step("Load the commission rate", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.CREATOR_FEE_RATE))
            .compute("Pay it", (metadata, ctx) -> payPlan(ctx))
            .step("Post the reward", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT)
                && !ctx.hasViolations(), LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
            .compute("Record the payment", (metadata, ctx) -> payRecord(ctx))
            .step("Announce the paid reward", PublishEvent.<ProcessContext>when(
                ctx -> ctx.contains(POSTING) && !ctx.hasViolations(), AttemptFinishing.SETTLED_EVENT, ctx -> {
                    EntityInstance attempt = first(ctx, HEAD);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("attemptId", String.valueOf(attempt.id()));
                    payload.put("userId", attempt.get("userId"));
                    payload.put("publicationId", String.valueOf((Object) attempt.get("publicationId")));
                    payload.put("reward", PlaySteps.money(attempt.get("reward")));
                    return payload;
                })));
    }

    private static PayInput payInput(ProcessContext ctx) {
        return ctx.get(INPUT, PayInput.class);
    }

    static void payPlan(ProcessContext ctx) {
        PayInput input = payInput(ctx);
        EntityInstance head = first(ctx, HEAD);
        EntityInstance wallet = first(ctx, WALLET);
        if (head == null || !input.attemptId().equals(String.valueOf(head.id()))) {
            return;
        }
        BigDecimal reward = PlaySteps.money(head.get("reward"));
        if (QbWallets.balance(wallet).compareTo(reward) < 0) {
            return;
        }
        BigDecimal rate = ctx.get(PARAMS, ParamValues.class).get(QbParams.CREATOR_FEE_RATE, BigDecimal.class);
        if (!RewardSplit.validRate(rate)) {
            ctx.reject(AttemptFinishing.feeRateInvalid(rate));
            return;
        }
        RewardSplit.Split split = RewardSplit.of(reward, rate);
        QbWallets.change(ctx, wallet, QbWallets.SPONSOR, input.sponsorId(), reward.negate(), -1, reward.negate());
        ctx.put("play.fee", split.fee());
        ctx.put(POST_INPUT, QbWallets.rewardPosting(ctx.opTime(), String.valueOf(head.id()), input.sponsorId(),
            head.get("userId"), String.valueOf((Object) head.get("publicationId")), reward, split.fee()));
    }

    static void payRecord(ProcessContext ctx) {
        if (!ctx.contains(POSTING)) {
            return;
        }
        EntityInstance attempt = first(ctx, HEAD);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rewardStatus", RewardStatus.PAID);
        values.put("rewardPaidAt", ctx.opTime());
        values.put("fee", ctx.get("play.fee"));
        values.put("ledgerTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
        ctx.changes().update(QbPlay.ATTEMPT, attempt.id(), attempt.version(), values);
    }

    private PendingSettlement() {}
}

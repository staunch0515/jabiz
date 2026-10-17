package com.jabiz.quizbuks.wallet;

import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The takers' wallet cache (docs/quizbuks/plans/Q6-payments.md, D-Q6-11): a taker's {@code TAKER} row of
 * {@code qb_wallet} holds account 2120 for them (their balance) and 2130 (on its way out), so that the daily transfer
 * run finds the takers above the threshold without summing the ledger. The settlement of rewards does not take the
 * taker's lock (it already holds the publication's and the sponsor's): {@value #SYNC} recomputes the row from the
 * ledger under the taker's wallet lock, on each reward settled, each transfer changed and each resync the
 * reconciliation asks for. Recomputing rather than adding makes repeated and reordered events agree with the ledger;
 * a new subscription receives the past events, which fills the cache of the takers rewarded before.
 *
 * <p>The transfer processes, under the same lock, recompute it themselves after posting ({@link #refresh}). The cache
 * is only a selection aid: what a transfer takes is read from the ledger.
 */
public final class TakerWallets {

    public static final String SYNC = "QB_TAKER_WALLET_SYNC";
    public static final String BALANCE_TEMPLATE = "qb.system.taker-balance";
    /** The balance and the rewards held back from transfers (Q10b, review M3), for the transfer processes. */
    public static final String TRANSFERABLE_TEMPLATE = "qb.wallet.taker-transferable";
    /** Event: recompute a taker's wallet ({@code {userId}}), asked for by the reconciliation. */
    public static final String RESYNC_EVENT = "qb.taker-wallet.resync";

    public static final String REWARD_CONSUMER = "qb.taker-wallet";
    public static final String TRANSFER_CONSUMER = "qb.taker-wallet-transfer";
    public static final String RESYNC_CONSUMER = "qb.taker-wallet-resync";

    public record TakerRef(@NotBlank @Size(max = 128) String userId) {}

    /** @param balance what the wallet says after the process */
    public record SyncOutput(String userId, BigDecimal balance, BigDecimal pendingOut) {}

    static final String INPUT = "input";
    static final String WALLET = "taker.wallet";
    static final String BALANCES = "taker.balances";

    public static ProcessDefinition<TakerRef, SyncOutput, ProcessContext> sync() {
        return ProcessDefinition.define(SYNC, 1, TakerRef.class, SyncOutput.class, ProcessContext.class, pb -> pb
            .description("Recomputes a taker's wallet from the ledger.")
            .permissions(QbPermissions.PAYOUT_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> {
                Map<String, Object> row = balances(ctx);
                return new SyncOutput(ctx.get(INPUT, TakerRef.class).userId(), money(row.get("available")),
                    money(row.get("inTransfer")));
            })
            .step("Lock the wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.TAKER, ctx.get(INPUT, TakerRef.class).userId())))
            .steps(b -> load(b, ctx -> ctx.get(INPUT, TakerRef.class).userId()))
            .steps(b -> refresh(b, ctx -> ctx.get(INPUT, TakerRef.class).userId(), ctx -> true)));
    }

    /**
     * The resync the reconciliation asks for ({@value #RESYNC_EVENT}, {@code {userIds}}): {@value #SYNC} for each
     * taker, in order. Each takes only its own taker's lock, in the sorted order the reconciliation gives, so they
     * cannot wait for each other in a circle.
     */
    public static ProcessDefinition<ResyncInput, ResyncOutput, ProcessContext> resync() {
        return ProcessDefinition.define(RESYNC, 1, ResyncInput.class, ResyncOutput.class, ProcessContext.class,
            pb -> pb
                .description("Recomputes the wallets of the takers the reconciliation found behind the ledger.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new ResyncOutput(ctx.get(INPUT, ResyncInput.class).userIds().size()))
                .step("Recompute each wallet", com.jabiz.runtime.process.steps.CallProcess.<ProcessContext>forEach(
                    SYNC, 1, ctx -> ctx.get(INPUT, ResyncInput.class).userIds().stream().sorted().distinct()
                        .map(TakerRef::new).toList(), "synced")));
    }

    public static final String RESYNC = "QB_TAKER_WALLET_RESYNC";

    public record ResyncInput(@jakarta.validation.constraints.NotNull @Size(max = 1000) List<@NotBlank @Size(max = 128)
        String> userIds) {}

    public record ResyncOutput(int takers) {}

    @SuppressWarnings("unchecked")
    static ResyncInput resyncInput(DomainEvent event) {
        Object ids = event.payload().get("userIds");
        return new ResyncInput(ids instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of());
    }

    /** Reads the taker's wallet row (under their lock, taken before). */
    static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> userId) {
        pb.step("Read the taker's wallet", QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET),
            ctx -> QbWallets.query(QbWallets.TAKER, userId.apply(ctx)), WALLET));
    }

    /**
     * Reads the taker's balances from the ledger (one statement: what is committed, and what this transaction posted)
     * and writes them to the wallet read by {@link #load}, when {@code when} holds.
     */
    static <I, O> void refresh(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> userId, java.util.function.Predicate<ProcessContext> when) {
        pb.step("Read the taker's balances", RunTemplate.<ProcessContext>of(BALANCE_TEMPLATE,
                ctx -> Map.of("userId", String.valueOf(userId.apply(ctx))), BALANCES))
            .compute("Write the taker's wallet", (metadata, ctx) -> {
                if (!when.test(ctx) || userId.apply(ctx) == null) {
                    return;
                }
                Map<String, Object> row = balances(ctx);
                QbWallets.setTaker(ctx, TopUpCrediting.first(ctx, WALLET), userId.apply(ctx),
                    money(row.get("available")), money(row.get("inTransfer")));
            });
    }

    /** The ledger's balances of the taker: {@code available} (2120) and {@code inTransfer} (2130). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> balances(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(BALANCES);
        return rows == null || rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    /** The input of {@value #SYNC} from an event naming the taker ({@code userId}). */
    static TakerRef input(DomainEvent event) {
        Object userId = event.payload().get("userId");
        return new TakerRef(userId == null ? null : String.valueOf(userId));
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private TakerWallets() {}
}

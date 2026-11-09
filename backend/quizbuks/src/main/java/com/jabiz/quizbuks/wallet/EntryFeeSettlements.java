package com.jabiz.quizbuks.wallet;

import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.quizbuks.fee.CashLots;
import com.jabiz.quizbuks.fee.QbFeeAccount;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The entry fees settled into the sponsors' cash accounts (Q88b, D-Q88-11): what the starts posted to 2170 for a
 * sponsor is moved to 2160 by {@value #SETTLE}, one sponsor per transaction, under their wallet lock (the cash
 * cache changes only there, Q87a); the daily job {@value #JOB} runs it for every sponsor with a balance there. The
 * start itself never takes the sponsor's wallet lock (it is the answering hot path), which is why the fee waits
 * here. Nothing is refunded (D-Q88-13).
 */
public final class EntryFeeSettlements {

    public static final String SETTLE = "QB_ENTRY_FEE_SETTLE";
    public static final String JOB = "qb.entry-fee-settle";
    /** The sponsors with entry fees unsettled (the job's items), and one sponsor's unsettled amount. */
    public static final String DUE_TEMPLATE = "qb.system.entry-fees-due";
    public static final String UNSETTLED_TEMPLATE = "qb.system.entry-fees-unsettled";
    /** Every day at 18:07 UTC (03:07 in Tokyo); the end-to-end tests run it every minute. */
    public static final String RUN_CRON = "0 7 18 * * *";
    public static final String RUN_CRON_SETTING = "quizbuks.entry-fee.settle-cron";
    public static final String DESCRIPTION = "Entry fees settled";

    /** @param date the settlement's day (the job's scheduled day in the platform's zone), the posting's reference */
    public record SettleInput(@NotBlank String sponsorId, @NotBlank String date) {}

    public record SettleOutput(String sponsorId, BigDecimal amount) {}

    static final String INPUT = "input";
    static final String CASH = "entryFee.cash";
    static final String UNSETTLED = "entryFee.unsettled";
    static final String AMOUNT = "entryFee.amount";
    static final String POSTING = "entryFee.posting";
    static final String POSTED = "entryFee.posted";

    private EntryFeeSettlements() {}

    public static ProcessDefinition<SettleInput, SettleOutput, ProcessContext> settle() {
        return ProcessDefinition.define(SETTLE, 1, SettleInput.class, SettleOutput.class, ProcessContext.class, pb -> pb
            .description("Moves the entry fees takers paid for a sponsor's quizzes from 2170 to the sponsor's cash "
                + "account, once a day (job item).")
            .permissions(QbPermissions.WALLET_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new SettleOutput(input(ctx).sponsorId(),
                ctx.contains(AMOUNT) ? ctx.get(AMOUNT, BigDecimal.class) : BigDecimal.ZERO))
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, input(ctx).sponsorId())))
            .step("Read the cash account", QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET),
                ctx -> QbWallets.query(QbWallets.CASH, input(ctx).sponsorId()), CASH))
            // Under the lock: what this transaction sees of 2170 for the sponsor (the starts post without the lock,
            // so a fee posted meanwhile waits for the next day).
            .step("Read the unsettled fees", RunTemplate.<ProcessContext>of(UNSETTLED_TEMPLATE,
                ctx -> Map.of("sponsorId", input(ctx).sponsorId()), UNSETTLED))
            .compute("Settle them", (metadata, ctx) -> {
                BigDecimal amount = unsettled(ctx);
                if (amount.signum() <= 0) {
                    return;
                }
                EntityInstance cash = TopUpCrediting.first(ctx, CASH);
                Object walletId = QbWallets.change(ctx, cash, QbWallets.CASH, input(ctx).sponsorId(), amount, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO);
                ctx.put(AMOUNT, amount);
                // F-M1: the day's entry fees are a cash lot of their own (the record: the day's settlement).
                CashLots.add(ctx, input(ctx).sponsorId(), QbFeeAccount.OTHER, amount, DESCRIPTION_SOURCE,
                    input(ctx).date());
                ctx.put(POSTING, posting(ctx.opTime(), input(ctx).sponsorId(), String.valueOf(walletId),
                    input(ctx).date(), amount));
            })
            // The posting's source is the cash wallet row, inserted here for a sponsor without one: saved first.
            .step("Save the cash account", SaveChanges.<ProcessContext>when(ctx -> ctx.contains(POSTING)))
            .step("Post the settlement", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POSTING),
                LedgerProcesses.POST, 1, ctx -> ctx.get(POSTING), POSTED)));
    }

    /**
     * The posting of a day's settlement: 2170 (debit) to the sponsor's cash account 2160 (credit), both lines the
     * sponsor's; its source is the cash wallet row, its reference the day. The monthly report's class
     * {@code ENTRY_FEE_SETTLED}.
     */
    static LedgerProcesses.PostInput posting(Instant bookingTime, String sponsorId, String walletId, String date,
        BigDecimal amount) {
        List<LedgerProcesses.Line> lines = List.of(
            new LedgerProcesses.Line(QbLedger.ENTRY_FEES_UNSETTLED, Direction.DEBIT, amount, null,
                Map.of(QbLedger.PARTY, sponsorId)),
            new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.CREDIT, amount, null,
                Map.of(QbLedger.PARTY, sponsorId)));
        return new LedgerProcesses.PostInput(bookingTime, DESCRIPTION, date, lines, QbWallets.WALLET, walletId);
    }

    /** The record a lot of entry fees names: the day settled ({@code yyyy-MM-dd}) of {@link #SETTLE}. */
    static final String DESCRIPTION_SOURCE = "QbEntryFeeSettlement";

    static SettleInput input(ProcessContext ctx) {
        return ctx.get(INPUT, SettleInput.class);
    }

    private static BigDecimal unsettled(ProcessContext ctx) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(UNSETTLED);
        return rows == null || rows.isEmpty() ? BigDecimal.ZERO : QbWallets.money(rows.getFirst().get("unsettled"));
    }
}

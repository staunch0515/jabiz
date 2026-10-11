package com.jabiz.quizbuks.wallet;

import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * {@code QB_WALLET_DEV_CREDIT} (docs/quizbuks/plans/Q5-answering.md, D-Q5-8): before top-ups exist (Q6), credits a
 * sponsor's wallet as if a top-up had arrived, for development and tests: under the wallet's lock, posts debit 1110
 * (Stripe balance) / credit 2110 (the sponsor), raises the cached balance, and asks for the sponsor's pending rewards
 * to be paid: the event {@link QbWallets#SETTLE_EVENT}, whose consumer runs {@code QB_PENDING_SETTLE} as the system
 * after this commits (platform decision D41: that process runs only as the system, and this one is run by a person).
 * Q6's top-up confirmation runs as the system and may call it as a sub-process instead. Registered only when
 * {@code quizbuks.wallet.dev-credit.enabled=true}, which only the development profile and the tests set; its
 * permission is granted to no role, and a startup check refuses it together with a live Stripe key.
 */
public final class DevCredit {

    public static final String PROCESS = "QB_WALLET_DEV_CREDIT";
    public static final String ENABLED = "quizbuks.wallet.dev-credit.enabled";

    /**
     * @param sponsorId the sponsor's user id (the party of the ledger: a platform user)
     * @param amount    whole Kudos, at least 1
     */
    public record Input(@NotBlank @Size(max = 128) String sponsorId,
        @NotNull @DecimalMin("1") @DecimalMax("1000000000000") @Digits(integer = 13, fraction = 0) BigDecimal amount) {}

    public record Output(String sponsorId, BigDecimal balance, String transactionId) {}

    static final String INPUT = "input";
    static final String WALLET = "wallet";
    static final String POSTING = "posting";
    static final String BALANCE = "balance";

    public static ProcessDefinition<Input, Output, ProcessContext> process() {
        return ProcessDefinition.define(PROCESS, 1, Input.class, Output.class, ProcessContext.class, pb -> pb
            .description("Development only: credits a sponsor's wallet as if a top-up had arrived, then pays their "
                + "pending rewards.")
            .permissions(QbPermissions.WALLET_DEV_CREDIT)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new Output(input(ctx).sponsorId(), ctx.get(BALANCE, BigDecimal.class),
                ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId()))
            .step("Lock the wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, input(ctx).sponsorId())))
            .step("Load the wallet", QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET),
                ctx -> QbWallets.query(QbWallets.SPONSOR, input(ctx).sponsorId()), WALLET))
            .step("Post the credit", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                ctx -> new LedgerProcesses.PostInput(ctx.opTime(), "Development credit", null, List.of(
                    new LedgerProcesses.Line(QbLedger.STRIPE_BALANCE, Direction.DEBIT, input(ctx).amount()),
                    new LedgerProcesses.Line(QbLedger.SPONSOR_PREPAYMENTS, Direction.CREDIT, input(ctx).amount(),
                        null, Map.of(QbLedger.PARTY, input(ctx).sponsorId())))), POSTING))
            .compute("Raise the balance", (metadata, ctx) -> {
                EntityInstance wallet = wallet(ctx);
                QbWallets.change(ctx, wallet, QbWallets.SPONSOR, input(ctx).sponsorId(), input(ctx).amount(), 0,
                    BigDecimal.ZERO);
                ctx.put(BALANCE, QbWallets.balance(wallet).add(input(ctx).amount()));
            })
            .step("Ask for pending rewards to be paid", PublishEvent.<ProcessContext>of(QbWallets.SETTLE_EVENT,
                ctx -> Map.of("sponsorId", input(ctx).sponsorId()))));
    }

    private static Input input(ProcessContext ctx) {
        return ctx.get(INPUT, Input.class);
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance wallet(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(WALLET);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private DevCredit() {}
}

package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning a sponsor's cash into Kudos and back (Q87a, D-Q87-3, docs/quizbuks/plans/Q87-fee-account.md):
 * {@value #BUY} posts debit 2160 / credit 2110 one to one, {@value #SELL} the reverse. Both run under the sponsor's
 * wallet lock ({@code qb.wallet:SPONSOR:<sponsor>}: the Kudos wallet and the cash account are two rows of one owner
 * under one lock), read both rows in one statement, and record the exchange ({@code QbKudosExchange}, written once, the
 * postings' source). Whether each is allowed now is a controlled parameter ({@code qb.kudos.buy.enabled}, on;
 * {@code qb.kudos.sell.enabled}, off); what may be moved is decided by {@link #problems} from the rows alone.
 */
public final class KudosExchanges {

    public static final String EXCHANGE = "QbKudosExchange";
    public static final String BUY = "QB_KUDOS_BUY";
    public static final String SELL = "QB_KUDOS_SELL";
    public static final String DIRECTION_DICTIONARY = "urn:jabiz:dict:quizbuks:kudos-exchange-direction";

    /** Cash into Kudos. */
    public static final String TO_KUDOS = "TO_KUDOS";
    /** Kudos back into cash. */
    public static final String TO_CASH = "TO_CASH";

    public record Input(@NotNull @DecimalMin("1") @DecimalMax("1000000000000")
        @Digits(integer = 13, fraction = 0) BigDecimal amount) {}

    /** The balances after the exchange (the Kudos wallet's and the cash account's). */
    public record Output(String exchangeId, BigDecimal amount, BigDecimal kudosBalance, BigDecimal cashBalance) {}

    public static final EntityDefinition EXCHANGE_ENTITY = EntityDefinition.define(EXCHANGE, eb -> {
        eb.physicalTable("qb_kudos_exchange");
        eb.primaryKey("exchangeId");
        eb.field("exchangeId", f -> f.physicalColumn("exchange_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:kudos-exchange"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("direction", f -> f.physicalColumn("direction").immutable(true).required(true).processOnly()
            .asCode(DIRECTION_DICTIONARY, TO_KUDOS, TO_CASH));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "direction", "amount", "createdAt")
            .filters("sponsorId", "direction", "createdAt")
            .sorts("createdAt", "amount")
            .defaultSort("createdAt", false));
    });

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String KUDOS_WALLET = "kudosWallet";
    static final String CASH_WALLET = "cashWallet";
    static final String EXCHANGE_ID = "exchangeId";
    static final String POSTING = "posting";

    /**
     * Why {@code amount} cannot be moved now; none when it can.
     *
     * @param toKudos  cash into Kudos (else Kudos into cash)
     * @param enabled  the direction's switch ({@code qb.kudos.buy|sell.enabled}); null when not set
     */
    public static List<Violation> problems(boolean toKudos, Boolean enabled, BigDecimal amount,
        EntityInstance kudosWallet, EntityInstance cashWallet) {
        List<Violation> problems = new ArrayList<>();
        if (!Boolean.TRUE.equals(enabled)) {
            problems.add(new Violation(null, WalletCodes.KUDOS_EXCHANGE_OFF, toKudos
                ? "Buying Kudos with cash is not available now" : "Turning Kudos back into cash is not available now",
                Map.of()));
            return problems;
        }
        // What open disputes hold is not available on either side (it may be taken back).
        BigDecimal available = QbWallets.available(toKudos ? cashWallet : kudosWallet);
        if (!toKudos && QbWallets.pendingCount(kudosWallet) > 0) {
            // Rewards wait to be paid from these Kudos, in their order: none of them goes back to cash first.
            problems.add(new Violation(null, WalletCodes.KUDOS_EXCHANGE_PENDING,
                "Rewards are waiting to be paid from these Kudos", Map.of("pending",
                    QbWallets.pendingAmount(kudosWallet).toPlainString())));
        } else if (available.compareTo(amount) < 0) {
            problems.add(new Violation("amount", WalletCodes.KUDOS_EXCHANGE_FUNDS, "Only " + available.toPlainString()
                + " is available", Map.of("available", available.max(BigDecimal.ZERO).toPlainString())));
        }
        return problems;
    }

    public static ProcessDefinition<Input, Output, ProcessContext> buy() {
        return process(BUY, true, "Turns cash of the sponsor's cash account into Kudos, one to one.");
    }

    public static ProcessDefinition<Input, Output, ProcessContext> sell() {
        return process(SELL, false, "Turns Kudos of the sponsor's wallet back into cash, one to one.");
    }

    private static ProcessDefinition<Input, Output, ProcessContext> process(String name, boolean toKudos,
        String description) {
        String switchKey = toKudos ? QbParams.KUDOS_BUY_ENABLED : QbParams.KUDOS_SELL_ENABLED;
        return ProcessDefinition.define(name, 1, Input.class, Output.class, ProcessContext.class, pb -> pb
            .description(description)
            // The top-ups' permission: moving the sponsor's own money in the platform, as a top-up does.
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .steps(ConsentChecks.sponsor())
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> output(ctx, toKudos))
            .steps(SponsorChecks::load)
            .step("Load the switch", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS, switchKey,
                QbParams.FEE_OVERDUE_BLOCK, QbParams.FEE_DEFAULT_BILLING_MODE))
            // Q87b: unpaid platform fees stop buying Kudos (never selling), read outside the lock like the sponsor.
            .steps(b -> { if (toKudos) { com.jabiz.quizbuks.fee.FeeGate.load(b, ctx -> ctx.request().actorId()); } })
            .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.request().actorId())))
            .step("Read both wallets", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.SPONSOR, ctx.request().actorId()), KUDOS_WALLET),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, ctx.request().actorId()), CASH_WALLET)))
            .compute("Check and record the exchange", (metadata, ctx) -> record(ctx, toKudos, switchKey))
            // The posting checks that its source document exists: the exchange is written first.
            .step("Save the exchange", SaveChanges.now())
            .step("Post the exchange", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                ctx -> posting(ctx, toKudos), POSTING))
            .compute("Change both wallets", (metadata, ctx) -> change(ctx, toKudos))
            // New Kudos may pay rewards that wait for them, in their order (a transaction of its own).
            .step("Ask for the pending rewards to be paid", PublishEvent.<ProcessContext>when(ctx -> toKudos,
                QbWallets.SETTLE_EVENT, ctx -> Map.of("sponsorId", ctx.request().actorId()))));
    }

    static void record(ProcessContext ctx, boolean toKudos, String switchKey) {
        List<Violation> problems = new ArrayList<>(SponsorChecks.problems(ctx));
        Boolean enabled = ctx.get(PARAMS, ParamValues.class).find(switchKey, Boolean.class).orElse(null);
        problems.addAll(problems(toKudos, enabled, amount(ctx), first(ctx, KUDOS_WALLET), first(ctx, CASH_WALLET)));
        if (toKudos) {
            ParamValues params = ctx.get(PARAMS, ParamValues.class);
            Violation unpaid = com.jabiz.quizbuks.fee.FeeGate.refusal(ctx,
                params.find(QbParams.FEE_OVERDUE_BLOCK, Boolean.class).orElse(null),
                params.find(QbParams.FEE_DEFAULT_BILLING_MODE, String.class).orElse(null));
            if (unpaid != null) {
                problems.add(unpaid);
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", ctx.request().actorId());
        values.put("direction", toKudos ? TO_KUDOS : TO_CASH);
        values.put("amount", amount(ctx));
        values.put("createdAt", ctx.opTime());
        ctx.put(EXCHANGE_ID, String.valueOf(ctx.changes().insert(EXCHANGE, values)));
    }

    /** Cash into Kudos: debit 2160 / credit 2110 (the reverse back into cash); both lines carry the sponsor. */
    static LedgerProcesses.PostInput posting(ProcessContext ctx, boolean toKudos) {
        String sponsorId = ctx.request().actorId();
        BigDecimal amount = amount(ctx);
        String from = toKudos ? QbLedger.SPONSOR_CASH : QbLedger.SPONSOR_PREPAYMENTS;
        String to = toKudos ? QbLedger.SPONSOR_PREPAYMENTS : QbLedger.SPONSOR_CASH;
        return new LedgerProcesses.PostInput(ctx.opTime(), toKudos ? "Kudos bought with cash" : "Kudos sold for cash",
            null, List.of(
                new LedgerProcesses.Line(from, Direction.DEBIT, amount, null, Map.of(QbLedger.PARTY, sponsorId)),
                new LedgerProcesses.Line(to, Direction.CREDIT, amount, null, Map.of(QbLedger.PARTY, sponsorId))),
            EXCHANGE, (String) ctx.get(EXCHANGE_ID));
    }

    static void change(ProcessContext ctx, boolean toKudos) {
        BigDecimal amount = amount(ctx);
        String sponsorId = ctx.request().actorId();
        QbWallets.change(ctx, first(ctx, KUDOS_WALLET), QbWallets.SPONSOR, sponsorId,
            toKudos ? amount : amount.negate(), 0, BigDecimal.ZERO);
        QbWallets.change(ctx, first(ctx, CASH_WALLET), QbWallets.CASH, sponsorId,
            toKudos ? amount.negate() : amount, 0, BigDecimal.ZERO);
    }

    private static Output output(ProcessContext ctx, boolean toKudos) {
        BigDecimal amount = amount(ctx);
        BigDecimal kudos = QbWallets.balance(first(ctx, KUDOS_WALLET)).add(toKudos ? amount : amount.negate());
        BigDecimal cash = QbWallets.balance(first(ctx, CASH_WALLET)).add(toKudos ? amount.negate() : amount);
        return new Output((String) ctx.get(EXCHANGE_ID), amount, kudos, cash);
    }

    private static BigDecimal amount(ProcessContext ctx) {
        return ctx.get(INPUT, Input.class).amount();
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(key);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private KudosExchanges() {}
}

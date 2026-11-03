package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.param.ParamValues;
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

import static com.jabiz.quizbuks.wallet.TopUpCrediting.first;

/**
 * Cash to Kudos and back (docs/quizbuks/plans/Q87-fee-account.md, D-Q87-3): a sponsor turns money in their cash
 * account into Kudos ({@value #BUY}, 1 : 1, debit 2160 / credit 2110, both theirs) or Kudos they do not need back
 * into cash ({@value #SELL}, 2110 → 2160). Both under the sponsor's wallet lock (the two kinds are one lock), both
 * only when their switch is on ({@code qb.kudos.buy.enabled}, {@code qb.kudos.sell.enabled}), and buying only while
 * no platform fee is unpaid beyond what the sponsor's billing allows ({@link FeeGate}). Only what is available moves:
 * the cash less what disputes hold; the Kudos less what disputes hold, and none while rewards wait to be paid.
 * Every exchange is a row of {@value #EXCHANGE}, the source of its posting.
 */
public final class KudosExchange {

    public static final String EXCHANGE = "QbKudosExchange";
    public static final String BUY = "QB_KUDOS_BUY";
    public static final String SELL = "QB_KUDOS_SELL";
    public static final String SPONSOR_EXCHANGES = "urn:jabiz:dataset:sponsor:" + EXCHANGE;
    public static final String DIRECTION_DICTIONARY = "urn:jabiz:dict:quizbuks:kudos-exchange-direction";
    /** Cash became Kudos. */
    public static final String BOUGHT = "BUY";
    /** Kudos became cash. */
    public static final String SOLD = "SELL";

    /** The most one exchange may be, in Kudos: the top-up ceiling (larger sums are split, and seen). */
    public static final String MAX_AMOUNT = "1000000000000";

    /** @param amount whole Kudos (one per yen) */
    public record ExchangeInput(@NotNull @DecimalMin("1") @DecimalMax(MAX_AMOUNT)
        @Digits(integer = 13, fraction = 0) BigDecimal amount) {}

    /** @param kudosBalance the Kudos after; {@code cashBalance} the cash after */
    public record ExchangeOutput(String exchangeId, String direction, BigDecimal amount, BigDecimal kudosBalance,
        BigDecimal cashBalance) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String KUDOS_WALLET = "kudosWallet";
    static final String CASH_WALLET = "cashWallet";
    static final String NEW_ID = "newId";
    static final String ROW = "row";
    static final String POSTING = "posting";
    static final String KUDOS_AFTER = "kudosAfter";
    static final String CASH_AFTER = "cashAfter";

    public static final EntityDefinition EXCHANGE_ENTITY = EntityDefinition.define(EXCHANGE, eb -> {
        eb.physicalTable("qb_kudos_exchange");
        eb.primaryKey("exchangeId");
        eb.field("exchangeId", f -> f.physicalColumn("exchange_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:kudos-exchange"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("direction", f -> f.physicalColumn("direction").immutable(true).required(true).processOnly()
            .asCode(DIRECTION_DICTIONARY, BOUGHT, SOLD));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("exchangedAt", f -> f.physicalColumn("exchanged_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("ledgerTransactionId", f -> f.physicalColumn("ledger_transaction_id").processOnly().asText(64));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "direction", "amount", "exchangedAt")
            .filters("sponsorId", "direction", "exchangedAt")
            .sorts("exchangedAt", "amount")
            .defaultSort("exchangedAt", false));
    });

    private KudosExchange() {}

    public static ProcessDefinition<ExchangeInput, ExchangeOutput, ProcessContext> buy() {
        return define(BUY, true);
    }

    public static ProcessDefinition<ExchangeInput, ExchangeOutput, ProcessContext> sell() {
        return define(SELL, false);
    }

    private static ProcessDefinition<ExchangeInput, ExchangeOutput, ProcessContext> define(String name, boolean buy) {
        return ProcessDefinition.define(name, 1, ExchangeInput.class, ExchangeOutput.class, ProcessContext.class,
            pb -> {
                pb.description(buy ? "Turns money in the sponsor's cash account into Kudos, one for each yen."
                        : "Turns Kudos the sponsor does not need back into money in their cash account.")
                    .permissions(QbPermissions.TOPUP)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    // Money: the sponsors' terms and the privacy policy in effect are accepted (Q7b, Q7-2).
                    .steps(ConsentChecks.sponsor())
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> new ExchangeOutput((String) ctx.get(NEW_ID), buy ? BOUGHT : SOLD,
                        input(ctx).amount(), ctx.get(KUDOS_AFTER, BigDecimal.class), ctx.get(CASH_AFTER, BigDecimal.class)))
                    .steps(SponsorChecks::load)
                    .step("Load the switches", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                        buy ? QbParams.KUDOS_BUY_ENABLED : QbParams.KUDOS_SELL_ENABLED, QbParams.FEE_OVERDUE_BLOCK))
                    .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.request().actorId())))
                    .step("Read the Kudos and the cash", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                            ctx -> QbWallets.query(QbWallets.SPONSOR, ctx.request().actorId()), KUDOS_WALLET),
                        QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                            ctx -> QbWallets.query(QbWallets.CASH, ctx.request().actorId()), CASH_WALLET)));
                if (buy) {
                    // D-Q87-5: unpaid fees beyond what the billing allows stop the buying, while the switch says so.
                    FeeGate.load(pb, ctx -> ctx.request().actorId());
                }
                pb.compute("Check the exchange", (metadata, ctx) -> check(ctx, buy))
                    .compute("Record the exchange", (metadata, ctx) -> insert(ctx, buy))
                    // Written first: the posting names it as its source.
                    .step("Save the exchange", SaveChanges.now())
                    .step("Post the exchange", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                        ctx -> posting(ctx, buy), POSTING))
                    .step("Read the exchange", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(EXCHANGE),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.In("exchangeId",
                            new ArrayList<>(List.of((String) ctx.get(NEW_ID))))).limit(1).build(), ROW))
                    .compute("Move the balances", (metadata, ctx) -> move(ctx, buy))
                    .step("Ask for the pending rewards to be paid", PublishEvent.<ProcessContext>when(ctx -> buy,
                        QbWallets.SETTLE_EVENT, ctx -> Map.of("sponsorId", ctx.request().actorId())));
            });
    }

    static void check(ProcessContext ctx, boolean buy) {
        List<Violation> problems = new ArrayList<>(SponsorChecks.problems(ctx));
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        BigDecimal amount = input(ctx).amount();
        if (!Boolean.TRUE.equals(params.get(buy ? QbParams.KUDOS_BUY_ENABLED : QbParams.KUDOS_SELL_ENABLED,
            Boolean.class))) {
            problems.add(new Violation(null, WalletCodes.KUDOS_EXCHANGE_OFF, (buy ? "Buying" : "Selling")
                + " Kudos is switched off", Map.of()));
        }
        EntityInstance kudos = first(ctx, KUDOS_WALLET);
        EntityInstance cash = first(ctx, CASH_WALLET);
        if (buy) {
            BigDecimal available = QbWallets.available(cash);
            if (available.compareTo(amount) < 0) {
                problems.add(new Violation("amount", WalletCodes.KUDOS_EXCHANGE_SHORT, "The cash account has "
                    + available + " available", Map.of("available", available.toPlainString())));
            }
            if (Boolean.TRUE.equals(params.get(QbParams.FEE_OVERDUE_BLOCK, Boolean.class))) {
                Violation blocked = FeeGate.refusal(ctx);
                if (blocked != null) {
                    problems.add(blocked);
                }
            }
        } else {
            BigDecimal available = QbWallets.available(kudos);
            if (QbWallets.pendingCount(kudos) > 0) {
                problems.add(new Violation(null, WalletCodes.KUDOS_EXCHANGE_PENDING, "Rewards wait to be paid first",
                    Map.of("pendingCount", String.valueOf(QbWallets.pendingCount(kudos)))));
            } else if (available.compareTo(amount) < 0) {
                problems.add(new Violation("amount", WalletCodes.KUDOS_EXCHANGE_SHORT, "The wallet has " + available
                    + " Kudos available", Map.of("available", available.toPlainString())));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    static void insert(ProcessContext ctx, boolean buy) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", ctx.request().actorId());
        values.put("direction", buy ? BOUGHT : SOLD);
        values.put("amount", input(ctx).amount());
        values.put("exchangedAt", ctx.opTime());
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(EXCHANGE, values)));
    }

    /** Buying: debit the cash account, credit the prepayments; selling: the reverse. Both lines carry the sponsor. */
    static LedgerProcesses.PostInput posting(ProcessContext ctx, boolean buy) {
        String sponsorId = ctx.request().actorId();
        BigDecimal amount = input(ctx).amount();
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        return new LedgerProcesses.PostInput(ctx.opTime(), buy ? "Kudos bought" : "Kudos sold", null, List.of(
            new LedgerProcesses.Line(buy ? QbLedger.SPONSOR_CASH : QbLedger.SPONSOR_PREPAYMENTS, Direction.DEBIT, amount,
                null, party),
            new LedgerProcesses.Line(buy ? QbLedger.SPONSOR_PREPAYMENTS : QbLedger.SPONSOR_CASH, Direction.CREDIT, amount,
                null, party)), EXCHANGE, (String) ctx.get(NEW_ID));
    }

    static void move(ProcessContext ctx, boolean buy) {
        String sponsorId = ctx.request().actorId();
        BigDecimal amount = input(ctx).amount();
        EntityInstance kudos = first(ctx, KUDOS_WALLET);
        EntityInstance cash = first(ctx, CASH_WALLET);
        BigDecimal kudosDelta = buy ? amount : amount.negate();
        QbWallets.change(ctx, kudos, QbWallets.SPONSOR, sponsorId, kudosDelta, 0, BigDecimal.ZERO);
        QbWallets.change(ctx, cash, QbWallets.CASH, sponsorId, kudosDelta.negate(), 0, BigDecimal.ZERO);
        ctx.put(KUDOS_AFTER, QbWallets.balance(kudos).add(kudosDelta));
        ctx.put(CASH_AFTER, QbWallets.balance(cash).subtract(kudosDelta));
        EntityInstance row = first(ctx, ROW);
        ctx.changes().update(EXCHANGE, row.id(), row.version(), Map.of("ledgerTransactionId",
            ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId()));
    }

    private static ExchangeInput input(ProcessContext ctx) {
        return ctx.get(INPUT, ExchangeInput.class);
    }
}

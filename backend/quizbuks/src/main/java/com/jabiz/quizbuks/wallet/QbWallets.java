package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.ledger.Direction;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wallets' balance cache (docs/quizbuks/02-design.md section 3.4, docs/quizbuks/plans/Q5-answering.md, D-Q5-5):
 * one ordinary row per owner and kind, changed only under the wallet's lock ({@link #lockName}) by the process that
 * posts to the ledger in the same transaction. The truth is the ledger: a sponsor's balance is the credit of account
 * 2110 for them less its debit. In Q5 only sponsors have rows; a missing row is a balance of 0.
 *
 * <p>Rewards a sponsor cannot pay are counted here too ({@code pendingCount}, {@code pendingAmount}): they are not in
 * the ledger (requirement 7.1) until paid, in the order they were submitted.
 */
public final class QbWallets {

    public static final String WALLET = "QbWallet";
    /** A sponsor's own wallet, read only (Q6a). */
    public static final String SPONSOR_WALLETS = "urn:jabiz:dataset:sponsor:" + WALLET;
    /** The caller's own ledger lines by party, read only: a taker's, a sponsor's (Q6a). */
    public static final String TAKER_LEDGER = "urn:jabiz:dataset:taker:LedgerEntry";
    public static final String SPONSOR_LEDGER = "urn:jabiz:dataset:sponsor:LedgerEntry";
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:wallet-kind";
    public static final String SPONSOR = "SPONSOR";
    public static final String TAKER = "TAKER";

    /** The process that pays a sponsor's pending rewards, in their order (in the {@code play} module). */
    public static final String PENDING_SETTLE = "QB_PENDING_SETTLE";
    /**
     * Event: pay a sponsor's pending rewards, as far as the balance goes, in a transaction of its own (the consumer
     * {@code qb.pending-settle} runs {@link #PENDING_SETTLE} as the system). Published by money coming in (the
     * development credit) and by a settlement run that left more it could pay.
     */
    public static final String SETTLE_EVENT = "qb.sponsor.settle-pending";

    /** Event: a sponsor's wallet has no pending reward left (after some were pending). */
    public static final String FUNDS_RESTORED_EVENT = "qb.sponsor.funds-restored";

    /** The input of the processes that act on one sponsor's wallet. */
    public record SponsorRef(@jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Size(max = 128) String sponsorId) {}

    /** The memo-free description of a reward posting. */
    public static final String REWARD_DESCRIPTION = "Quiz reward";

    /** The name of the analysis dimension of the publication a wallet line belongs to (position 2). */
    public static final String PUBLICATION = "publication";

    /** The default (administrators' read-only) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * The name of a wallet's lock ({@code HoldLock}): the kind is part of it, so a user's sponsor wallet and their
     * taker wallet (Q6) never share one. It comes after a publication's lock and is never held while taking one
     * (D-Q5-7). Null without an owner.
     */
    public static String lockName(String kind, Object ownerId) {
        return ownerId == null ? null : "qb.wallet:" + kind + ":" + ownerId;
    }

    public static final EntityDefinition WALLET_ENTITY = EntityDefinition.define(WALLET, eb -> {
        eb.physicalTable("qb_wallet");
        eb.primaryKey("walletId");
        eb.field("walletId", f -> f.physicalColumn("wallet_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:wallet"));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true).asText(128));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, SPONSOR, TAKER));
        eb.field("balance", f -> f.physicalColumn("balance").required(true).asMonetary("JPY", 0));
        eb.field("pendingCount", f -> f.physicalColumn("pending_count").required(true).asNumeric(9, 0));
        eb.field("pendingAmount", f -> f.physicalColumn("pending_amount").required(true).asMonetary("JPY", 0));
        // Transfers in flight (Q6).
        eb.field("pendingOut", f -> f.physicalColumn("pending_out").required(true).asMonetary("JPY", 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("ownerId", "kind", "balance", "pendingCount", "pendingAmount", "pendingOut")
            .filters("ownerId", "kind")
            .sorts("balance", "pendingAmount")
            .defaultSort("balance", false));
    });

    /** The query of an owner's wallet of a kind (none for no owner). */
    public static EntityQuery query(String kind, Object ownerId) {
        List<Object> owners = new ArrayList<>();
        if (ownerId != null) {
            owners.add(String.valueOf(ownerId));
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(new QueryPredicate.In("ownerId", owners),
            new QueryPredicate.Eq("kind", kind)))).limit(1).build();
    }

    /** A wallet's balance; 0 without a row. */
    public static BigDecimal balance(EntityInstance wallet) {
        return wallet == null ? BigDecimal.ZERO : money(wallet.get("balance"));
    }

    /** A wallet's pending rewards; 0 without a row. */
    public static long pendingCount(EntityInstance wallet) {
        return wallet == null ? 0 : money(wallet.get("pendingCount")).longValueExact();
    }

    public static BigDecimal pendingAmount(EntityInstance wallet) {
        return wallet == null ? BigDecimal.ZERO : money(wallet.get("pendingAmount"));
    }

    /**
     * Whether a sponsor's wallet pays {@code amount} now: nothing is pending (rewards are paid in their order, so a
     * new one never overtakes a pending one, D-Q5-5) and the balance covers it.
     */
    public static boolean pays(EntityInstance wallet, BigDecimal amount) {
        return pendingCount(wallet) == 0 && balance(wallet).compareTo(amount) >= 0;
    }

    /**
     * Writes a wallet's new values: inserts the row (balance and counts 0 before the change) when the owner has none,
     * else updates it at the version read under the lock. {@code delta} names the changes to add.
     */
    public static void change(com.jabiz.process.ProcessContext ctx, EntityInstance wallet, String kind,
        Object ownerId, BigDecimal balanceDelta, long pendingCountDelta, BigDecimal pendingAmountDelta) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("balance", balance(wallet).add(balanceDelta));
        values.put("pendingCount", BigDecimal.valueOf(pendingCount(wallet) + pendingCountDelta));
        values.put("pendingAmount", pendingAmount(wallet).add(pendingAmountDelta));
        if (wallet == null) {
            values.put("ownerId", String.valueOf(ownerId));
            values.put("kind", kind);
            values.put("pendingOut", BigDecimal.ZERO);
            ctx.changes().insert(WALLET, values);
        } else {
            ctx.changes().update(WALLET, wallet.id(), wallet.version(), values);
        }
    }

    /**
     * The posting of a reward (D-Q5-4): the sponsor's wallet pays it (2110, debit), the taker's receives it less the
     * commission (2120, credit), the platform the commission (4110, credit; no line when it is 0). Each line carries
     * the publication; the wallet lines the party. Its source is the attempt.
     */
    public static LedgerProcesses.PostInput rewardPosting(Instant bookingTime, String attemptId, String sponsorId,
        String userId, String publicationId, BigDecimal reward, BigDecimal fee) {
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        lines.add(new LedgerProcesses.Line(QbLedger.SPONSOR_PREPAYMENTS, Direction.DEBIT, reward, null,
            Map.of(QbLedger.PARTY, sponsorId, PUBLICATION, publicationId)));
        BigDecimal net = reward.subtract(fee);
        if (net.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.REWARDS_PAYABLE, Direction.CREDIT, net, null,
                Map.of(QbLedger.PARTY, userId, PUBLICATION, publicationId)));
        }
        if (fee.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.SERVICE_REVENUE, Direction.CREDIT, fee, null,
                Map.of(PUBLICATION, publicationId)));
        }
        return new LedgerProcesses.PostInput(bookingTime, REWARD_DESCRIPTION, attemptId, lines, "QbAttempt",
            attemptId);
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private QbWallets() {}
}

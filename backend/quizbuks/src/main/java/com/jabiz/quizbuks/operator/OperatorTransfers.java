package com.jabiz.quizbuks.operator;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.CashLots;
import com.jabiz.quizbuks.fee.QbFeeAccount;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M4 (docs/quizbuks/first-launch-spec.md F-M4, docs/quizbuks/plans/impl/F-M.md §7): an operator's lead moves money
 * of the operator's account (what its settlements credited, 2140) to the operator's own direct Sponsor, into that
 * Sponsor's account that pays the platform's fees: a cash lot of the source {@code OPERATOR}, spent after the granted
 * credit like the other cash (F-M1), never turned into Kudos (the controller, 2026-11-10) and never paid out.
 *
 * <p>{@value #TRANSFER}: the lead only (as a withdrawal: the session's operator, its lock, the membership), the direct
 * Sponsor taken from the operator's row (never from the input), every time with two-step verification; no second
 * person (the money stays in the platform, with the operator's own Sponsor). Locks: {@code qb.operator:<operator>} →
 * the membership (shared) → {@code qb.wallet:SPONSOR:<direct Sponsor>}. Under them it reads the account's balance
 * ({@code qb.system.operator-balance}: credited less withdrawals less transfers) and refuses more than it, a direct
 * Sponsor without an approved onboarding, or a banned one, writing nothing. The transfer is written once
 * ({@value #TRANSFER_ENTITY_NAME}), then posted (debit 2140 / credit 2160 for the Sponsor) and the cash account and its
 * lot are credited in the same transaction. A transfer is not taken back: it went to the operator's own Sponsor.
 */
public final class OperatorTransfers {

    public static final String TRANSFER_ENTITY_NAME = "QbOperatorTransfer";
    public static final String TRANSFER = "QB_OPERATOR_TRANSFER_TO_DIRECT";
    /** The description of its postings. */
    static final String POSTING_DESCRIPTION = "Operator's account to its direct Sponsor";
    public static final int NOTE_LENGTH = 200;

    static final String INPUT = "input";
    static final String OPERATOR_ROW = "operatorRow";
    static final String SPONSOR_ID = "directSponsorId";
    static final String CASH_WALLET = "cashWallet";
    static final String ONBOARDING = "onboarding";
    static final String BAN = "ban";
    static final String BALANCE = "balance";
    static final String NEW_ID = "newId";
    static final String POSTING = "posting";
    static final String SAME_REQUEST = "sameRequest";
    /** Longest request key, as the platform's idempotency keys. */
    public static final int REQUEST_KEY_LENGTH = 128;

    public static final EntityDefinition TRANSFER_ENTITY = EntityDefinition.define(TRANSFER_ENTITY_NAME, eb -> {
        eb.physicalTable("qb_operator_transfer_version");
        eb.primaryKey("transferId");
        eb.field("transferId", f -> f.physicalColumn("transfer_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:operator-transfer"));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true)
            .asReference(QbOperators.OPERATOR));
        // The operator's direct Sponsor when it was made (the cash went to this Sponsor's account).
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        // The lead's own note, for the operator's records.
        eb.field("note", f -> f.physicalColumn("note").immutable(true).asText(NOTE_LENGTH, true));
        // The page's key for the submission (also sent as its Idempotency-Key): one transfer per key and operator.
        eb.field("requestKey", f -> f.physicalColumn("request_key").immutable(true).required(true)
            .asText(REQUEST_KEY_LENGTH));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(128));
        eb.field("transferredAt", f -> f.physicalColumn("transferred_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("operatorId", "sponsorId", "amount", "transferredAt")
            .filters("operatorId", "sponsorId", "transferredAt")
            .sorts("transferredAt", "amount")
            .defaultSort("transferredAt", false));
    });

    /**
     * @param amount whole yen, at most the operator's account's balance
     * @param note   the lead's own note, optional
     * @param requestKey the page's key for this submission, required: the page sends the same value as the
     *               request's Idempotency-Key, so a retried submission replays the first answer; a second transfer
     *               with a key the operator already used is refused (the controller's review, L3)
     */
    public record TransferInput(@NotNull @DecimalMin("1") BigDecimal amount,
        @Size(max = NOTE_LENGTH) String note,
        @NotBlank @Size(max = REQUEST_KEY_LENGTH) String requestKey) {}

    /** @param balance what the operator's account has left after it */
    public record TransferOutput(String transferId, BigDecimal amount, BigDecimal balance, Instant transferredAt) {}

    private OperatorTransfers() {}

    public static ProcessDefinition<TransferInput, TransferOutput, ProcessContext> transfer() {
        return ProcessDefinition.define(TRANSFER, 1, TransferInput.class, TransferOutput.class, ProcessContext.class,
            pb -> {
                pb.description("The operator's lead moves money of the operator's account to its direct Sponsor, "
                        + "to pay the platform's fees.")
                    .permissions(QbPermissions.OPERATOR_USE)
                    .requiresVerifiedEmail()
                    .requiresMfa(MfaRequirement.ALWAYS)
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory(OperatorTransfers::context)
                    .outputMapper(OperatorTransfers::output);
                OperatorWithdrawals.lead(pb);
                pb.step("Load the operator", QueryEntities.<ProcessContext>of(
                        QbOperators.defaultDataset(QbOperators.OPERATOR),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.In("operatorId",
                            List.of(String.valueOf(ctx.get(OperatorWithdrawals.OPERATOR_ID))))).limit(1).build(),
                        OPERATOR_ROW))
                    .compute("Know its direct Sponsor", (metadata, ctx) -> directSponsor(ctx))
                    .step("Lock the direct Sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.get(SPONSOR_ID))))
                    .step("Read the Sponsor's cash account, onboarding and ban", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                            ctx -> QbWallets.query(QbWallets.CASH, ctx.get(SPONSOR_ID)), CASH_WALLET),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.Eq("userId", ctx.get(SPONSOR_ID))).limit(1).build(), ONBOARDING),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("userId", AccountEditing.uuids(ctx.get(SPONSOR_ID))))
                            .limit(1).build(), BAN),
                        QueryEntities.query(QbOperators.defaultDataset(TRANSFER_ENTITY_NAME), ctx -> EntityQuery
                            .builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("operatorId", ctx.get(OperatorWithdrawals.OPERATOR_ID)),
                                new QueryPredicate.Eq("requestKey", ctx.get(INPUT, TransferInput.class).requestKey()))))
                            .limit(1).build(), SAME_REQUEST)))
                    .step("Read the account's balance", RunTemplate.<ProcessContext>of(
                        OperatorWithdrawals.BALANCE_TEMPLATE,
                        ctx -> Map.of("operatorId", ctx.get(OperatorWithdrawals.OPERATOR_ID)), BALANCE))
                    .compute("Record the transfer", (metadata, ctx) -> record(ctx))
                    // The posting checks that its source document exists: the transfer is written first.
                    .step("Save the transfer", SaveChanges.now())
                    .step("Post the transfer", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                        OperatorTransfers::posting, POSTING))
                    .compute("Credit the Sponsor's cash account", (metadata, ctx) -> credit(ctx));
            });
    }

    private static ProcessContext context(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    /** The operator's direct Sponsor, from its row (the operator's lock is held: it does not change meanwhile). */
    static void directSponsor(ProcessContext ctx) {
        EntityInstance operator = OperatorWithdrawals.first(ctx, OPERATOR_ROW);
        Object sponsor = operator == null ? null : operator.get("directSponsorId");
        if (sponsor == null || String.valueOf(sponsor).isBlank()) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.TRANSFER_NO_DIRECT,
                "The operator has no direct Sponsor", Map.of()));
        }
        ctx.put(SPONSOR_ID, String.valueOf(sponsor));
    }

    static void record(ProcessContext ctx) {
        TransferInput input = ctx.get(INPUT, TransferInput.class);
        if (OperatorWithdrawals.first(ctx, SAME_REQUEST) != null) {
            throw new BusinessRuleViolationException(new Violation("requestKey", OperatorCodes.TRANSFER_REPEATED,
                "This submission was already made", Map.of()));
        }
        EntityInstance onboarding = OperatorWithdrawals.first(ctx, ONBOARDING);
        if (onboarding == null || !SponsorStatus.APPROVED.equals(onboarding.get("reviewStatus"))) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.TRANSFER_NO_DIRECT,
                "The direct Sponsor's onboarding is not approved", Map.of()));
        }
        EntityInstance ban = OperatorWithdrawals.first(ctx, BAN);
        if (BanRules.banned(ban == null ? null : ban.attributes())) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.TRANSFER_DIRECT_BANNED,
                "The direct Sponsor is banned", Map.of()));
        }
        BigDecimal balance = OperatorWithdrawals.balance(ctx);
        BigDecimal amount = input.amount();
        if (!OperatorRules.transferable(amount, balance)) {
            throw new BusinessRuleViolationException(new Violation("amount", OperatorCodes.TRANSFER_AMOUNT,
                "Whole yen up to the balance", Map.of("balance", balance.max(BigDecimal.ZERO).toPlainString())));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operatorId", ctx.get(OperatorWithdrawals.OPERATOR_ID));
        values.put("sponsorId", ctx.get(SPONSOR_ID));
        values.put("amount", amount.setScale(0));
        String note = input.note() == null || input.note().isBlank() ? null : input.note().strip();
        values.put("note", note);
        values.put("requestKey", input.requestKey());
        values.put("requestedBy", ctx.request().actorId());
        values.put("transferredAt", ctx.opTime());
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(TRANSFER_ENTITY_NAME, values)));
    }

    /** Debit 2140 (what the platform owes the operator), credit 2160 for the direct Sponsor. */
    static LedgerProcesses.PostInput posting(ProcessContext ctx) {
        BigDecimal amount = ctx.get(INPUT, TransferInput.class).amount().setScale(0);
        String id = (String) ctx.get(NEW_ID);
        return new LedgerProcesses.PostInput(ctx.opTime(), POSTING_DESCRIPTION, "opt_" + id.substring(0, 8), List.of(
                new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES_PAYABLE, Direction.DEBIT, amount),
                new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.CREDIT, amount, null,
                    Map.of(QbLedger.PARTY, String.valueOf((Object) ctx.get(SPONSOR_ID))))),
            TRANSFER_ENTITY_NAME, id);
    }

    static void credit(ProcessContext ctx) {
        BigDecimal amount = ctx.get(INPUT, TransferInput.class).amount().setScale(0);
        Object sponsorId = ctx.get(SPONSOR_ID);
        QbWallets.change(ctx, OperatorWithdrawals.first(ctx, CASH_WALLET), QbWallets.CASH, sponsorId, amount, 0,
            BigDecimal.ZERO);
        CashLots.add(ctx, sponsorId, QbFeeAccount.OPERATOR, amount, TRANSFER_ENTITY_NAME, ctx.get(NEW_ID));
    }

    private static TransferOutput output(ProcessContext ctx) {
        BigDecimal amount = ctx.get(INPUT, TransferInput.class).amount().setScale(0);
        return new TransferOutput((String) ctx.get(NEW_ID), amount,
            OperatorWithdrawals.balance(ctx).subtract(amount), ctx.opTime());
    }
}

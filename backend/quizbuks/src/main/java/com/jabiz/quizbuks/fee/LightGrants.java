package com.jabiz.quizbuks.fee;

import com.jabiz.ledger.Direction;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.QbTrees;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The platform fee Kudos a Sponsor is granted for lighting a node (F-S4b, docs/quizbuks/plans/impl/F-S4.md §3): the
 * lit record froze the template's grant ({@code QbTreeLit.grantAmount}); the grant itself is a lot of the source
 * {@code LIGHT} with the key {@code lit:<lit record>} ({@link FeeCredits#GRANT}: once per key, the wallet lock held),
 * never in the answering nor in the lighting:
 * <ul>
 *   <li>{@value #GRANT} ({@code internal()}): every lit record of the Sponsor with a grant, not revoked and not granted
 *       yet, under their wallet lock; run for the lighting's event {@value #DUE_EVENT} and, for what failed, by the
 *       daily job {@value #RETRY_JOB} (template {@value #DUE_TEMPLATE}). Nothing while the grants' switch
 *       {@code qb.fee-credit.grant.enabled} is off (the job gives them once it is on);</li>
 *   <li>{@value #VOID} ({@code internal()}, for {@code qb.tree.lit-revoked}): a revoked lighting's lot loses what is
 *       left of it (the decision of 2026-11-10: what was used is not taken back), as an expiry does
 *       (debit 2190, credit 5140).</li>
 * </ul>
 */
public final class LightGrants {

    public static final String GRANT = "QB_TREE_LIT_GRANT";
    public static final String VOID = "QB_TREE_LIT_GRANT_VOID";
    /** A Sponsor lit a node that grants: {@code sponsorId}. */
    public static final String DUE_EVENT = "qb.tree.grant-due";
    public static final String DUE_CONSUMER = "qb.tree-grant-due";
    public static final String VOID_CONSUMER = "qb.tree-grant-void";
    /** Every day at 03:29 (in {@code jabiz.fiscal-year-zone}): the Sponsors with a grant still due. */
    public static final String RETRY_JOB = "qb.tree-grant-retry";
    public static final String RETRY_CRON = "0 29 3 * * *";
    public static final String DUE_TEMPLATE = "qb.system.tree-grants-due";
    public static final String SOURCE = "LIGHT";

    /** The most lit records of a Sponsor read at once (far more than a map has nodes). */
    static final int MAX_LITS = 5000;
    /** The most grants one run gives (the rest by the next run or the job). */
    static final int MAX_PER_RUN = 50;

    /** The posting's description when a revoked lighting's lot is voided. */
    static final String VOIDED = "Platform fee Kudos voided (lighting revoked)";

    /** @param litId the revoked lit record */
    public record LitRef(@NotBlank @Size(max = 36) String litId) {}

    static final String INPUT = FeeCredits.INPUT;
    static final String PARAMS = "params";
    static final String LITS = "lits";
    static final String REVOCATIONS = "revocations";
    static final String GRANTS = "grants";
    static final String DUE = "due";
    static final String DONE = "done";
    static final String FOUND = "found";
    static final String LOT = "lot";
    static final String CREDIT_WALLET = "creditWallet";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String OUTCOME = "outcome";

    private LightGrants() {}

    /** The grant's source key of a lit record. */
    public static String sourceKey(Object litId) {
        return "lit:" + litId;
    }

    // ---- the grants ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> grant() {
        return ProcessDefinition.define(GRANT, 1, FeeCredits.SponsorRef.class, FeeProcesses.ItemOutput.class,
            ProcessContext.class, pb -> pb
                .description("Grants a Sponsor the platform fee Kudos of the nodes they lit and were not granted yet "
                    + "(once per lit record, under their wallet lock).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(LightGrants::grantOutput)
                .step("Load the grants' switch", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_CREDIT_GRANT_ENABLED))
                .step("Lock the Sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> enabled(ctx)
                    ? QbWallets.lockName(QbWallets.SPONSOR, sponsorId(ctx)) : null))
                .step("Read the Sponsor's lit records, revocations and lighting grants", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.LIT), ctx -> subjects(ctx), LITS),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOCATION), ctx -> subjects(ctx), REVOCATIONS),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("sponsorId", enabled(ctx) ? List.of(sponsorId(ctx)) : List.of()),
                            new QueryPredicate.Eq("source", SOURCE)))).limit(MAX_LITS).build(), GRANTS)))
                .compute("Know the grants due", (metadata, ctx) -> ctx.put(DUE, due(ctx)))
                .step("Grant them", CallProcess.<ProcessContext>forEach(FeeCredits.GRANT, 1,
                    ctx -> ctx.contains(DUE) ? (List<?>) ctx.get(DUE) : List.of(), DONE)));
    }

    private static String sponsorId(ProcessContext ctx) {
        return ctx.get(INPUT, FeeCredits.SponsorRef.class).sponsorId();
    }

    private static boolean enabled(ProcessContext ctx) {
        ParamValues params = ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class) : null;
        return params != null && Boolean.TRUE.equals(params.find(QbParams.FEE_CREDIT_GRANT_ENABLED, Boolean.class)
            .orElse(null));
    }

    /** The Sponsor's lit records or revocations (none while the switch is off: no statement). */
    private static EntityQuery subjects(ProcessContext ctx) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.In("subjectKind", enabled(ctx) ? List.of(QbTrees.SPONSOR) : List.of()),
            new QueryPredicate.Eq("subjectId", sponsorId(ctx))))).limit(MAX_LITS).build();
    }

    /** The grants due: a lit record with a grant, not revoked, whose key has no lot yet; at most {@link #MAX_PER_RUN}. */
    static List<FeeCredits.GrantInput> due(ProcessContext ctx) {
        Set<String> revoked = new HashSet<>();
        FeeGate.list(ctx, REVOCATIONS).forEach(row -> revoked.add(String.valueOf((Object) row.get("litId"))));
        Set<String> granted = new HashSet<>();
        FeeGate.list(ctx, GRANTS).forEach(row -> granted.add(String.valueOf((Object) row.get("sourceKey"))));
        List<FeeCredits.GrantInput> due = new ArrayList<>();
        for (EntityInstance lit : FeeGate.list(ctx, LITS)) {
            Object amount = lit.get("grantAmount");
            String litId = String.valueOf(lit.id());
            if (amount == null || revoked.contains(litId) || granted.contains(sourceKey(litId))) {
                continue;
            }
            BigDecimal value = FeeGate.money(amount);
            if (value.signum() > 0 && due.size() < MAX_PER_RUN) {
                due.add(new FeeCredits.GrantInput(sponsorId(ctx), SOURCE, sourceKey(litId), value, null));
            }
        }
        return due;
    }

    static FeeProcesses.ItemOutput grantOutput(ProcessContext ctx) {
        String outcome;
        if (!enabled(ctx)) {
            outcome = "OFF";
        } else {
            int count = ctx.contains(DUE) ? ((List<?>) ctx.get(DUE)).size() : 0;
            outcome = count == 0 ? "NONE" : "GRANTED " + count;
        }
        return new FeeProcesses.ItemOutput(sponsorId(ctx), outcome, null);
    }

    // ---- the void of a revoked lighting's lot -------------------------------------------------------------------------

    public static ProcessDefinition<LitRef, FeeProcesses.ItemOutput, ProcessContext> voidGrant() {
        return ProcessDefinition.define(VOID, 1, LitRef.class, FeeProcesses.ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Voids what is left of the platform fee Kudos granted for a lighting that was revoked "
                    + "(what was used is kept).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(lit(ctx) == null ? null
                    : lit(ctx).get("subjectId"), ctx.contains(OUTCOME) ? (String) ctx.get(OUTCOME) : "NONE",
                    ctx.get(INPUT, LitRef.class).litId()))
                .step("Find the lit record", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.LIT),
                    ctx -> FeeProcesses.byId("litId", ctx.get(INPUT, LitRef.class).litId()), FOUND))
                .step("Lock the Sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> lit(ctx) == null ? null
                    : QbWallets.lockName(QbWallets.SPONSOR, lit(ctx).get("subjectId"))))
                .step("Read the lot and the wallet", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("sponsorId", lit(ctx) == null ? List.of()
                                : List.of((Object) lit(ctx).get("subjectId"))),
                            new QueryPredicate.Eq("sourceKey", sourceKey(ctx.get(INPUT, LitRef.class).litId())))))
                        .limit(1).build(), LOT),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(
                        QbWallets.FEE_CREDIT, lit(ctx) == null ? null : lit(ctx).get("subjectId")), CREDIT_WALLET)))
                .compute("Void what is left", (metadata, ctx) -> voidLeft(ctx))
                .step("Post the void", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING)));
    }

    /** The lit record, when it is a Sponsor's (a Taker's lighting grants nothing). */
    private static EntityInstance lit(ProcessContext ctx) {
        EntityInstance lit = FeeGate.first(ctx, FOUND);
        return lit != null && QbTrees.SPONSOR.equals(lit.get("subjectKind")) ? lit : null;
    }

    static void voidLeft(ProcessContext ctx) {
        EntityInstance lot = FeeGate.first(ctx, LOT);
        if (lit(ctx) == null || lot == null) {
            return;
        }
        BigDecimal left = FeeGate.money(lot.get("remaining"));
        if (left.signum() <= 0) {
            ctx.put(OUTCOME, "USED");
            return;
        }
        String sponsorId = lot.get("sponsorId");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("remaining", BigDecimal.ZERO);
        values.put("voidedAmount", FeeGate.money(lot.get("voidedAmount")).add(left));
        values.put("voidedAt", ctx.opTime());
        ctx.changes().update(QbFeeAccount.GRANT, lot.id(), lot.version(), values);
        QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, sponsorId, left.negate(), 0,
            BigDecimal.ZERO);
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), VOIDED, lot.get("source"), List.of(
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, left, null, party),
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.CREDIT, left, null, party)),
            QbFeeAccount.GRANT, String.valueOf(lot.id())));
        ctx.put(OUTCOME, "VOIDED");
    }
}

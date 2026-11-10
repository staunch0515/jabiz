package com.jabiz.quizbuks.fee;

import com.jabiz.ledger.Direction;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.QbTrees;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The platform fee Kudos a Sponsor is granted for lighting a node (F-S4b, docs/quizbuks/plans/impl/F-S4.md §3): the
 * lit record froze the template's grant ({@code QbTreeLit.grantAmount}); the grant is a lot of the source
 * {@code LIGHT} whose key is the <b>template's node</b>, {@code light:<template>/<node>}: a Sponsor is granted a node
 * once in their life (the requester, 2026-11-10), whether they light it again after a revocation or, after a
 * reassignment, in another operator's copy ({@link FeeCredits#GRANT} gives back the lot a key has).
 *
 * <p>{@value #SETTLE} ({@code internal()}, the Sponsor's wallet lock) settles one Sponsor both ways, never in the
 * answering nor in the lighting:
 * <ul>
 *   <li>grants each template node they hold lit now (a lit record with a grant, not revoked) that has no lot yet, the
 *       amount of the earliest such record; only while the grants' switch {@code qb.fee-credit.grant.enabled} is on,
 *       and not to a Sponsor banned or whose onboarding is not approved (as {@code QB_FEE_CREDIT_ONBOARDING});</li>
 *   <li>voids what is left of a lot whose node they no longer hold lit (every lit record of it revoked; the decision
 *       of 2026-11-10: what was used is not taken back), as an expiry does (debit 2190, credit 5140), also what a
 *       voided invoice gave back to it afterwards.</li>
 * </ul>
 * It runs for the lighting's event {@value #DUE_EVENT}, for a revocation's {@code qb.tree.lit-revoked}, and daily for
 * whatever these missed ({@value #RETRY_JOB}, template {@value #DUE_TEMPLATE}).
 */
public final class LightGrants {

    public static final String SETTLE = "QB_TREE_LIT_GRANT";
    /** A Sponsor lit a node that grants: {@code sponsorId}. */
    public static final String DUE_EVENT = "qb.tree.grant-due";
    public static final String DUE_CONSUMER = "qb.tree-grant-due";
    public static final String REVOKED_CONSUMER = "qb.tree-grant-revoked";
    /** Every day at 03:29 (in {@code jabiz.fiscal-year-zone}): the Sponsors with a grant due or a lot to void. */
    public static final String RETRY_JOB = "qb.tree-grant-retry";
    public static final String RETRY_CRON = "0 29 3 * * *";
    public static final String DUE_TEMPLATE = "qb.system.tree-grants-due";
    public static final String SOURCE = "LIGHT";

    /** The most lit records and lots of a Sponsor read at once (far more than the platform's maps have nodes). */
    static final int MAX_ROWS = 5000;

    /** The posting's description when a lot no lighting holds any more is voided. */
    static final String VOIDED = "Platform fee Kudos voided (lighting revoked)";

    static final String INPUT = FeeCredits.INPUT;
    static final String PARAMS = "params";
    static final String LITS = "lits";
    static final String REVOCATIONS = "revocations";
    static final String LOTS = "lots";
    static final String CREDIT_WALLET = "creditWallet";
    static final String ONBOARDING = "onboarding";
    static final String BAN = "ban";
    static final String TREES = "trees";
    static final String DUE = "due";
    static final String VOIDS = "voids";
    static final String DONE = "done";
    static final String POSTED = "posted";
    static final String OUTCOME = "outcome";

    private LightGrants() {}

    /** The grant's source key of a template's node. */
    public static String sourceKey(Object templateTreeId, Object nodeId) {
        return "light:" + templateTreeId + "/" + nodeId;
    }

    public static ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> settle(
        int maxPerRun) {
        return ProcessDefinition.define(SETTLE, 1, FeeCredits.SponsorRef.class, FeeProcesses.ItemOutput.class,
            ProcessContext.class, pb -> pb
                .description("Settles a Sponsor's platform fee Kudos for lit nodes: grants each template node they "
                    + "hold lit once, and voids what is left of a grant whose lighting was revoked.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(sponsorId(ctx),
                    ctx.contains(OUTCOME) ? (String) ctx.get(OUTCOME) : "NONE", null))
                .step("Load the grants' switch", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_CREDIT_GRANT_ENABLED))
                .step("Lock the Sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, sponsorId(ctx))))
                .step("Read the Sponsor's lit records, revocations, lighting lots, wallet, onboarding and ban",
                    QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbTrees.defaultDataset(QbTrees.LIT), LightGrants::subjects, LITS),
                        QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOCATION), LightGrants::subjects,
                            REVOCATIONS),
                        QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> EntityQuery
                            .builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("sponsorId", FeeGate.ids(sponsorId(ctx))),
                                new QueryPredicate.Eq("source", SOURCE)))).limit(MAX_ROWS).build(), LOTS),
                        QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                            ctx -> QbWallets.query(QbWallets.FEE_CREDIT, sponsorId(ctx)), CREDIT_WALLET),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery
                            .builder().where(new QueryPredicate.Eq("userId", sponsorId(ctx))).limit(1).build(),
                            ONBOARDING),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("userId", AccountEditing.uuids(sponsorId(ctx)))).limit(1)
                            .build(), BAN)))
                .step("Read the maps of the lit records", QueryEntities.<ProcessContext>of(
                    QbTrees.defaultDataset(QbTrees.TREE), ctx -> {
                        Set<Object> treeIds = new LinkedHashSet<>();
                        FeeGate.list(ctx, LITS).forEach(lit -> treeIds.add(String.valueOf((Object) lit.get("treeId"))));
                        return EntityQuery.builder().where(new QueryPredicate.In("treeId", List.copyOf(treeIds)))
                            .limit(Math.max(1, treeIds.size())).build();
                    }, TREES))
                .compute("Know what is due and what is left", (metadata, ctx) -> settle(ctx, maxPerRun))
                .step("Post the voids", CallProcess.<ProcessContext>forEach(LedgerProcesses.POST, 1,
                    ctx -> ctx.contains(VOIDS) ? (List<?>) ctx.get(VOIDS) : List.of(), POSTED))
                .step("Grant what is due", CallProcess.<ProcessContext>forEach(FeeCredits.GRANT, 1,
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

    private static EntityQuery subjects(ProcessContext ctx) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("subjectKind", QbTrees.SPONSOR),
            new QueryPredicate.Eq("subjectId", sponsorId(ctx))))).limit(MAX_ROWS).build();
    }

    /**
     * The template of a lit record's map: the map itself when it is a platform template, its template when it is an
     * operator's copy; none for another map.
     */
    static String templateOf(EntityInstance tree) {
        if (tree == null) {
            return null;
        }
        if (QbTrees.PLATFORM.equals(tree.get("ownerKind"))) {
            return String.valueOf(tree.id());
        }
        Object template = tree.get("templateTreeId");
        return QbTrees.OPERATOR.equals(tree.get("ownerKind")) && template != null ? template.toString() : null;
    }

    static void settle(ProcessContext ctx, int maxPerRun) {
        Map<String, String> templates = new HashMap<>();
        FeeGate.list(ctx, TREES).forEach(tree -> templates.put(String.valueOf(tree.id()), templateOf(tree)));
        Set<String> revoked = new HashSet<>();
        FeeGate.list(ctx, REVOCATIONS).forEach(row -> revoked.add(String.valueOf((Object) row.get("litId"))));
        // Each template node the Sponsor holds lit now, with the earliest lit record of it (the grant it froze).
        Map<String, EntityInstance> held = new LinkedHashMap<>();
        FeeGate.list(ctx, LITS).stream()
            .sorted(Comparator.comparing(lit -> String.valueOf((Object) lit.get("litAt"))))
            .forEach(lit -> {
                String template = templates.get(String.valueOf((Object) lit.get("treeId")));
                if (template != null && !revoked.contains(String.valueOf(lit.id()))
                    && FeeGate.money(lit.get("grantAmount")).signum() > 0) {
                    held.putIfAbsent(sourceKey(template, lit.get("nodeId")), lit);
                }
            });
        Map<String, EntityInstance> lots = new HashMap<>();
        FeeGate.list(ctx, LOTS).forEach(lot -> lots.put(String.valueOf((Object) lot.get("sourceKey")), lot));
        int granted = 0;
        int voided = 0;

        // What is left of a lot whose node is no longer held: voided (what was used is kept).
        List<LedgerProcesses.PostInput> posts = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, EntityInstance> entry : lots.entrySet()) {
            EntityInstance lot = entry.getValue();
            BigDecimal left = FeeGate.money(lot.get("remaining"));
            if (held.containsKey(entry.getKey()) || left.signum() <= 0) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("remaining", BigDecimal.ZERO);
            values.put("voidedAmount", FeeGate.money(lot.get("voidedAmount")).add(left));
            values.put("voidedAt", ctx.opTime());
            ctx.changes().update(QbFeeAccount.GRANT, lot.id(), lot.version(), values);
            Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId(ctx));
            posts.add(new LedgerProcesses.PostInput(ctx.opTime(), VOIDED, SOURCE, List.of(
                new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, left, null, party),
                new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.CREDIT, left, null, party)),
                QbFeeAccount.GRANT, String.valueOf(lot.id())));
            total = total.add(left);
            voided++;
        }
        if (total.signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, sponsorId(ctx),
                total.negate(), 0, BigDecimal.ZERO);
        }
        ctx.put(VOIDS, List.copyOf(posts));

        // What is due: a node held with no lot of its key yet, while the switch is on, to an approved Sponsor not
        // banned.
        EntityInstance onboarding = FeeGate.first(ctx, ONBOARDING);
        EntityInstance ban = FeeGate.first(ctx, BAN);
        boolean may = enabled(ctx) && onboarding != null
            && SponsorStatus.APPROVED.equals(onboarding.get("reviewStatus"))
            && !BanRules.banned(ban == null ? null : ban.attributes());
        List<FeeCredits.GrantInput> due = new ArrayList<>();
        if (may) {
            for (Map.Entry<String, EntityInstance> entry : held.entrySet()) {
                if (!lots.containsKey(entry.getKey()) && due.size() < maxPerRun) {
                    due.add(new FeeCredits.GrantInput(sponsorId(ctx), SOURCE, entry.getKey(),
                        FeeGate.money(entry.getValue().get("grantAmount")), null));
                }
            }
            granted = due.size();
        }
        ctx.put(DUE, List.copyOf(due));
        ctx.put(OUTCOME, granted == 0 && voided == 0 ? (may || held.isEmpty() ? "NONE" : "HELD")
            : "GRANTED " + granted + ", VOIDED " + voided);
    }
}

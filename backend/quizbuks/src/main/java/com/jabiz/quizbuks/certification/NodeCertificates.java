package com.jabiz.quizbuks.certification;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.QbTrees;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.RandomBytesStep;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The certificates of lit nodes (F-S5a, docs/quizbuks/plans/impl/F-S5.md §2.2): a node whose lighting issues one froze
 * its names on the lit record ({@code QbTreeLit.certificateNames}); {@value #SETTLE} ({@code internal()}, the person's
 * certificate lock) writes a certificate row for each such record of a person that is not revoked and has none yet.
 * It runs for the lighting's event {@value #DUE_EVENT} and daily for whatever that missed ({@value #RETRY_JOB}, template
 * {@value #DUE_TEMPLATE}), and again and again changes nothing: a lit record has one certificate (the unique {@code lit_id}),
 * and the person's lock makes the second run find the first's.
 *
 * <p>Everything on the row is frozen as lit: the holder's name, the names of the certificate, the issuer (the owner of the
 * graph: the platform's {@code qb.issuer.name}, the operator of a copy, the Sponsor of their own graph) and the time lit.
 * There is no end (A2-12). The PDF is issued by the holder on the first download ({@link CertificateProcesses}).
 * A revocation writes nothing here: the lit record's revocation is the certificate's.
 */
public final class NodeCertificates {

    public static final String SETTLE = "QB_TREE_LIT_CERTIFICATE";
    /** A person lit a node that issues a certificate: {@code subjectKind}, {@code subjectId}. */
    public static final String DUE_EVENT = "qb.tree.certificate-due";
    public static final String DUE_CONSUMER = "qb.tree-certificate-due";
    /** Every day at 03:41 (in {@code jabiz.fiscal-year-zone}): the people with a lit record that has no certificate. */
    public static final String RETRY_JOB = "qb.tree-certificate-retry";
    public static final String RETRY_CRON = "0 41 3 * * *";
    public static final String DUE_TEMPLATE = "qb.system.tree-certificates-due";

    /** The source of a node's certificate (the other is Q77's, {@link QbCertificates#CERTIFICATION}). */
    public static final String NODE = QbCertificates.NODE;
    public static final String USER = QbCertificates.USER;
    public static final String SPONSOR = QbCertificates.SPONSOR;

    /** The platform as the issuer when {@code qb.issuer.name} is not set yet: {@code certificate.issuer.platform}. */
    public static final String PLATFORM_ISSUER_KEY = "certificate.issuer.platform";
    /** The most lit records and certificates of a person read at once (far more than the maps have nodes). */
    static final int MAX_ROWS = 5000;
    /** The seed of a run's keys: 32 random bytes, never written. */
    static final int SEED_BYTES = 32;

    static final String INPUT = "nodeCertificate.input";
    static final String SEED = "nodeCertificate.seed";
    static final String PARAMS = "nodeCertificate.params";
    static final String LITS = "nodeCertificate.lits";
    static final String REVOCATIONS = "nodeCertificate.revocations";
    static final String EXISTING = "nodeCertificate.existing";
    static final String PROFILE = "nodeCertificate.profile";
    static final String SPONSOR_SELF = "nodeCertificate.sponsorSelf";
    static final String ERASED = "nodeCertificate.erased";
    static final String TREES = "nodeCertificate.trees";
    static final String OPERATORS = "nodeCertificate.operators";
    static final String OWNERS = "nodeCertificate.owners";
    static final String WRITTEN = "nodeCertificate.written";

    private NodeCertificates() {}

    /**
     * @param subjectId the person (a user: their Taker's and their Sponsor's lit records both); the job names no kind
     * @param subjectKind informational, from the event
     */
    public record SubjectRef(@NotBlank @Size(max = 128) String subjectId, @Size(max = 10) String subjectKind) {}

    /** @param written how many certificates this run wrote */
    public record SettleOutput(String subjectId, int written) {}

    /** The person's certificate lock: never held with the lighting's, a publication's, a start or a wallet's. */
    public static String lockName(Object subjectId) {
        return subjectId == null ? null : "qb.certificate:" + subjectId;
    }

    /**
     * A certificate's verify key: 128 bits of the hash of the run's seed and the lit record, as 32 hex digits. The seed is
     * random and never leaves the run, so a key says nothing of its lit record and no key follows from another's.
     */
    public static String verifyKey(byte[] seed, String litId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(seed);
            digest.update(litId.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            return HexFormat.of().formatHex(hash, 0, QbCertificates.KEY_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ProcessDefinition<SubjectRef, SettleOutput, ProcessContext> settle(MessageCatalog messages) {
        return ProcessDefinition.define(SETTLE, 1, SubjectRef.class, SettleOutput.class, ProcessContext.class, pb -> pb
            .description("Writes the certificates of the nodes a person lit that issue one and have none yet.")
            .permissions(QbPermissions.CERTIFICATE_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new SettleOutput(subjectId(ctx), ctx.contains(WRITTEN) ? (Integer) ctx.get(WRITTEN)
                : 0))
            .step("Draw the seed of the keys", RandomBytesStep.class, new RandomBytesStep.Metadata(SEED, SEED_BYTES))
            .step("Load the issuer's name", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                QbParams.ISSUER_NAME))
            .step("Lock the person's certificates", HoldLock.<ProcessContext>exclusive(
                ctx -> lockName(subjectId(ctx))))
            .step("Read the lit records, revocations, certificates and the names of the person",
                QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.LIT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("subjectId", List.of(subjectId(ctx)))).limit(MAX_ROWS).build(),
                        LITS),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOCATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("subjectId", List.of(subjectId(ctx)))).limit(MAX_ROWS).build(),
                        REVOCATIONS),
                    QueryEntities.query(QbCertificates.DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("userId", subjectId(ctx)),
                            new QueryPredicate.Eq("source", NODE)))).limit(MAX_ROWS).build(), EXISTING),
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.PROFILE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("userId", subjectId(ctx))).limit(1).build(), PROFILE),
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("userId", subjectId(ctx))).limit(1).build(), SPONSOR_SELF),
                    // An erased account (platform D62) has no certificate to be made again from the lit records that stay.
                    QueryEntities.query(SecurityEntities.USER_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("userId", AccountEditing.uuids(subjectId(ctx)))).limit(1).build(),
                        ERASED)))
            .step("Read the maps of the lit records", QueryEntities.<ProcessContext>of(
                QbTrees.defaultDataset(QbTrees.TREE), ctx -> {
                    Set<Object> treeIds = new LinkedHashSet<>();
                    due(ctx).forEach(lit -> treeIds.add(String.valueOf((Object) lit.get("treeId"))));
                    return EntityQuery.builder().where(new QueryPredicate.In("treeId", List.copyOf(treeIds)))
                        .limit(Math.max(1, treeIds.size())).build();
                }, TREES))
            .step("Read the operators and Sponsors who own them", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.OPERATOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("operatorId", owners(ctx, QbTrees.OPERATOR)))
                    .limit(Math.max(1, owners(ctx, QbTrees.OPERATOR).size())).build(), OPERATORS),
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", owners(ctx, QbTrees.SPONSOR)))
                    .limit(Math.max(1, owners(ctx, QbTrees.SPONSOR).size())).build(), OWNERS)))
            .compute("Write the certificates", (metadata, ctx) -> write(ctx, messages)));
    }

    private static String subjectId(ProcessContext ctx) {
        return ctx.get(INPUT, SubjectRef.class).subjectId();
    }

    /**
     * The lit records that need a certificate now: names frozen, not revoked, none written, oldest first; none for a person
     * whose account was erased (the lit records stay, holding ids only).
     */
    static List<EntityInstance> due(ProcessContext ctx) {
        if (erased(ctx)) {
            return List.of();
        }
        Set<String> revoked = new LinkedHashSet<>();
        list(ctx, REVOCATIONS).forEach(row -> revoked.add(String.valueOf((Object) row.get("litId"))));
        Set<String> done = new LinkedHashSet<>();
        list(ctx, EXISTING).forEach(row -> done.add(String.valueOf((Object) row.get("litId"))));
        return list(ctx, LITS).stream()
            .filter(lit -> lit.get("certificateNames") != null && !revoked.contains(String.valueOf(lit.id()))
                && !done.contains(String.valueOf(lit.id())))
            .sorted(Comparator.comparing((EntityInstance lit) -> CertificateProcesses.instant(lit.get("litAt")))
                .thenComparing(lit -> String.valueOf(lit.id())))
            .toList();
    }

    private static boolean erased(ProcessContext ctx) {
        return list(ctx, ERASED).stream().anyMatch(user -> user.get("erasedAt") != null);
    }

    /** The ids of the owners of the maps read, of one kind. */
    private static List<Object> owners(ProcessContext ctx, String ownerKind) {
        Set<Object> ids = new LinkedHashSet<>();
        list(ctx, TREES).stream().filter(tree -> ownerKind.equals(tree.get("ownerKind")))
            .forEach(tree -> ids.add(String.valueOf((Object) tree.get("ownerId"))));
        return List.copyOf(ids);
    }

    static void write(ProcessContext ctx, MessageCatalog messages) {
        // An account erased: platform ACCOUNT_ERASE does not take our lock, so a certificate written by a settlement that
        // read before the erasure committed can outlive it. Removed here through the normal write path (the audit and the
        // purge see it). Its PDF, if any, is the erasure's purge's (a row written after the erasure has none).
        if (erased(ctx)) {
            list(ctx, EXISTING).forEach(row -> ctx.changes().delete(QbCertificates.CERTIFICATE, row.id(),
                row.version()));
            ctx.put(WRITTEN, 0);
            return;
        }
        int written = 0;
        byte[] seed = ctx.get(SEED, byte[].class);
        for (EntityInstance lit : due(ctx)) {
            EntityInstance tree = list(ctx, TREES).stream()
                .filter(row -> String.valueOf(row.id()).equals(String.valueOf((Object) lit.get("treeId"))))
                .findFirst().orElse(null);
            boolean sponsor = SPONSOR.equals(lit.get("subjectKind"));
            String litId = String.valueOf(lit.id());
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", subjectId(ctx));
            values.put("source", NODE);
            values.put("subjectKind", sponsor ? SPONSOR : USER);
            values.put("litId", litId);
            values.put("treeId", String.valueOf((Object) lit.get("treeId")));
            values.put("nodeId", String.valueOf((Object) lit.get("nodeId")));
            values.put("heldFrom", CertificateProcesses.instant(lit.get("litAt")));
            values.put("holderName", holderName(ctx, sponsor));
            values.put("certificateNames", lit.get("certificateNames"));
            values.put("issuerName", issuerName(ctx, tree, messages));
            values.put("verifyKey", verifyKey(seed, litId));
            values.put("shared", true);
            values.put("issuedAt", ctx.opTime());
            ctx.changes().insert(QbCertificates.CERTIFICATE, values);
            written++;
        }
        ctx.put(WRITTEN, written);
    }

    /** The holder's name as shown now: a Taker's display name, a Sponsor's company; {@code -} when there is none. */
    private static String holderName(ProcessContext ctx, boolean sponsor) {
        EntityInstance row = sponsor ? first(ctx, SPONSOR_SELF) : first(ctx, PROFILE);
        Object name = row == null ? null : row.get(sponsor ? "company" : "displayName");
        return name == null || String.valueOf(name).isBlank() ? "-" : String.valueOf(name).strip();
    }

    /**
     * The issuer: the owner of the graph the node is in. The platform's graph: the controlled parameter
     * {@code qb.issuer.name}, the platform's name in the messages while it is not set; an operator's copy: the
     * operator; a Sponsor's own graph: their company.
     */
    private static String issuerName(ProcessContext ctx, EntityInstance tree, MessageCatalog messages) {
        String owner = tree == null ? null : String.valueOf((Object) tree.get("ownerId"));
        String name = null;
        if (tree != null && QbTrees.OPERATOR.equals(tree.get("ownerKind"))) {
            name = list(ctx, OPERATORS).stream().filter(row -> String.valueOf(row.id()).equals(owner))
                .map(row -> (String) row.get("name")).findFirst().orElse(null);
        } else if (tree != null && QbTrees.SPONSOR.equals(tree.get("ownerKind"))) {
            name = list(ctx, OWNERS).stream()
                .filter(row -> String.valueOf((Object) row.get("userId")).equals(owner))
                .map(row -> (String) row.get("company")).findFirst().orElse(null);
        } else {
            ParamValues params = ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class) : null;
            name = params == null ? null : params.find(QbParams.ISSUER_NAME, String.class).orElse(null);
        }
        if (name == null || name.isBlank()) {
            name = messages.find(PLATFORM_ISSUER_KEY, Locale.ENGLISH).orElse("QuizBuks");
        }
        return name.strip();
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key, Object.class) : null;
        return value instanceof List<?> rows ? (List<EntityInstance>) rows : new ArrayList<>();
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.get(0);
    }
}

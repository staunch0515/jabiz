package com.jabiz.quizbuks.help;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.LegalRules;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.StartExternalJob;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The AI help's materials (F-C10b, docs/quizbuks/plans/impl/F-C10.md §6):
 * <ul>
 *   <li>{@value #PUBLISH}: a content administrator publishes a manual's draft (all three languages; every snapshot of
 *       its entry within {@code qb.help.manual-max-bytes}, else 422 and nothing changes), or, without a manual, asks
 *       for the snapshots to be built again now (after editing the FAQ). Either way the snapshots are rebuilt (the
 *       event {@value QbHelpMaterials#CHANGED_EVENT});</li>
 *   <li>{@value #LOAD}: a content administrator loads the repository's text of a manual into its draft (a feature's
 *       pull request changed it), refused when the draft was edited since the repository's text was last loaded,
 *       unless told to replace it; or records that the draft already holds it, merged by hand;</li>
 *   <li>{@value #BUILD} (system): builds every entry's and language's snapshot from the published sources and stores
 *       each that changed ({@value HelpMaterialsJob#TYPE}); run by the event and every hour (FAQ edits and legal texts
 *       taking effect later need no publishing here);</li>
 *   <li>{@value #STORED} / {@value #NOT_STORED} (system): the job's outcomes; a stored snapshot is put in use unless a
 *       later one already is.</li>
 * </ul>
 */
public final class HelpMaterialsProcesses {

    private static final Logger log = LoggerFactory.getLogger(HelpMaterialsProcesses.class);

    public static final String PUBLISH = "QB_HELP_PUBLISH";
    public static final String LOAD = "QB_HELP_MANUAL_LOAD";
    public static final String BUILD = "QB_HELP_MATERIALS_BUILD";
    public static final String STORED = "QB_HELP_MATERIALS_STORED";
    public static final String NOT_STORED = "QB_HELP_MATERIALS_NOT_STORED";

    /** The hourly build's job and its time (minute 41 of every hour). */
    public static final String BUILD_JOB = "qb.help-materials";
    public static final String BUILD_CRON = "0 41 * * * *";

    /** A snapshot being stored for longer than this is built again (its job may have been stopped). */
    static final Duration PENDING_FOR = Duration.ofMinutes(30);

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String MANUAL = "manual";
    static final String MANUALS = "manuals";
    static final String MATERIALS = "materials";
    static final String FAQS = "faqs";
    static final String VERSIONS = "versions";
    static final String WITHDRAWALS = "withdrawals";
    static final String OUTPUT = "output";
    static final String JOBS = "jobs";

    private static final String UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /** Every entry and language, in order (the build's job steps, one each). */
    static final List<String[]> KEYS;

    static {
        List<String[]> keys = new ArrayList<>();
        for (String entry : QbHelp.ENTRIES) {
            for (String language : QbHelpMaterials.LANGUAGES) {
                keys.add(new String[] {entry, language});
            }
        }
        KEYS = List.copyOf(keys);
    }

    /**
     * @param manualId     the manual to publish; none to rebuild the snapshots only (after editing the FAQ)
     * @param baseRevision the draft's version the publisher read (required with a manual); no longer current: 422
     */
    public record PublishInput(@Pattern(regexp = UUID_PATTERN) String manualId, Long baseRevision) {}

    /** The size of one snapshot, in bytes of UTF-8. */
    public record Size(String entry, String language, long bytes) {}

    /** @param sizes every entry's and language's snapshot as it is built now, with the limit in effect */
    public record PublishOutput(String manualId, String entry, Instant publishedAt, long limit, List<Size> sizes) {}

    /**
     * @param keepDraft whether the draft is kept as it is and only recorded as holding the repository's text (merged by
     *                  hand); otherwise the repository's text replaces it
     * @param replace   whether the repository's text replaces a draft edited since it was last loaded
     */
    public record LoadInput(@NotNull @Pattern(regexp = UUID_PATTERN) String manualId, @NotNull Long baseRevision,
        Boolean keepDraft, Boolean replace) {

        boolean keeps() {
            return Boolean.TRUE.equals(keepDraft);
        }

        boolean replaces() {
            return Boolean.TRUE.equals(replace);
        }
    }

    public record LoadOutput(String manualId, String entry, String sourceHash, boolean replaced) {}

    public record BuildInput(Instant scheduledTime) {}

    /** @param started the snapshots being stored now ({@code entry/language}); too large: not stored */
    public record BuildOutput(List<String> started, List<String> tooLarge) {}

    public record StoredOutput(String entry, String language, boolean inUse) {}

    private HelpMaterialsProcesses() {}

    // ---- publish ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublishInput, PublishOutput, ProcessContext> publish() {
        return ProcessDefinition.define(PUBLISH, 1, PublishInput.class, PublishOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Publishes the AI help's usage manual of an entry, or has its materials built again.")
                    .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                    .requiresVerifiedEmail()
                    .actsOn(QbHelpMaterials.MANUAL, "manualId")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, PublishOutput.class))
                    .step("Take the materials' turn", HoldLock.<ProcessContext>exclusive(ctx -> QbHelpMaterials.LOCK));
                sources(pb);
                pb.compute("Publish", (metadata, ctx) -> published(ctx))
                    .step("Build the materials again", PublishEvent.<ProcessContext>of(QbHelpMaterials.CHANGED_EVENT,
                        ctx -> Map.of("reason", ctx.get(INPUT, PublishInput.class).manualId() == null ? "refresh"
                            : "manual")));
            });
    }

    /** The sources of the snapshots, the manuals and the snapshots' rows in one statement, and the size limit. */
    static void sources(ProcessDefinitionBuilder<?, ?, ProcessContext> pb) {
        pb.step("Load the size limit", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                QbParams.HELP_MANUAL_MAX_BYTES))
            .step("Load the FAQ, the legal texts, the manuals and the snapshots", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbLegal.defaultDataset(QbLegal.FAQ), ctx -> all(new QueryPredicate.Eq(
                    "published", true)), FAQS),
                QueryEntities.query(QbLegal.defaultDataset(QbLegal.VERSION), ctx -> all(new QueryPredicate.In("kind",
                    legalKinds())), VERSIONS),
                QueryEntities.query(QbLegal.defaultDataset(QbLegal.WITHDRAWAL), ctx -> all(new QueryPredicate.In(
                    "kind", legalKinds())), WITHDRAWALS),
                QueryEntities.query(QbHelpMaterials.defaultDataset(QbHelpMaterials.MANUAL), ctx -> all(null),
                    MANUALS),
                QueryEntities.query(QbHelpMaterials.defaultDataset(QbHelpMaterials.MATERIAL), ctx -> all(null),
                    MATERIALS)));
    }

    private static List<Object> legalKinds() {
        Set<Object> kinds = new java.util.LinkedHashSet<>();
        QbHelp.ENTRIES.forEach(entry -> kinds.addAll(HelpMaterialsText.legalKinds(entry)));
        return new ArrayList<>(kinds);
    }

    private static EntityQuery all(QueryPredicate predicate) {
        EntityQuery.Builder query = EntityQuery.builder().limit(Integer.MAX_VALUE);
        return predicate == null ? query.build() : query.where(predicate).build();
    }

    static void published(ProcessContext ctx) {
        PublishInput input = ctx.get(INPUT, PublishInput.class);
        long limit = limit(ctx);
        Map<String, Map<?, ?>> manuals = publishedManuals(ctx);
        EntityInstance manual = null;
        if (input.manualId() != null) {
            manual = HelpProcesses.list(ctx, MANUALS).stream()
                .filter(row -> input.manualId().equalsIgnoreCase(String.valueOf(row.id()))).findFirst()
                .orElseThrow(() -> new EntityNotFoundException(QbHelpMaterials.MANUAL + " "
                    + input.manualId() + " not found"));
            if (input.baseRevision() == null || input.baseRevision() != manual.version()) {
                throw HelpProcesses.refused(HelpCodes.MANUAL_CHANGED, "The manual changed meanwhile: version "
                    + manual.version() + ", not " + input.baseRevision(), Map.of("revision", manual.version()));
            }
            Map<?, ?> body = manual.get("body");
            List<Violation> problems = new ArrayList<>();
            for (String language : LegalRules.missingLanguages(body, QbHelpMaterials.LANGUAGES)) {
                problems.add(new Violation("body", HelpCodes.MANUAL_LANGUAGE, "The manual in " + language
                    + " is missing", Map.of("lang", language)));
            }
            if (!problems.isEmpty()) {
                throw new BusinessRuleViolationException(problems);
            }
            manuals.put(String.valueOf((Object) manual.get("entry")), body);
        }
        List<Size> sizes = new ArrayList<>();
        List<Violation> tooLarge = new ArrayList<>();
        for (String[] key : KEYS) {
            long bytes = HelpMaterialsText.bytes(text(ctx, key[0], key[1], manuals)).length;
            sizes.add(new Size(key[0], key[1], bytes));
            if (manual != null && key[0].equals(manual.get("entry")) && bytes > limit) {
                tooLarge.add(new Violation("body", HelpCodes.MATERIALS_TOO_LARGE, "The materials of " + key[0] + "/"
                    + key[1] + " would be " + bytes + " bytes, more than " + limit, Map.of("entry", key[0],
                    "lang", key[1], "bytes", bytes, "limit", limit)));
            }
        }
        if (!tooLarge.isEmpty()) {
            throw new BusinessRuleViolationException(tooLarge);
        }
        if (manual != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("publishedBody", manual.get("body"));
            values.put("publishedAt", ctx.opTime());
            ctx.changes().update(QbHelpMaterials.MANUAL, manual.id(), manual.version(), values);
        }
        ctx.put(OUTPUT, new PublishOutput(manual == null ? null : String.valueOf(manual.id()),
            manual == null ? null : manual.get("entry"), manual == null ? null : ctx.opTime(), limit,
            List.copyOf(sizes)));
    }

    // ---- load the repository's text --------------------------------------------------------------------------------

    public static ProcessDefinition<LoadInput, LoadOutput, ProcessContext> load() {
        return ProcessDefinition.define(LOAD, 1, LoadInput.class, LoadOutput.class, ProcessContext.class, pb -> pb
            .description("Loads the repository's text of an AI help usage manual into its draft.")
            .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
            .requiresVerifiedEmail()
            .actsOn(QbHelpMaterials.MANUAL, "manualId")
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, LoadOutput.class))
            .step("Load the manual", QueryEntities.<ProcessContext>of(
                QbHelpMaterials.defaultDataset(QbHelpMaterials.MANUAL),
                ctx -> HelpProcesses.byId("manualId", ctx.get(INPUT, LoadInput.class).manualId()), MANUAL))
            .compute("Load the repository's text", (metadata, ctx) -> loaded(ctx)));
    }

    static void loaded(ProcessContext ctx) {
        LoadInput input = ctx.get(INPUT, LoadInput.class);
        EntityInstance manual = HelpProcesses.first(ctx, MANUAL);
        if (manual == null) {
            throw new EntityNotFoundException(QbHelpMaterials.MANUAL + " " + input.manualId()
                + " not found");
        }
        if (input.baseRevision() != manual.version()) {
            throw HelpProcesses.refused(HelpCodes.MANUAL_CHANGED, "The manual changed meanwhile: version "
                + manual.version() + ", not " + input.baseRevision(), Map.of("revision", manual.version()));
        }
        String entry = manual.get("entry");
        Map<String, String> source = HelpManualDrafts.of(entry);
        String sourceHash = HelpManualDrafts.hash(source);
        Map<?, ?> body = manual.get("body");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceHash", sourceHash);
        boolean replaced = false;
        if (!input.keeps()) {
            // Edited here since the repository's text was last loaded: replacing it would lose those edits.
            boolean edited = !Objects.equals(HelpManualDrafts.hash(body), manual.get("sourceHash"));
            if (edited && !input.replaces() && !HelpManualDrafts.hash(body).equals(sourceHash)) {
                throw HelpProcesses.refused(HelpCodes.MANUAL_EDITED, "The draft was edited since the repository's "
                    + "text was last loaded", Map.of());
            }
            values.put("body", source);
            replaced = true;
        }
        ctx.changes().update(QbHelpMaterials.MANUAL, manual.id(), manual.version(), values);
        ctx.put(OUTPUT, new LoadOutput(String.valueOf(manual.id()), entry, sourceHash, replaced));
    }

    // ---- build -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<BuildInput, BuildOutput, ProcessContext> build() {
        return ProcessDefinition.define(BUILD, 1, BuildInput.class, BuildOutput.class, ProcessContext.class, pb -> {
            pb.description("Builds the AI help's materials snapshots and stores those that changed.")
                .permissions(QbPermissions.HELP_SYSTEM)
                .internal()
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> ctx.get(OUTPUT, BuildOutput.class))
                .step("Take the materials' turn", HoldLock.<ProcessContext>exclusive(ctx -> QbHelpMaterials.LOCK));
            sources(pb);
            pb.compute("Build", (metadata, ctx) -> built(ctx));
            for (int i = 0; i < KEYS.size(); i++) {
                int index = i;
                pb.step("Store " + KEYS.get(i)[0] + "/" + KEYS.get(i)[1], StartExternalJob.<ProcessContext>of(
                        HelpMaterialsJob.TYPE, ctx -> job(ctx, index))
                    .when(ctx -> job(ctx, index) != null)
                    .reference(ctx -> QbHelpMaterials.reference(KEYS.get(index)[0], KEYS.get(index)[1])));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static HelpMaterialsJob.Input job(ProcessContext ctx, int index) {
        List<HelpMaterialsJob.Input> jobs = ctx.contains(JOBS) ? (List<HelpMaterialsJob.Input>) ctx.get(JOBS) : null;
        return jobs == null ? null : jobs.get(index);
    }

    static void built(ProcessContext ctx) {
        long limit = Math.min(limit(ctx), QbHelpMaterials.MAX_FILE_BYTES);
        Map<String, Map<?, ?>> manuals = publishedManuals(ctx);
        Map<String, EntityInstance> rows = new LinkedHashMap<>();
        for (EntityInstance row : HelpProcesses.list(ctx, MATERIALS)) {
            rows.put(QbHelpMaterials.reference(row.get("entry"), row.get("language")), row);
        }
        List<HelpMaterialsJob.Input> jobs = new ArrayList<>();
        List<String> started = new ArrayList<>();
        List<String> tooLarge = new ArrayList<>();
        Instant now = ctx.opTime();
        for (String[] key : KEYS) {
            String reference = QbHelpMaterials.reference(key[0], key[1]);
            String text = text(ctx, key[0], key[1], manuals);
            String hash = HelpMaterialsText.hash(text);
            long bytes = HelpMaterialsText.bytes(text).length;
            EntityInstance row = rows.get(reference);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("latestHash", hash);
            values.put("latestBytes", BigDecimal.valueOf(bytes));
            values.put("latestAt", now);
            HelpMaterialsJob.Input job = null;
            if (bytes > limit) {
                // Kept as it was: the startup check QB_HELP_MATERIALS reports it (entry and language only, D16).
                log.warn("AI help materials of {} are {} bytes, above {}: not stored", reference, bytes, limit);
                tooLarge.add(reference);
            } else if (row != null && hash.equals(row.get("contentHash"))) {
                values.put("pendingHash", null);
                values.put("pendingAt", null);
            } else if (!beingStored(row, hash, now)) {
                job = new HelpMaterialsJob.Input(key[0], key[1], hash, now, text);
                values.put("pendingHash", hash);
                values.put("pendingAt", now);
                started.add(reference);
            }
            jobs.add(job);
            if (row == null) {
                values.put("entry", key[0]);
                values.put("language", key[1]);
                ctx.changes().insert(QbHelpMaterials.MATERIAL, values);
            } else if (changes(row, values)) {
                ctx.changes().update(QbHelpMaterials.MATERIAL, row.id(), row.version(), values);
            }
        }
        ctx.put(JOBS, jobs);
        ctx.put(OUTPUT, new BuildOutput(List.copyOf(started), List.copyOf(tooLarge)));
    }

    /** Whether the snapshot of {@code hash} is being stored already (its job started a short while ago). */
    private static boolean beingStored(EntityInstance row, String hash, Instant now) {
        return row != null && hash.equals(row.get("pendingHash")) && row.get("pendingAt") != null
            && HelpProcesses.instant(row.get("pendingAt")).plus(PENDING_FOR).isAfter(now);
    }

    /** Whether writing {@code values} changes the row (an hourly build of unchanged sources writes nothing). */
    private static boolean changes(EntityInstance row, Map<String, Object> values) {
        for (Map.Entry<String, Object> value : values.entrySet()) {
            if ("latestAt".equals(value.getKey())) {
                continue;
            }
            Object now = row.get(value.getKey());
            Object then = value.getValue();
            if (then instanceof BigDecimal number ? now == null || number.compareTo(new BigDecimal(now.toString())) != 0
                : !Objects.equals(now == null ? null : now.toString(), then == null ? null : then.toString())) {
                return true;
            }
        }
        return false;
    }

    // ---- the job's outcomes ----------------------------------------------------------------------------------------

    public static ProcessDefinition<ExternalJobCompletion, StoredOutput, ProcessContext> stored() {
        return ProcessDefinition.define(STORED, 1, ExternalJobCompletion.class, StoredOutput.class,
            ProcessContext.class, pb -> pb
                .description("Puts a stored AI help materials snapshot in use.")
                .permissions(QbPermissions.HELP_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, StoredOutput.class))
                .step("Take the materials' turn", HoldLock.<ProcessContext>exclusive(ctx -> QbHelpMaterials.LOCK))
                .step("Load the snapshot's row", QueryEntities.<ProcessContext>of(
                    QbHelpMaterials.defaultDataset(QbHelpMaterials.MATERIAL), ctx -> rowOf(
                        ctx.get(INPUT, ExternalJobCompletion.class).reference()), MATERIALS))
                .compute("Put it in use", (metadata, ctx) -> stored(ctx)));
    }

    static void stored(ProcessContext ctx) {
        ExternalJobCompletion completion = ctx.get(INPUT, ExternalJobCompletion.class);
        HelpMaterialsJob.Result result = completion.result(HelpMaterialsJob.Result.class);
        EntityInstance row = HelpProcesses.first(ctx, MATERIALS);
        boolean inUse = false;
        if (row != null && result != null) {
            Instant current = HelpProcesses.instant(row.get("builtAt"));
            Map<String, Object> values = new LinkedHashMap<>();
            if (current == null || !result.builtAt().isBefore(current)) {
                values.put("fileId", completion.file(HelpMaterialsJob.FILE));
                values.put("contentHash", result.contentHash());
                values.put("bytes", BigDecimal.valueOf(result.bytes()));
                values.put("builtAt", result.builtAt());
                inUse = true;
            }
            if (result.contentHash().equals(row.get("pendingHash"))) {
                values.put("pendingHash", null);
                values.put("pendingAt", null);
            }
            if (!values.isEmpty()) {
                ctx.changes().update(QbHelpMaterials.MATERIAL, row.id(), row.version(), values);
            }
        }
        ctx.put(OUTPUT, new StoredOutput(row == null ? null : row.get("entry"),
            row == null ? null : row.get("language"), inUse));
    }

    public static ProcessDefinition<ExternalJobFailure, StoredOutput, ProcessContext> notStored() {
        return ProcessDefinition.define(NOT_STORED, 1, ExternalJobFailure.class, StoredOutput.class,
            ProcessContext.class, pb -> pb
                .description("Records that an AI help materials snapshot was not stored, so that it is built again.")
                .permissions(QbPermissions.HELP_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, StoredOutput.class))
                .step("Take the materials' turn", HoldLock.<ProcessContext>exclusive(ctx -> QbHelpMaterials.LOCK))
                .step("Load the snapshot's row", QueryEntities.<ProcessContext>of(
                    QbHelpMaterials.defaultDataset(QbHelpMaterials.MATERIAL), ctx -> rowOf(
                        ctx.get(INPUT, ExternalJobFailure.class).reference()), MATERIALS))
                .compute("Let the next build store it", (metadata, ctx) -> {
                    EntityInstance row = HelpProcesses.first(ctx, MATERIALS);
                    if (row != null && row.get("pendingHash") != null) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("pendingHash", null);
                        values.put("pendingAt", null);
                        ctx.changes().update(QbHelpMaterials.MATERIAL, row.id(), row.version(), values);
                    }
                    ctx.put(OUTPUT, new StoredOutput(row == null ? null : row.get("entry"),
                        row == null ? null : row.get("language"), false));
                }));
    }

    private static EntityQuery rowOf(String reference) {
        String[] parts = reference == null ? new String[0] : reference.split("/", 2);
        if (parts.length != 2) {
            return EntityQuery.builder().where(new QueryPredicate.In("entry", List.of())).limit(1).build();
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("entry", parts[0]),
            new QueryPredicate.Eq("language", parts[1])))).limit(1).build();
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** {@code qb.help.manual-max-bytes} in effect (its declared value while it is not there). */
    static long limit(ProcessContext ctx) {
        return HelpSettings.number(ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class) : null,
            QbParams.HELP_MANUAL_MAX_BYTES);
    }

    /** The published manual of each entry, by entry. */
    private static Map<String, Map<?, ?>> publishedManuals(ProcessContext ctx) {
        Map<String, Map<?, ?>> manuals = new LinkedHashMap<>();
        for (EntityInstance row : HelpProcesses.list(ctx, MANUALS)) {
            Map<?, ?> body = row.get("publishedBody");
            if (body != null) {
                manuals.put(String.valueOf((Object) row.get("entry")), body);
            }
        }
        return manuals;
    }

    /** The snapshot of an entry and language from the sources the steps read. */
    static String text(ProcessContext ctx, String entry, String language, Map<String, Map<?, ?>> manuals) {
        List<HelpMaterialsText.Faq> faqs = new ArrayList<>();
        for (EntityInstance row : HelpProcesses.list(ctx, FAQS)) {
            faqs.add(new HelpMaterialsText.Faq(row.get("audience"), HelpProcesses.number(row.get("seq")),
                row.get("question"), row.get("answer")));
        }
        return HelpMaterialsText.build(entry, language, manuals.get(entry), faqs, inEffect(ctx));
    }

    /** The version in effect of each legal text, now. */
    static List<HelpMaterialsText.Legal> inEffect(ProcessContext ctx) {
        Set<String> withdrawn = new HashSet<>();
        HelpProcesses.list(ctx, WITHDRAWALS).forEach(row -> withdrawn.add(String.valueOf((Object) row.get(
            "legalVersionId"))));
        Map<String, List<EntityInstance>> byKind = new LinkedHashMap<>();
        for (EntityInstance row : HelpProcesses.list(ctx, VERSIONS)) {
            byKind.computeIfAbsent(row.get("kind"), kind -> new ArrayList<>()).add(row);
        }
        List<HelpMaterialsText.Legal> legal = new ArrayList<>();
        byKind.forEach((kind, rows) -> {
            List<LegalRules.Version> versions = new ArrayList<>();
            Map<Integer, EntityInstance> byNumber = new LinkedHashMap<>();
            for (EntityInstance row : rows) {
                int number = (int) HelpProcesses.number(row.get("versionNumber"));
                byNumber.put(number, row);
                versions.add(new LegalRules.Version(number, Boolean.TRUE.equals(row.get("requiresConsent")),
                    HelpProcesses.instant(row.get("effectiveAt")), row.get("contentHash"),
                    withdrawn.contains(String.valueOf(row.id()))));
            }
            LegalRules.current(versions, ctx.opTime()).ifPresent(version -> {
                EntityInstance row = byNumber.get(version.number());
                legal.add(new HelpMaterialsText.Legal(kind, version.number(), version.effectiveAt(), row.get("title"),
                    row.get("body")));
            });
        });
        return legal;
    }
}

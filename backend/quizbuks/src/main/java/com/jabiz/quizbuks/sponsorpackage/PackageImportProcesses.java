package com.jabiz.quizbuks.sponsorpackage;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.audience.AudienceCriteria;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuestionImportProcesses;
import com.jabiz.quizbuks.content.QuizProcesses;
import com.jabiz.quizbuks.content.QuizTopics;
import com.jabiz.quizbuks.content.transfer.QuizTransferProcesses;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.page.ProfileTransferProcesses;
import com.jabiz.quizbuks.publishing.PublicationAudience;
import com.jabiz.quizbuks.publishing.PublicationProcesses;
import com.jabiz.quizbuks.publishing.PublicationSharedVideos;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.PublicationTopics;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.SaveOutput;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.topic.TopicRules;
import com.jabiz.quizbuks.tree.QbTrees;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Importing a sponsor package (F-C4, docs/quizbuks/plans/impl/F-C4.md §2). The console reads the package and plans the
 * steps; the server keeps the record of the import and runs each step:
 * <ul>
 *   <li>{@value #START}: opens the import of a package (by its hash), or goes on with the open import of the same
 *       package, answering the steps done and what they made. Only an account that never submitted a publication
 *       imports the whole package ({@code QB_PACKAGE_SUBMITTED}); one open import per Sponsor
 *       ({@code QB_PACKAGE_IMPORT_OPEN}).</li>
 *   <li>{@value #STEP}: one step, run as the processes the hub's pages run (sub-processes, same rules as by hand) in
 *       the same transaction as its record: a step done is recorded, one not recorded was not done, so going on never
 *       makes anything twice; a step recorded already answers what it made and does nothing. The last step finishes
 *       the import.</li>
 *   <li>{@value #ABANDON}: gives the import up, removing through the existing processes what it made that is still a
 *       draft (graphs never in effect retired, draft publications deleted, quizzes no submitted publication uses
 *       removed); the profile part stays.</li>
 *   <li>{@value #EXPIRE}: the daily job's item, deleting a record past {@code qb.package.import-retention-days}.</li>
 * </ul>
 * Locks: the Sponsor's {@code qb.package-import:<sponsor>} first, then whatever the steps' processes take.
 */
public final class PackageImportProcesses {

    public static final String START = "QB_PACKAGE_IMPORT_START";
    public static final String STEP = "QB_PACKAGE_IMPORT_STEP";
    public static final String ABANDON = "QB_PACKAGE_IMPORT_ABANDON";
    public static final String EXPIRE = "QB_PACKAGE_IMPORT_EXPIRE";
    public static final String EXPIRE_JOB = "qb.package-import-expire";
    public static final String EXPIRED_TEMPLATE = "qb.system.package-imports-expired";

    /**
     * @param packageHash   the SHA-256 of the package's bytes, in hex
     * @param formatVersion the package's format version (1 or 2)
     * @param mode          {@code FULL} or {@code PROFILE}
     * @param batchSize     the questions per batch of a large bank (the console's {@code qb.content.import.batch-size});
     *                      an import gone on with keeps its own
     * @param batchBytes    the bytes of one request of a large bank (the console's {@code
     *                      qb.content.import.batch-bytes}, F-F11a); an import gone on with keeps its own; none from an
     *                      older console
     * @param stepsTotal    how many steps the console planned
     */
    public record StartInput(@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String packageHash,
        @Min(1) @Max(2) int formatVersion, @NotBlank @Pattern(regexp = "FULL|PROFILE") String mode,
        @Min(1) @Max(PackageImportRules.MAX_BATCH) int batchSize,
        @Min(ContentLimits.IMPORT_BATCH_BYTES_MIN) @Max(ContentLimits.IMPORT_BATCH_BYTES_TECH) Integer batchBytes,
        @Min(1) @Max(PackageImportRules.MAX_STEPS) int stepsTotal) {}

    /**
     * An import as the console sees it.
     *
     * @param batchBytes the bytes of a request the import was planned by; none for an import that started before F-F11a
     * @param done    the steps done, each with what it made
     * @param resumed whether {@value #START} went on with an open import
     */
    public record ImportView(UUID importId, String mode, String status, int batchSize, Integer batchBytes,
        int stepsTotal, int stepsDone, Map<String, Map<String, Object>> done, boolean resumed) {}

    /** A quiz's topics, set with its content (F-S9). */
    public record QuizTopicsInput(
        @NotNull @Size(max = TopicRules.HARD_MAX) List<@NotNull @Size(max = TopicRules.CODE_LENGTH) String> topics,
        @Size(max = TopicRules.HARD_MAX)
        List<@NotNull @Size(max = TopicRules.CUSTOM_HARD_MAX_LENGTH * 2) String> customTopics) {}

    /** A publication's changes to its version's topics (F-S9). */
    public record PublicationTopicsInput(
        @Size(max = TopicRules.HARD_MAX) List<@NotNull @Size(max = TopicRules.CODE_LENGTH) String> add,
        @Size(max = TopicRules.HARD_MAX) List<@NotNull @Size(max = TopicRules.CODE_LENGTH) String> remove,
        @Size(max = TopicRules.HARD_MAX)
        List<@NotNull @Size(max = TopicRules.CUSTOM_HARD_MAX_LENGTH * 2) String> customAdd,
        @Size(max = TopicRules.HARD_MAX)
        List<@NotNull @Size(max = TopicRules.CUSTOM_HARD_MAX_LENGTH * 2) String> customRemove) {}

    /** A graph: created as the Sponsor's own, then its draft saved with every node (new nodes named by their keys). */
    public record TreeStepInput(@NotNull @Valid TreeProcesses.CreateInput create,
        @NotNull @Size(max = 1000) List<TreeProcesses.@Valid @NotNull NodeInput> nodes) {}

    /**
     * A publication: a new draft ({@code save} without a {@code publicationId}), then its matching conditions, its
     * topics and the shared video it picks, each when given.
     */
    public record PublicationStepInput(@NotNull @Valid PublicationProcesses.SaveInput save,
        @Size(max = 20) List<AudienceCriteria.@Valid @NotNull ConditionInput> criteria,
        @Valid PublicationTopicsInput topics, @Size(max = 64) String sharedVideoId) {}

    /**
     * One step of the import: exactly one action.
     *
     * @param profile     the profile part (Q100's import)
     * @param quiz        a quiz's content: a new draft, or (with {@code intoQuizId}) the next content of one made
     *                    before, with its topics when given ({@code quizTopics})
     * @param questions   a further batch of a large bank (F-S6)
     * @param clear       a part of the content of a quiz made before deleted, ahead of the step that brings its next
     *                    content (F-F11a: a large bank is deleted in steps of a few seconds each)
     * @param version     a version saved of a quiz made before
     * @param tree        a graph
     * @param publication a publication
     */
    public record StepInput(@NotNull UUID importId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9:._/-]{1,200}") String step,
        @Valid ProfileTransferProcesses.ImportInput profile, @Valid QuizTransferProcesses.ImportInput quiz,
        @Valid QuizTopicsInput quizTopics, @Valid QuestionImportProcesses.ImportInput questions,
        @Valid QuizProcesses.ClearInput clear, @Valid QuizProcesses.QuizRef version, @Valid TreeStepInput tree, @Valid PublicationStepInput publication) {}

    /**
     * @param result   what the step made (ids only)
     * @param repeated whether the step was done before (nothing was done now)
     */
    public record StepOutput(UUID importId, String step, String status, int stepsDone, int stepsTotal,
        Map<String, Object> result, boolean repeated) {}

    public record ImportRef(@NotNull UUID importId) {}

    /**
     * @param retiredTrees        graphs the import made, retired
     * @param deletedPublications draft publications it made, deleted
     * @param removedQuizzes      quizzes it made, removed
     * @param keptQuizzes         quizzes it made that a submitted publication uses, kept
     */
    public record AbandonOutput(UUID importId, String status, List<String> retiredTrees,
        List<String> deletedPublications, List<String> removedQuizzes, List<String> keptQuizzes) {}

    public record ExpireOutput(boolean deleted) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RECORD = "record";
    static final String OPEN = "open";
    static final String SUBMITTED = "submitted";
    static final String ACTION = "action";
    static final String REPEATED = "repeated";
    static final String PROFILE_OUT = "profileOut";
    static final String QUIZ_OUT = "quizOut";
    static final String QUESTIONS_OUT = "questionsOut";
    static final String CLEAR_OUT = "clearOut";
    static final String VERSION_OUT = "versionOut";
    static final String TREE_CREATED = "treeCreated";
    static final String TREE_SAVED = "treeSaved";
    static final String PUBLICATION_OUT = "publicationOut";
    static final String TREES = "trees";
    static final String PUBLICATIONS = "publications";
    static final String QUIZZES = "quizzes";
    static final String QUIZ_PUBLICATIONS = "quizPublications";
    static final String PLAN = "abandonPlan";
    static final String PARAMS = "params";

    /** The kinds of step. */
    enum Action { PROFILE, QUIZ, QUESTIONS, CLEAR, VERSION, TREE, PUBLICATION }

    private PackageImportProcesses() {}

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    // ---- start -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<StartInput, ImportView, ProcessContext> start() {
        return ProcessDefinition.define(START, 1, StartInput.class, ImportView.class, ProcessContext.class, pb -> pb
            .description("Starts importing a sponsor package, or goes on with the open import of the same package.")
            .permissions(QbPermissions.CONTENT_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory(PackageImportProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, ImportView.class))
            .step("Lock the Sponsor's imports", HoldLock.<ProcessContext>exclusive(
                ctx -> QbPackageImports.lockName(ctx.request().actorId())))
            .step("Load the open import and a submitted publication", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbPackageImports.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                        new QueryPredicate.Eq("status", QbPackageImports.RUNNING)))).limit(1).build(), OPEN),
                QueryEntities.query(QbContent.defaultDataset(QbPublications.PUBLICATION), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("publisherKind", PublisherKind.SPONSOR),
                        new QueryPredicate.Eq("publisherId", ctx.request().actorId()),
                        new QueryPredicate.Or(List.of(
                            new QueryPredicate.Ne("status", PublicationStatus.DRAFT),
                            new QueryPredicate.IsNotNull("submittedAt")))))).limit(1).build(), SUBMITTED)))
            .compute("Open or go on", (metadata, ctx) -> started(ctx)));
    }

    static void started(ProcessContext ctx) {
        StartInput input = ctx.get(INPUT, StartInput.class);
        EntityInstance open = first(ctx, OPEN);
        if (open != null) {
            if (!input.packageHash().equals(open.get("packageHash")) || !input.mode().equals(open.get("mode"))) {
                ctx.reject(new Violation("packageHash", PackageImportCodes.OPEN, "Another package's import is not "
                    + "finished: go on with it or give it up first", Map.of("importId", String.valueOf(open.id()))));
                return;
            }
            // Going on: the console planned the same package again (a changed count is its to know).
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("updatedAt", ctx.opTime());
            values.put("stepsTotal", BigDecimal.valueOf(input.stepsTotal()));
            ctx.changes().update(QbPackageImports.IMPORT, open.id(), open.version(), values);
            ctx.put(OUTPUT, new ImportView(uuid(open.id()), input.mode(), QbPackageImports.RUNNING,
                number(open.get("batchSize")), open.get("batchBytes") == null ? null : number(open.get("batchBytes")),
                input.stepsTotal(), number(open.get("stepsDone")),
                PackageImportRules.done(open.get("done")), true));
            return;
        }
        if (QbPackageImports.FULL.equals(input.mode()) && first(ctx, SUBMITTED) != null) {
            ctx.reject(new Violation("mode", PackageImportCodes.SUBMITTED, "The account submitted a publication: only "
                + "the profile part can be imported", Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", ctx.request().actorId());
        values.put("packageHash", input.packageHash());
        values.put("formatVersion", BigDecimal.valueOf(input.formatVersion()));
        values.put("mode", input.mode());
        values.put("status", QbPackageImports.RUNNING);
        values.put("batchSize", BigDecimal.valueOf(input.batchSize()));
        if (input.batchBytes() != null) {
            values.put("batchBytes", BigDecimal.valueOf(input.batchBytes()));
        }
        values.put("stepsTotal", BigDecimal.valueOf(input.stepsTotal()));
        values.put("stepsDone", BigDecimal.ZERO);
        values.put("done", PackageImportRules.json(Map.of()));
        values.put("startedAt", ctx.opTime());
        values.put("updatedAt", ctx.opTime());
        Object importId = ctx.changes().insert(QbPackageImports.IMPORT, values);
        ctx.put(OUTPUT, new ImportView(uuid(importId), input.mode(), QbPackageImports.RUNNING, input.batchSize(),
            input.batchBytes(), input.stepsTotal(), 0, Map.of(), false));
    }

    // ---- step ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<StepInput, StepOutput, ProcessContext> step() {
        return ProcessDefinition.define(STEP, 1, StepInput.class, StepOutput.class, ProcessContext.class, pb -> pb
            .description("Runs one step of a sponsor package's import with the processes the pages run, and records "
                + "it in the same transaction.")
            .permissions(QbPermissions.CONTENT_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory(PackageImportProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, StepOutput.class))
            .step("Lock the Sponsor's imports", HoldLock.<ProcessContext>exclusive(
                ctx -> QbPackageImports.lockName(ctx.request().actorId())))
            .step("Load the import", QueryEntities.<ProcessContext>of(QbPackageImports.DEFAULT_DATASET,
                ctx -> own(ctx, ctx.get(INPUT, StepInput.class).importId()), RECORD))
            .compute("Check the step", (metadata, ctx) -> checkStep(ctx))
            .step("Import the profile", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.PROFILE),
                ProfileTransferProcesses.IMPORT, 1, ctx -> ctx.get(INPUT, StepInput.class).profile(), PROFILE_OUT))
            .step("Import the quiz's content", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.QUIZ),
                QuizTransferProcesses.IMPORT, 1, ctx -> ctx.get(INPUT, StepInput.class).quiz(), QUIZ_OUT))
            .step("Set the quiz's topics", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.QUIZ)
                    && ctx.get(INPUT, StepInput.class).quizTopics() != null, QuizTopics.SET, 1, ctx -> {
                    QuizTopicsInput topics = ctx.get(INPUT, StepInput.class).quizTopics();
                    return new QuizTopics.TopicsInput(
                        ctx.get(QUIZ_OUT, QuizTransferProcesses.ImportOutput.class).quizId(), topics.topics(),
                        topics.customTopics(), null);
                }, null))
            .step("Import a batch of questions", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.QUESTIONS),
                QuestionImportProcesses.IMPORT, 1, ctx -> ctx.get(INPUT, StepInput.class).questions(), QUESTIONS_OUT))
            .step("Delete part of a quiz's content", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.CLEAR),
                QuizProcesses.CLEAR_CONTENT, 1, ctx -> ctx.get(INPUT, StepInput.class).clear(), CLEAR_OUT))
            .step("Save a version", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.VERSION),
                QuizProcesses.PUBLISH_VERSION, 1, ctx -> ctx.get(INPUT, StepInput.class).version(), VERSION_OUT))
            .step("Create the graph", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.TREE),
                TreeProcesses.CREATE, 1, ctx -> ctx.get(INPUT, StepInput.class).tree().create(), TREE_CREATED))
            .step("Save the graph's draft", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.TREE),
                TreeProcesses.SAVE, 1, ctx -> {
                    TreeStepInput tree = ctx.get(INPUT, StepInput.class).tree();
                    TreeProcesses.TreeOutput created = ctx.get(TREE_CREATED, TreeProcesses.TreeOutput.class);
                    return new TreeProcesses.SaveInput(created.treeId(), created.revision(), tree.create().name(),
                        tree.create().description(), tree.nodes());
                }, TREE_SAVED))
            .step("Save the publication", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.PUBLICATION),
                PublicationProcesses.SAVE, 1, ctx -> ctx.get(INPUT, StepInput.class).publication().save(),
                PUBLICATION_OUT))
            .step("Save its conditions", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.PUBLICATION)
                    && notEmpty(ctx.get(INPUT, StepInput.class).publication().criteria()),
                PublicationAudience.SAVE, 1, ctx -> new PublicationAudience.AudienceInput(publicationId(ctx), null,
                    ctx.get(INPUT, StepInput.class).publication().criteria()), null))
            .step("Set its topics", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.PUBLICATION)
                    && ctx.get(INPUT, StepInput.class).publication().topics() != null, PublicationTopics.SET, 1,
                ctx -> {
                    PublicationTopicsInput topics = ctx.get(INPUT, StepInput.class).publication().topics();
                    return new PublicationTopics.TopicsInput(publicationId(ctx), topics.add(), topics.remove(),
                        topics.customAdd(), topics.customRemove(), null);
                }, null))
            .step("Pick its shared video", CallProcess.<ProcessContext>when(ctx -> runs(ctx, Action.PUBLICATION)
                    && ctx.get(INPUT, StepInput.class).publication().sharedVideoId() != null,
                PublicationSharedVideos.SET, 1, ctx -> new PublicationSharedVideos.SharedVideoInput(
                    publicationId(ctx), ctx.get(INPUT, StepInput.class).publication().sharedVideoId(), null), null))
            .compute("Record the step", (metadata, ctx) -> recorded(ctx)));
    }

    /** The caller's own import of this id (another's: none). */
    private static EntityQuery own(ProcessContext ctx, UUID importId) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("importId", String.valueOf(importId)),
            new QueryPredicate.Eq("sponsorId", ctx.request().actorId())))).limit(1).build();
    }

    private static boolean runs(ProcessContext ctx, Action action) {
        return !ctx.hasViolations() && !ctx.contains(REPEATED) && ctx.contains(ACTION) && ctx.get(ACTION) == action;
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private static UUID publicationId(ProcessContext ctx) {
        return ctx.get(PUBLICATION_OUT, SaveOutput.class).publicationId();
    }

    static void checkStep(ProcessContext ctx) {
        StepInput input = ctx.get(INPUT, StepInput.class);
        EntityInstance record = first(ctx, RECORD);
        if (record == null) {
            throw new EntityNotFoundException(QbPackageImports.IMPORT + " " + input.importId() + " not found");
        }
        if (!QbPackageImports.RUNNING.equals(record.get("status"))) {
            ctx.reject(new Violation("importId", PackageImportCodes.CLOSED, "The import is "
                + record.get("status"), Map.of("status", String.valueOf((Object) record.get("status")))));
            return;
        }
        Map<String, Map<String, Object>> done = PackageImportRules.done(record.get("done"));
        if (done.containsKey(input.step())) {
            ctx.put(REPEATED, done.get(input.step()));
            return;
        }
        List<Action> actions = new ArrayList<>();
        if (input.profile() != null) {
            actions.add(Action.PROFILE);
        }
        if (input.quiz() != null) {
            actions.add(Action.QUIZ);
        }
        if (input.questions() != null) {
            actions.add(Action.QUESTIONS);
        }
        if (input.clear() != null) {
            actions.add(Action.CLEAR);
        }
        if (input.version() != null) {
            actions.add(Action.VERSION);
        }
        if (input.tree() != null) {
            actions.add(Action.TREE);
        }
        if (input.publication() != null) {
            actions.add(Action.PUBLICATION);
        }
        boolean profileOnly = QbPackageImports.PROFILE.equals(record.get("mode"));
        boolean topicsAlone = input.quizTopics() != null && input.quiz() == null;
        boolean editsOld = input.publication() != null && input.publication().save().publicationId() != null;
        if (actions.size() != 1 || topicsAlone || editsOld || (profileOnly && actions.getFirst() != Action.PROFILE)) {
            ctx.reject(new Violation("step", PackageImportCodes.STEP_INVALID, "A step names exactly one action the "
                + "import takes", Map.of("step", input.step())));
            return;
        }
        ctx.put(ACTION, actions.getFirst());
    }

    static void recorded(ProcessContext ctx) {
        StepInput input = ctx.get(INPUT, StepInput.class);
        EntityInstance record = first(ctx, RECORD);
        if (ctx.hasViolations() || record == null) {
            return;
        }
        int total = number(record.get("stepsTotal"));
        int doneCount = number(record.get("stepsDone"));
        if (ctx.contains(REPEATED)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) ctx.get(REPEATED);
            ctx.put(OUTPUT, new StepOutput(uuid(record.id()), input.step(), String.valueOf((Object) record.get("status")),
                doneCount, total, result == null ? Map.of() : result, true));
            return;
        }
        Map<String, Object> result = result(ctx, (Action) ctx.get(ACTION));
        Map<String, Map<String, Object>> done = PackageImportRules.with(PackageImportRules.done(record.get("done")),
            input.step(), result);
        int count = doneCount + 1;
        boolean finished = PackageImportRules.finished(count, total);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("done", PackageImportRules.json(done));
        values.put("stepsDone", BigDecimal.valueOf(count));
        values.put("lastStep", input.step());
        values.put("updatedAt", ctx.opTime());
        if (finished) {
            values.put("status", QbPackageImports.DONE);
            values.put("finishedAt", ctx.opTime());
        }
        ctx.changes().update(QbPackageImports.IMPORT, record.id(), record.version(), values);
        ctx.put(OUTPUT, new StepOutput(uuid(record.id()), input.step(),
            finished ? QbPackageImports.DONE : QbPackageImports.RUNNING, count, total, result, false));
    }

    /** What a step made: ids only (no content, no secret), and how many problems a draft still has. */
    static Map<String, Object> result(ProcessContext ctx, Action action) {
        Map<String, Object> result = new LinkedHashMap<>();
        switch (action) {
            case PROFILE -> {
                ProfileTransferProcesses.ImportOutput out = ctx.get(PROFILE_OUT,
                    ProfileTransferProcesses.ImportOutput.class);
                result.put("basics", out == null || out.plan() == null ? null : out.plan().basics());
            }
            case QUIZ -> result.put(PackageImportRules.QUIZ_ID, String.valueOf(
                ctx.get(QUIZ_OUT, QuizTransferProcesses.ImportOutput.class).quizId()));
            case QUESTIONS -> {
                QuestionImportProcesses.ImportOutput out = ctx.get(QUESTIONS_OUT,
                    QuestionImportProcesses.ImportOutput.class);
                result.put(PackageImportRules.QUIZ_ID, String.valueOf(out.quizId()));
                result.put("questions", out.questions());
            }
            case CLEAR -> {
                QuizProcesses.ClearOutput out = ctx.get(CLEAR_OUT, QuizProcesses.ClearOutput.class);
                result.put(PackageImportRules.QUIZ_ID, String.valueOf(out.quizId()));
                result.put("cleared", out.cleared());
                result.put("remaining", out.remaining());
            }
            case VERSION -> {
                QuizProcesses.VersionOutput out = ctx.get(VERSION_OUT, QuizProcesses.VersionOutput.class);
                result.put(PackageImportRules.QUIZ_ID, String.valueOf(out.quizId()));
                result.put(PackageImportRules.VERSION_ID, String.valueOf(out.versionId()));
                result.put("versionNo", out.versionNo());
            }
            case TREE -> {
                TreeProcesses.TreeOutput out = ctx.get(TREE_SAVED, TreeProcesses.TreeOutput.class);
                result.put(PackageImportRules.TREE_ID, out.treeId());
                result.put("nodeIds", out.nodeIds() == null ? Map.of() : Map.copyOf(out.nodeIds()));
                result.put("problems", out.problems() == null ? 0 : out.problems().size());
            }
            case PUBLICATION -> {
                SaveOutput out = ctx.get(PUBLICATION_OUT, SaveOutput.class);
                result.put(PackageImportRules.PUBLICATION_ID, String.valueOf(out.publicationId()));
                result.put("problems", out.problems() == null ? 0 : out.problems().size());
            }
        }
        return result;
    }

    // ---- abandon ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ImportRef, AbandonOutput, ProcessContext> abandon() {
        return ProcessDefinition.define(ABANDON, 1, ImportRef.class, AbandonOutput.class, ProcessContext.class,
            pb -> pb
                .description("Gives up a sponsor package's import, removing the drafts it made with the existing "
                    + "processes.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(PackageImportProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, AbandonOutput.class))
                .step("Lock the Sponsor's imports", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbPackageImports.lockName(ctx.request().actorId())))
                .step("Load the import", QueryEntities.<ProcessContext>of(QbPackageImports.DEFAULT_DATASET,
                    ctx -> own(ctx, ctx.get(INPUT, ImportRef.class).importId()), RECORD))
                .step("Load what it made", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("treeId", made(ctx, PackageImportRules.TREE_ID)),
                            new QueryPredicate.Eq("ownerKind", QbTrees.SPONSOR),
                            new QueryPredicate.Eq("ownerId", ctx.request().actorId()))))
                        .limit(Integer.MAX_VALUE).build(), TREES),
                    QueryEntities.query(QbContent.defaultDataset(QbPublications.PUBLICATION),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("publicationId", made(ctx, PackageImportRules.PUBLICATION_ID)),
                            new QueryPredicate.Eq("publisherId", ctx.request().actorId()))))
                            .limit(Integer.MAX_VALUE).build(), PUBLICATIONS),
                    QueryEntities.query(QbContent.defaultDataset(QbContent.QUIZ), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("quizId", made(ctx, PackageImportRules.QUIZ_ID)),
                            new QueryPredicate.Eq("ownerId", ctx.request().actorId()),
                            new QueryPredicate.Eq("removed", false))))
                        .limit(Integer.MAX_VALUE).build(), QUIZZES),
                    QueryEntities.query(QbContent.defaultDataset(QbPublications.PUBLICATION),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.In("quizId",
                            made(ctx, PackageImportRules.QUIZ_ID))).limit(Integer.MAX_VALUE).build(),
                        QUIZ_PUBLICATIONS)))
                .compute("Plan the removal", (metadata, ctx) -> planAbandon(ctx))
                // Graphs first: a graph's lock comes before any publication's (TreeProcesses).
                .step("Retire the graphs", CallProcess.<ProcessContext>forEach(TreeProcesses.RETIRE, 1,
                    ctx -> plan(ctx).trees().stream().map(id -> new TreeProcesses.TreeInput(id, null)).toList(),
                    null))
                .step("Delete the draft publications", CallProcess.<ProcessContext>forEach(PublicationProcesses.DELETE,
                    1, ctx -> plan(ctx).publications().stream()
                        .map(id -> new PublicationProcesses.PublicationRef(UUID.fromString(id), null)).toList(), null))
                .step("Remove the quizzes", CallProcess.<ProcessContext>forEach(QuizProcesses.DELETE, 1,
                    ctx -> plan(ctx).quizzes().stream()
                        .map(id -> new QuizProcesses.QuizRef(UUID.fromString(id), null)).toList(), null))
                .compute("Close the import", (metadata, ctx) -> abandoned(ctx)));
    }

    /** What giving up removes, and the quizzes it keeps. */
    record AbandonPlan(List<String> trees, List<String> publications, List<String> quizzes, List<String> kept) {}

    private static AbandonPlan plan(ProcessContext ctx) {
        return ctx.contains(PLAN) ? ctx.get(PLAN, AbandonPlan.class) : new AbandonPlan(List.of(), List.of(), List.of(),
            List.of());
    }

    private static List<Object> made(ProcessContext ctx, String kind) {
        EntityInstance record = first(ctx, RECORD);
        return record == null ? new ArrayList<>()
            : new ArrayList<>(PackageImportRules.made(PackageImportRules.done(record.get("done")), kind));
    }

    static void planAbandon(ProcessContext ctx) {
        EntityInstance record = first(ctx, RECORD);
        if (record == null) {
            throw new EntityNotFoundException(QbPackageImports.IMPORT + " " + ctx.get(INPUT, ImportRef.class)
                .importId() + " not found");
        }
        if (!QbPackageImports.RUNNING.equals(record.get("status"))) {
            ctx.reject(new Violation("importId", PackageImportCodes.CLOSED, "The import is "
                + record.get("status"), Map.of("status", String.valueOf((Object) record.get("status")))));
            return;
        }
        // Graphs never in effect (in review or not): a graph in effect is no draft any more.
        List<String> trees = list(ctx, TREES).stream()
            .filter(tree -> QbTrees.ACTIVE.equals(tree.get("status")) && tree.get("currentVersionId") == null)
            .map(tree -> String.valueOf(tree.id())).toList();
        Set<String> deleted = new HashSet<>();
        List<String> publications = new ArrayList<>();
        for (EntityInstance publication : list(ctx, PUBLICATIONS)) {
            if (PublicationStatus.DRAFT.equals(publication.get("status"))) {
                publications.add(String.valueOf(publication.id()));
                deleted.add(String.valueOf(publication.id()));
            }
        }
        List<String> quizzes = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (EntityInstance quiz : list(ctx, QUIZZES)) {
            String quizId = String.valueOf(quiz.id());
            boolean used = list(ctx, QUIZ_PUBLICATIONS).stream()
                .filter(p -> Objects.equals(quizId, String.valueOf((Object) p.get("quizId"))))
                .anyMatch(p -> !deleted.contains(String.valueOf(p.id())));
            (used ? kept : quizzes).add(quizId);
        }
        ctx.put(PLAN, new AbandonPlan(trees, publications, quizzes, kept));
    }

    static void abandoned(ProcessContext ctx) {
        EntityInstance record = first(ctx, RECORD);
        if (ctx.hasViolations() || record == null) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbPackageImports.ABANDONED);
        values.put("updatedAt", ctx.opTime());
        values.put("finishedAt", ctx.opTime());
        ctx.changes().update(QbPackageImports.IMPORT, record.id(), record.version(), values);
        AbandonPlan plan = plan(ctx);
        ctx.put(OUTPUT, new AbandonOutput(uuid(record.id()), QbPackageImports.ABANDONED, plan.trees(),
            plan.publications(), plan.quizzes(), plan.kept()));
    }

    // ---- expire ----------------------------------------------------------------------------------------------------

    /** The daily job's item: the record of one import past its retention, read again here. */
    public record ExpireInput(@NotNull UUID importId, @NotNull Instant scheduledTime) {}

    public static ProcessDefinition<ExpireInput, ExpireOutput, ProcessContext> expire() {
        return ProcessDefinition.define(EXPIRE, 1, ExpireInput.class, ExpireOutput.class, ProcessContext.class,
            pb -> pb
                .description("Deletes the record of a sponsor package's import past its retention.")
                .permissions(QbPermissions.PACKAGE_SYSTEM)
                .internal()
                .contextFactory(PackageImportProcesses::context)
                .outputMapper(ctx -> new ExpireOutput(ctx.contains(OUTPUT)))
                .step("Load the retention", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.PACKAGE_IMPORT_RETENTION_DAYS))
                .step("Load the import", QueryEntities.<ProcessContext>of(QbPackageImports.DEFAULT_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("importId",
                        String.valueOf(ctx.get(INPUT, ExpireInput.class).importId()))).limit(1).build(), RECORD))
                .step("Lock the Sponsor's imports", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, RECORD) == null
                    ? null : QbPackageImports.lockName(first(ctx, RECORD).get("sponsorId"))))
                .step("Read it again", QueryEntities.<ProcessContext>of(QbPackageImports.DEFAULT_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("importId",
                        String.valueOf(ctx.get(INPUT, ExpireInput.class).importId()))).limit(1).build(), OPEN))
                .compute("Delete it when expired", (metadata, ctx) -> {
                    EntityInstance record = first(ctx, OPEN);
                    if (record == null) {
                        return;
                    }
                    int days = QbParams.days(ctx.get(PARAMS, ParamValues.class),
                        QbParams.PACKAGE_IMPORT_RETENTION_DAYS);
                    Instant cutoff = ctx.get(INPUT, ExpireInput.class).scheduledTime().minus(days, ChronoUnit.DAYS);
                    Instant updated = instant(record.get("updatedAt"));
                    if (updated != null && !updated.isAfter(cutoff)) {
                        ctx.changes().delete(QbPackageImports.IMPORT, record.id(), record.version());
                        ctx.put(OUTPUT, true);
                    }
                }));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object rows = ctx.contains(key) ? ctx.get(key) : null;
        return rows == null ? List.of() : (List<EntityInstance>) rows;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static Instant instant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case java.time.OffsetDateTime time -> time.toInstant();
            case null -> null;
            default -> Instant.parse(String.valueOf(value));
        };
    }

    static UUID uuid(Object id) {
        return id == null ? null : UUID.fromString(String.valueOf(id));
    }

    static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
}

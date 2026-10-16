package com.jabiz.quizbuks.play;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.publishing.PublicationMoves;
import com.jabiz.quizbuks.publishing.PublicationRules;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.play.PlaySteps.INPUT;
import static com.jabiz.quizbuks.play.PlaySteps.OUTPUT;
import static com.jabiz.quizbuks.play.PlaySteps.first;
import static com.jabiz.quizbuks.play.PlaySteps.ids;

/**
 * A taker's way into a quiz (U-20 – U-23, U-30 – U-31, U-41, 5.3-3, 5.3-5, 5.3-6; docs/quizbuks/plans/
 * Q5-answering.md, D-Q5-1, D-Q5-3, D-Q5-5, D-Q5-6):
 * <ul>
 *   <li>{@code QB_VIEW}: records the first time a taker opens a publication they can see;</li>
 *   <li>{@code QB_ATTEMPT_START}: starts the one attempt a taker has of a publication, or returns the one in progress
 *       (continuing it), with the questions and their options in the attempt's order and never the correct ones;</li>
 *   <li>{@code QB_ATTEMPT_SAVE}: saves the current question and some answers, before the deadline.</li>
 * </ul>
 * Every one is the caller's own (the user is the caller, never the input), needs a verified address and refuses a
 * banned caller first. Only a new start takes a lock: the publication's, around the counters it reserves.
 */
public final class AttemptProcesses {

    public static final String VIEW = "QB_VIEW";
    public static final String START = "QB_ATTEMPT_START";
    public static final String SAVE = "QB_ATTEMPT_SAVE";

    public record PublicationRef(@NotNull UUID publicationId) {}

    /** @param firstView whether this view was the caller's first of the publication (and was recorded) */
    public record ViewOutput(UUID publicationId, boolean firstView) {}

    public record OptionView(int no, String text, UUID image) {}

    public record QuestionView(int no, String stem, UUID image, int points, List<OptionView> options) {}

    /**
     * An attempt as its taker answers it: the questions in order, each one's options in the attempt's order and
     * without saying which are correct; the answers so far and the current question (index from 0).
     *
     * @param deadline   when it must be submitted, by the server's clock ({@code serverTime} is that clock now)
     * @param resumed    whether the attempt was already in progress (continuing it)
     */
    public record StartOutput(String attemptId, UUID publicationId, String status, Instant startedAt,
        Instant deadline, Instant serverTime, int timeLimitSec, int currentIndex, boolean resumed,
        List<QuestionView> questions, List<Answers.Given> answers) {}

    /**
     * @param answers      answers of some questions, each replacing that question's (an empty list clears it)
     * @param currentIndex the question the taker is on, from 0; unchanged when not given
     */
    public record SaveInput(@NotBlank @Size(max = 36) String attemptId, Integer currentIndex,
        @Size(max = ContentLimits.MAX_QUESTIONS) List<Answers.@Valid @NotNull Given> answers) {}

    public record SaveOutput(String attemptId, int currentIndex, List<Answers.Given> answers, Instant deadline,
        Instant serverTime) {}

    static final String PUB_KEY = "play.publicationId";
    static final String ATTEMPT_KEY = "play.attemptId";
    static final String MINE = "play.mine";
    static final String MINE_AGAIN = "play.mineAgain";
    static final String CARD_BEFORE = "play.cardBefore";
    static final String CARD = "play.card";
    static final String COUNTER = "play.counter";
    static final String WALLET = "play.wallet";
    static final String VIEWS = "play.views";
    static final String ATTEMPT = "play.attempt";
    static final String NEW = "play.new";

    // ---- view ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublicationRef, ViewOutput, ProcessContext> view() {
        return ProcessDefinition.define(VIEW, 1, PublicationRef.class, ViewOutput.class, ProcessContext.class,
            pb -> pb
                .description("Records the first time the caller opens a publication's page.")
                .permissions(QbPermissions.PLAY)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(PUB_KEY, input.publicationId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ViewOutput.class))
                .steps(b -> PlaySteps.loadCard(b, "Load the card", ctx -> ctx.get(PUB_KEY), CARD))
                .step("Load the caller's attempt", QueryEntities.<ProcessContext>of(QbPlay.TAKER_ATTEMPTS,
                    ctx -> mine(ctx.get(PUB_KEY)), MINE))
                .steps(b -> PlaySteps.loadRegions(b, ctx -> ctx.request().actorId()))
                .step("Load an earlier view", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.VIEW),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("userId", ctx.request().actorId()),
                        new QueryPredicate.Eq("publicationId", ctx.get(PUB_KEY))))).limit(1).build(), VIEWS))
                .compute("Record the view", (metadata, ctx) -> view(ctx)));
    }

    static void view(ProcessContext ctx) {
        UUID publicationId = ctx.get(PUB_KEY, UUID.class);
        EntityInstance card = first(ctx, CARD);
        String userId = ctx.request().actorId();
        if (card == null || !(first(ctx, MINE) != null || shown(ctx, card))) {
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + publicationId + " not found");
        }
        boolean first = first(ctx, VIEWS) == null;
        if (first) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", userId);
            values.put("publicationId", publicationId);
            values.put("firstViewedAt", ctx.opTime());
            ctx.changes().insert(QbPlay.VIEW, values);
        }
        ctx.put(OUTPUT, new ViewOutput(publicationId, first));
    }

    /**
     * Whether the publication's page is shown to the caller, as {@code qb.app.publication} decides (an attempt of
     * theirs aside): live, within its time and aimed at a region of their country. Its winners, budget and sponsor's
     * funds decide only whether an attempt can start.
     */
    private static boolean shown(ProcessContext ctx, EntityInstance card) {
        return PublicationRules.acceptsNewAttempts(card.get("status"), PlaySteps.instant(card.get("startAt")),
            PlaySteps.instant(card.get("endAt")), ctx.opTime())
            && Regions.overlaps(PlaySteps.userRegions(ctx), card.get("regions"));
    }

    private static Object sponsorOf(EntityInstance card) {
        return card == null ? null : card.get("sponsorId");
    }

    // ---- start -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublicationRef, StartOutput, ProcessContext> start() {
        return ProcessDefinition.define(START, 1, PublicationRef.class, StartOutput.class, ProcessContext.class,
            pb -> pb
                .description("Starts the caller's attempt of a publication, or continues the one in progress.")
                .permissions(QbPermissions.PLAY)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(PUB_KEY, input.publicationId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, StartOutput.class))
                // Outside the lock: the caller's attempt, the frozen part of the card, the regions and the version.
                .step("Load the caller's attempt", QueryEntities.<ProcessContext>of(QbPlay.TAKER_ATTEMPTS,
                    ctx -> mine(ctx.get(PUB_KEY)), MINE))
                .steps(b -> PlaySteps.loadCard(b, "Load the card", ctx -> ctx.get(PUB_KEY), CARD_BEFORE))
                .steps(b -> PlaySteps.loadRegions(b, ctx -> ctx.request().actorId()))
                .steps(b -> PlaySteps.loadVersion(b, ctx -> {
                    EntityInstance card = first(ctx, CARD_BEFORE);
                    return card == null ? null : card.get("versionId");
                }))
                .compute("Continue an attempt in progress", (metadata, ctx) -> resume(ctx))
                // Without the wallet's lock (D-Q5-5): a soft check, so before the publication's lock too (Q10a: one
                // read less while it is held); a reward the sponsor cannot pay at the end is pending.
                .step("Read the sponsor's wallet", QueryEntities.<ProcessContext>of(
                    QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(QbWallets.SPONSOR,
                        starting(ctx) ? sponsorOf(first(ctx, CARD_BEFORE)) : null), WALLET))
                // A new attempt only: under the publication's lock, the attempt, the state and the counters again.
                .step("Lock the publication", HoldLock.<ProcessContext>exclusive(
                    ctx -> starting(ctx) ? PublicationMoves.lockName(ctx.get(PUB_KEY)) : null))
                .step("Load the caller's attempt again", QueryEntities.<ProcessContext>of(QbPlay.TAKER_ATTEMPTS,
                    ctx -> mine(starting(ctx) ? ctx.get(PUB_KEY) : null), MINE_AGAIN))
                .steps(b -> PlaySteps.loadCard(b, "Load the card again",
                    ctx -> starting(ctx) ? ctx.get(PUB_KEY) : null, CARD))
                .steps(b -> PlaySteps.loadCounter(b, ctx -> starting(ctx) ? ctx.get(PUB_KEY) : null, COUNTER))
                .compute("Start the attempt", (metadata, ctx) -> start(ctx)));
    }

    private static EntityQuery mine(Object publicationId) {
        return EntityQuery.builder().where(new QueryPredicate.In("publicationId", ids(publicationId))).limit(1)
            .build();
    }

    private static boolean starting(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(NEW)) && !ctx.hasViolations();
    }

    /** An attempt of the caller's already exists: continue it while in time, else refuse; or note a new start. */
    static void resume(ProcessContext ctx) {
        EntityInstance mine = first(ctx, MINE);
        if (mine != null) {
            continueOrRefuse(ctx, mine);
            return;
        }
        if (first(ctx, CARD_BEFORE) == null) {
            // Never went on (or does not exist): not a publication anybody can answer.
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + ctx.get(PUB_KEY) + " not found");
        }
        ctx.put(NEW, true);
    }

    private static void continueOrRefuse(ProcessContext ctx, EntityInstance attempt) {
        if (AttemptStatus.finished(attempt.get("status"))) {
            ctx.reject(new com.jabiz.entity.Violation("publicationId", PlayCodes.ALREADY_TAKEN, "The caller has "
                + "already answered this publication", Map.of("attemptId", String.valueOf(attempt.id()))));
            return;
        }
        Instant deadline = PlaySteps.instant(attempt.get("deadline"));
        if (AttemptRules.pastDeadline(deadline, ctx.opTime())) {
            ctx.reject(new com.jabiz.entity.Violation("publicationId", PlayCodes.EXPIRED, "The time of the "
                + "attempt is over", Map.of("deadline", deadline.toString())));
            return;
        }
        ctx.put(OUTPUT, output(ctx, attempt, PlaySteps.snapshot(ctx), true));
    }

    static void start(ProcessContext ctx) {
        if (!starting(ctx)) {
            return;
        }
        // Another start of the caller's went first while this one waited for the lock: continue that one.
        EntityInstance mine = first(ctx, MINE_AGAIN);
        if (mine != null) {
            continueOrRefuse(ctx, mine);
            return;
        }
        EntityInstance card = first(ctx, CARD);
        EntityInstance counter = first(ctx, COUNTER);
        if (card == null || counter == null) {
            throw new IllegalStateException("A publication that went on has a card and counters: "
                + ctx.get(PUB_KEY));
        }
        String userId = ctx.request().actorId();
        EntityInstance wallet = first(ctx, WALLET);
        String refusal = AttemptRules.startRefusal(userId, PlaySteps.userRegions(ctx), PlaySteps.cardOf(card),
            PlaySteps.counts(counter), QbWallets.available(wallet), QbWallets.pendingCount(wallet), ctx.opTime());
        if (refusal != null) {
            ctx.reject(new com.jabiz.entity.Violation("publicationId", refusal, "The attempt cannot start: "
                + refusal, Map.of("publicationId", String.valueOf(ctx.get(PUB_KEY)))));
            return;
        }
        QuizSnapshot snapshot = PlaySteps.snapshot(ctx);
        Object publicationId = ctx.get(PUB_KEY);
        Instant deadline = AttemptRules.deadline(ctx.opTime(), PlaySteps.integer(card.get("timeLimitSec")));
        List<List<Integer>> order = OptionShuffle.order(OptionShuffle.seedOf(userId, publicationId),
            snapshot.questions());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", userId);
        values.put("publicationId", publicationId);
        values.put("sponsorId", card.get("sponsorId"));
        values.put("versionId", card.get("versionId"));
        values.put("startedAt", ctx.opTime());
        values.put("deadline", deadline);
        values.put("status", AttemptStatus.IN_PROGRESS);
        values.put("currentIndex", BigDecimal.ZERO);
        values.put("optionOrder", Answers.toJson(order));
        values.put("answers", Answers.toJson(Answers.none(order.size())));
        Object attemptId = ctx.changes().insert(QbPlay.ATTEMPT, values);
        BigDecimal maxReward = PlaySteps.money(card.get("maxReward"));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("starts", BigDecimal.valueOf(PlaySteps.number(counter.get("starts")) + 1));
        counts.put("reserved", PlaySteps.money(counter.get("reserved")).add(maxReward));
        ctx.changes().update(QbPublications.COUNTER, counter.id(), counter.version(), counts);
        ctx.put(OUTPUT, new StartOutput(String.valueOf(attemptId), PlaySteps.uuid(publicationId),
            AttemptStatus.IN_PROGRESS, ctx.opTime(), deadline, ctx.opTime(),
            PlaySteps.integer(card.get("timeLimitSec")), 0, false, questions(snapshot, order),
            List.of()));
    }

    /** The output of an attempt in progress. */
    private static StartOutput output(ProcessContext ctx, EntityInstance attempt, QuizSnapshot snapshot,
        boolean resumed) {
        List<List<Integer>> order = Answers.fromJson(attempt.get("optionOrder"));
        Instant startedAt = PlaySteps.instant(attempt.get("startedAt"));
        Instant deadline = PlaySteps.instant(attempt.get("deadline"));
        return new StartOutput(String.valueOf(attempt.id()), PlaySteps.uuid(attempt.get("publicationId")),
            attempt.get("status"), startedAt, deadline, ctx.opTime(),
            (int) java.time.Duration.between(startedAt, deadline).toSeconds(),
            PlaySteps.integer(attempt.get("currentIndex")), resumed, questions(snapshot, order),
            Answers.given(Answers.fromJson(attempt.get("answers"))));
    }

    /**
     * The questions as the taker sees them: every question in order with its options in {@code order}, without the
     * options' correctness (design section 9: the correct answers appear only once the attempt is finished).
     */
    static List<QuestionView> questions(QuizSnapshot snapshot, List<List<Integer>> order) {
        List<QuestionView> questions = new ArrayList<>();
        for (int i = 0; i < snapshot.questions().size(); i++) {
            QuizSnapshot.Question question = snapshot.questions().get(i);
            Map<Integer, QuizSnapshot.Option> byNo = new LinkedHashMap<>();
            question.options().forEach(option -> byNo.put(option.no(), option));
            List<Integer> shown = i < order.size() ? order.get(i) : List.copyOf(byNo.keySet());
            List<OptionView> options = shown.stream().map(byNo::get)
                .map(option -> new OptionView(option.no(), option.text(), option.image())).toList();
            questions.add(new QuestionView(question.no(), question.stem(), question.image(), question.points(),
                options));
        }
        return List.copyOf(questions);
    }

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, SaveOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, SaveOutput.class, ProcessContext.class, pb -> pb
            .description("Saves the current question and answers of the caller's attempt in progress.")
            .permissions(QbPermissions.PLAY)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                ctx.put(ATTEMPT_KEY, input.attemptId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, SaveOutput.class))
            // No lock: the attempt's own version guards it (a save racing the submission is 409).
            .step("Load the attempt", LoadEntity.by(QbPlay.TAKER_ATTEMPTS, ATTEMPT_KEY, ATTEMPT))
            .compute("Save the answers", (metadata, ctx) -> save(ctx)));
    }

    static void save(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        EntityInstance attempt = ctx.get(ATTEMPT, EntityInstance.class);
        if (!AttemptStatus.IN_PROGRESS.equals(attempt.get("status"))) {
            ctx.reject(new com.jabiz.entity.Violation("attemptId", PlayCodes.NOT_IN_PROGRESS, "The attempt is "
                + "finished", Map.of("status", String.valueOf((Object) attempt.get("status")))));
            return;
        }
        Instant deadline = PlaySteps.instant(attempt.get("deadline"));
        if (AttemptRules.pastDeadline(deadline, ctx.opTime())) {
            ctx.reject(new com.jabiz.entity.Violation("attemptId", PlayCodes.EXPIRED, "The time of the attempt is "
                + "over", Map.of("deadline", deadline.toString())));
            return;
        }
        List<List<Integer>> order = Answers.fromJson(attempt.get("optionOrder"));
        List<com.jabiz.entity.Violation> problems = Answers.check(order, input.answers(), input.currentIndex());
        if (!problems.isEmpty()) {
            problems.forEach(ctx::reject);
            return;
        }
        List<List<Integer>> answers = Answers.merge(Answers.fromJson(attempt.get("answers")), order.size(),
            input.answers());
        int currentIndex = input.currentIndex() != null ? input.currentIndex()
            : PlaySteps.integer(attempt.get("currentIndex"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("answers", Answers.toJson(answers));
        values.put("currentIndex", BigDecimal.valueOf(currentIndex));
        ctx.changes().update(QbPlay.ATTEMPT, attempt.id(), attempt.version(), values);
        ctx.put(OUTPUT, new SaveOutput(String.valueOf(attempt.id()), currentIndex, Answers.given(answers), deadline,
            ctx.opTime()));
    }

    private AttemptProcesses() {}
}

package com.jabiz.quizbuks.play;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.publishing.CardTiers;
import com.jabiz.quizbuks.publishing.PublicationMoves;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.play.PlaySteps.INPUT;
import static com.jabiz.quizbuks.play.PlaySteps.OUTPUT;
import static com.jabiz.quizbuks.play.PlaySteps.first;
import static com.jabiz.quizbuks.play.PlaySteps.ids;

/**
 * Finishing an attempt (U-32 – U-34, 5.3-5 – 5.3-9, 5.3-13; docs/quizbuks/plans/Q5-answering.md, D-Q5-2 – D-Q5-5):
 * <ul>
 *   <li>{@code QB_ATTEMPT_SUBMIT}: the taker submits in time (the deadline and {@link PlayLimits#SUBMIT_GRACE}), with
 *       last answers if any; once, whatever the {@code Idempotency-Key};</li>
 *   <li>{@code QB_ATTEMPT_EXPIRE}: the system finishes an attempt whose time ran out with its saved answers; nothing
 *       for one finished already or not due yet.</li>
 * </ul>
 * Both share these steps: outside any lock, the attempt, its version and the commission rate; then the publication's
 * lock, the attempt again, the counters, the publication and its card; scoring and the tier; for a reward above 0 the
 * sponsor's wallet lock and wallet; the reward paid (posted with the attempt as its source document, in this
 * transaction) or pending; the attempt, counters, wallet and publication written; an event for the messages.
 */
public final class AttemptFinishing {

    public static final String SUBMIT = "QB_ATTEMPT_SUBMIT";
    public static final String EXPIRE = "QB_ATTEMPT_EXPIRE";

    /** Events for the takers' messages (Q7): a reward paid, a reward pending. */
    public static final String SETTLED_EVENT = "qb.reward.settled";
    public static final String PENDING_EVENT = "qb.reward.pending";

    /**
     * @param answers      last answers, each replacing that question's; applied only in time
     * @param currentIndex the question the taker was on
     */
    public record SubmitInput(@NotBlank @Size(max = 36) String attemptId, Integer currentIndex,
        @Size(max = ContentLimits.MAX_QUESTIONS) List<Answers.@Valid @NotNull Given> answers) {}

    public record ExpireInput(@NotBlank @Size(max = 36) String attemptId) {}

    /** One question of the result page: the options chosen and the correct ones, its points and what it earned. */
    public record QuestionOutput(int no, List<Integer> selected, List<Integer> correct, int points, int earned) {}

    /**
     * The result (U-33, U-34).
     *
     * @param message the notice of a pending reward in the caller's language; null otherwise
     */
    public record SubmitOutput(String attemptId, String status, int score, int fullScore, int passScore,
        boolean passed, BigDecimal reward, String rewardStatus, String message, List<QuestionOutput> questions) {}

    /** @param status the attempt's state afterwards; null when there is no such attempt */
    public record ExpireOutput(String attemptId, String status, boolean finished) {}

    static final String ATTEMPT_KEY = "play.attemptId";
    static final String FOUND = "play.found";
    static final String ACTIVE = "play.active";
    static final String CURRENT = "play.current";
    static final String COUNTER = "play.counter";
    static final String PARAMS = "play.params";
    static final String PLAN = "play.plan";
    static final String WALLET = "play.wallet";
    static final String SETTLEMENT = "play.settlement";
    static final String POSTING = "play.posting";

    /** What the finishing decided before the wallet: the attempt in its new state, without the money's outcome. */
    record Plan(EntityInstance attempt, EntityInstance card, EntityInstance counter, String status,
        List<List<Integer>> answers, Integer currentIndex, Scoring.Result result, int passScore, boolean passed,
        CardTiers.Tier tier, BigDecimal reward) {

        boolean rewarded() {
            return reward.signum() > 0;
        }
    }

    /** What became of the money. */
    record Settlement(String rewardStatus, BigDecimal fee, LedgerProcesses.PostInput posting) {}

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SubmitInput, SubmitOutput, ProcessContext> submit(MessageCatalog messages) {
        return ProcessDefinition.define(SUBMIT, 1, SubmitInput.class, SubmitOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Submits the caller's attempt: scores it and pays or records its reward.")
                    .permissions(QbPermissions.PLAY)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        ctx.put(ATTEMPT_KEY, input.attemptId());
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, SubmitOutput.class))
                    .step("Load the attempt", QueryEntities.<ProcessContext>of(QbPlay.TAKER_ATTEMPTS,
                        ctx -> byId(ctx.get(ATTEMPT_KEY)), FOUND))
                    .compute("Check the attempt", (metadata, ctx) -> {
                        EntityInstance attempt = first(ctx, FOUND);
                        if (attempt == null) {
                            throw new EntityNotFoundException(QbPlay.ATTEMPT + " " + ctx.get(ATTEMPT_KEY)
                                + " not found");
                        }
                        if (AttemptStatus.finished(attempt.get("status"))) {
                            ctx.reject(finished(attempt));
                        } else {
                            ctx.put(ACTIVE, true);
                        }
                    });
                steps(pb, false, messages);
            });
    }

    // ---- expire ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ExpireInput, ExpireOutput, ProcessContext> expire(MessageCatalog messages) {
        return ProcessDefinition.define(EXPIRE, 1, ExpireInput.class, ExpireOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Finishes an attempt whose time ran out with its saved answers.")
                    .permissions(QbPermissions.PLAY_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        ctx.put(ATTEMPT_KEY, input.attemptId());
                        return ctx;
                    })
                    .outputMapper(ctx -> {
                        EntityInstance attempt = first(ctx, ctx.contains(ACTIVE) ? CURRENT : FOUND);
                        return new ExpireOutput(String.valueOf((Object) ctx.get(ATTEMPT_KEY)),
                            ctx.contains(PLAN) ? AttemptStatus.EXPIRED
                                : attempt == null ? null : attempt.get("status"), ctx.contains(PLAN));
                    })
                    .step("Load the attempt", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                        ctx -> byId(ctx.get(ATTEMPT_KEY)), FOUND))
                    .compute("Check the attempt", (metadata, ctx) -> {
                        // A finished attempt, or one still in time, is left as it is: the call is idempotent.
                        EntityInstance attempt = first(ctx, FOUND);
                        if (attempt != null && due(attempt, ctx.opTime())) {
                            ctx.put(ACTIVE, true);
                        }
                    });
                steps(pb, true, messages);
            });
    }

    private static boolean due(EntityInstance attempt, Instant now) {
        return AttemptStatus.IN_PROGRESS.equals(attempt.get("status"))
            && AttemptRules.tooLate(PlaySteps.instant(attempt.get("deadline")), now);
    }

    private static EntityQuery byId(Object attemptId) {
        return EntityQuery.builder().where(new QueryPredicate.In("attemptId", ids(attemptId))).limit(1).build();
    }

    private static boolean active(ProcessContext ctx) {
        return ctx.contains(ACTIVE) && !ctx.hasViolations();
    }

    private static Object publicationOf(ProcessContext ctx) {
        EntityInstance attempt = first(ctx, FOUND);
        return active(ctx) && attempt != null ? attempt.get("publicationId") : null;
    }

    /** The steps both processes share, once the attempt is found ({@link #FOUND}) and known active. */
    static <I, O> void steps(ProcessDefinitionBuilder<I, O, ProcessContext> pb, boolean expire,
        MessageCatalog messages) {
        pb.steps(b -> PlaySteps.loadVersion(b, ctx -> {
                EntityInstance attempt = first(ctx, FOUND);
                return active(ctx) && attempt != null ? attempt.get("versionId") : null;
            }))
            // The rate in effect now: a reward is split when it is paid (D-Q5-4).
            .step("Load the commission rate", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.CREATOR_FEE_RATE))
            .step("Lock the publication", HoldLock.<ProcessContext>exclusive(
                ctx -> PublicationMoves.lockName(publicationOf(ctx))))
            .step("Load the attempt again", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                ctx -> byId(active(ctx) ? ctx.get(ATTEMPT_KEY) : null), CURRENT))
            .steps(b -> PlaySteps.loadCounter(b, AttemptFinishing::publicationOf, COUNTER))
            .steps(b -> PublicationMoves.load(b, AttemptFinishing::publicationOf))
            .compute("Score the attempt", (metadata, ctx) -> score(ctx, expire))
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> {
                Plan plan = plan(ctx);
                return plan != null && plan.rewarded() ? QbWallets.lockName(QbWallets.SPONSOR,
                    plan.attempt().get("sponsorId")) : null;
            }))
            .step("Load the sponsor's wallet", QueryEntities.<ProcessContext>of(
                QbWallets.defaultDataset(QbWallets.WALLET), ctx -> {
                    Plan plan = plan(ctx);
                    return QbWallets.query(QbWallets.SPONSOR, plan != null && plan.rewarded()
                        ? plan.attempt().get("sponsorId") : null);
                }, WALLET))
            .compute("Settle the reward", (metadata, ctx) -> settle(ctx))
            .step("Post the reward", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && settlement(ctx) != null && settlement(ctx).posting() != null,
                LedgerProcesses.POST, 1, ctx -> settlement(ctx).posting(), POSTING))
            .compute("Record the result", (metadata, ctx) -> record(ctx, messages))
            .step("Announce a paid reward", PublishEvent.<ProcessContext>when(
                ctx -> announced(ctx, RewardStatus.PAID), SETTLED_EVENT, AttemptFinishing::payload))
            .step("Announce a pending reward", PublishEvent.<ProcessContext>when(
                ctx -> announced(ctx, RewardStatus.PENDING), PENDING_EVENT, AttemptFinishing::payload));
    }

    private static Plan plan(ProcessContext ctx) {
        return ctx.contains(PLAN) && !ctx.hasViolations() ? ctx.get(PLAN, Plan.class) : null;
    }

    private static Settlement settlement(ProcessContext ctx) {
        return ctx.contains(SETTLEMENT) ? ctx.get(SETTLEMENT, Settlement.class) : null;
    }

    private static boolean announced(ProcessContext ctx, String rewardStatus) {
        return !ctx.hasViolations() && settlement(ctx) != null
            && rewardStatus.equals(settlement(ctx).rewardStatus());
    }

    private static Map<String, Object> payload(ProcessContext ctx) {
        Plan plan = ctx.get(PLAN, Plan.class);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", String.valueOf(plan.attempt().id()));
        payload.put("userId", plan.attempt().get("userId"));
        payload.put("publicationId", String.valueOf((Object) plan.attempt().get("publicationId")));
        payload.put("reward", plan.reward());
        return payload;
    }

    private static Violation finished(EntityInstance attempt) {
        return new Violation("attemptId", PlayCodes.FINISHED, "The attempt is finished already",
            Map.of("status", String.valueOf((Object) attempt.get("status"))));
    }

    /** Under the publication's lock: checks again, applies the last answers, scores, picks the tier. */
    static void score(ProcessContext ctx, boolean expire) {
        if (!active(ctx)) {
            return;
        }
        EntityInstance attempt = first(ctx, CURRENT);
        if (attempt == null) {
            throw new IllegalStateException("The attempt " + ctx.get(ATTEMPT_KEY) + " disappeared");
        }
        Instant deadline = PlaySteps.instant(attempt.get("deadline"));
        if (expire) {
            if (!due(attempt, ctx.opTime())) {
                // Submitted meanwhile.
                ctx.put(ACTIVE, null);
                return;
            }
        } else {
            if (AttemptStatus.finished(attempt.get("status"))) {
                ctx.reject(finished(attempt));
                return;
            }
            if (AttemptRules.tooLate(deadline, ctx.opTime())) {
                // The answers of a late submission are not taken; the attempt is finished with its saved ones.
                ctx.reject(new Violation("attemptId", PlayCodes.EXPIRED, "The time of the attempt is over",
                    Map.of("deadline", deadline.toString())));
                return;
            }
        }
        List<List<Integer>> order = Answers.fromJson(attempt.get("optionOrder"));
        List<List<Integer>> answers = Answers.fromJson(attempt.get("answers"));
        Integer currentIndex = null;
        if (!expire) {
            SubmitInput input = ctx.get(INPUT, SubmitInput.class);
            List<Violation> problems = Answers.check(order, input.answers(), input.currentIndex());
            if (!problems.isEmpty()) {
                problems.forEach(ctx::reject);
                return;
            }
            answers = Answers.merge(answers, order.size(), input.answers());
            currentIndex = input.currentIndex();
        }
        EntityInstance card = PublicationMoves.card(ctx);
        EntityInstance counter = first(ctx, COUNTER);
        if (card == null || counter == null || PublicationMoves.publication(ctx) == null) {
            throw new IllegalStateException("A publication that went on has a card and counters");
        }
        QuizSnapshot snapshot = PlaySteps.snapshot(ctx);
        Scoring.Result result = Scoring.score(snapshot, answers);
        int passScore = PlaySteps.integer(card.get("passScore"));
        CardTiers.Tier tier = TierPick.of(CardTiers.fromJson(card.get("tiers")), result.score())
            .orElseThrow(() -> new IllegalStateException("No tier holds the score " + result.score()
                + " of publication " + card.id()));
        ctx.put(PLAN, new Plan(attempt, card, counter, expire ? AttemptStatus.EXPIRED : AttemptStatus.SUBMITTED,
            answers, currentIndex, result, passScore, result.score() >= passScore, tier, tier.reward()));
    }

    /**
     * Under the wallet's lock (D-Q5-5): the reward is paid when nothing of the sponsor's is pending and the balance
     * covers it; otherwise it is pending, behind the others, and not posted.
     */
    static void settle(ProcessContext ctx) {
        Plan plan = plan(ctx);
        if (plan == null) {
            return;
        }
        if (!plan.rewarded()) {
            ctx.put(SETTLEMENT, new Settlement(RewardStatus.NONE, null, null));
            return;
        }
        EntityInstance wallet = first(ctx, WALLET);
        Object sponsorId = plan.attempt().get("sponsorId");
        if (QbWallets.pays(wallet, plan.reward())) {
            BigDecimal rate = ctx.get(PARAMS, ParamValues.class).get(QbParams.CREATOR_FEE_RATE, BigDecimal.class);
            RewardSplit.Split split = RewardSplit.of(plan.reward(), rate);
            QbWallets.change(ctx, wallet, QbWallets.SPONSOR, sponsorId, plan.reward().negate(), 0, BigDecimal.ZERO);
            ctx.put(SETTLEMENT, new Settlement(RewardStatus.PAID, split.fee(), QbWallets.rewardPosting(
                ctx.opTime(), String.valueOf(plan.attempt().id()), String.valueOf(sponsorId),
                plan.attempt().get("userId"), String.valueOf((Object) plan.attempt().get("publicationId")),
                plan.reward(), split.fee())));
        } else {
            QbWallets.change(ctx, wallet, QbWallets.SPONSOR, sponsorId, BigDecimal.ZERO, 1, plan.reward());
            ctx.put(SETTLEMENT, new Settlement(RewardStatus.PENDING, null, null));
        }
    }

    /** Writes the attempt, the counters and the publication's move; puts the output. */
    static void record(ProcessContext ctx, MessageCatalog messages) {
        Plan plan = plan(ctx);
        if (plan == null) {
            return;
        }
        Settlement settlement = settlement(ctx);
        EntityInstance attempt = plan.attempt();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", plan.status());
        values.put("submittedAt", ctx.opTime());
        values.put("answers", Answers.toJson(plan.answers()));
        if (plan.currentIndex() != null) {
            values.put("currentIndex", BigDecimal.valueOf(plan.currentIndex()));
        }
        values.put("result", Scoring.toJson(plan.result()));
        values.put("score", BigDecimal.valueOf(plan.result().score()));
        values.put("fullScore", BigDecimal.valueOf(plan.result().fullScore()));
        values.put("passed", plan.passed());
        values.put("tierId", plan.tier().tierId());
        values.put("reward", plan.reward());
        values.put("rewardStatus", settlement.rewardStatus());
        values.put("fee", settlement.fee());
        if (RewardStatus.PAID.equals(settlement.rewardStatus())) {
            values.put("rewardPaidAt", ctx.opTime());
            values.put("ledgerTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
        }
        ctx.changes().update(QbPlay.ATTEMPT, attempt.id(), attempt.version(), values);

        EntityInstance counter = plan.counter();
        long winners = PlaySteps.number(counter.get("winners")) + (plan.rewarded() ? 1 : 0);
        BigDecimal spent = PlaySteps.money(counter.get("spent")).add(plan.reward());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("completions", BigDecimal.valueOf(PlaySteps.number(counter.get("completions")) + 1));
        counts.put("passes", BigDecimal.valueOf(PlaySteps.number(counter.get("passes")) + (plan.passed() ? 1 : 0)));
        counts.put("winners", BigDecimal.valueOf(winners));
        counts.put("reserved", PlaySteps.money(counter.get("reserved"))
            .subtract(PlaySteps.money(plan.card().get("maxReward"))));
        counts.put("spent", spent);
        ctx.changes().update(QbPublications.COUNTER, counter.id(), counter.version(), counts);
        PublicationMoves.afterCompletion(ctx, winners, spent, RewardStatus.PENDING.equals(settlement.rewardStatus()));

        String message = RewardStatus.PENDING.equals(settlement.rewardStatus())
            ? messages.find(PlayCodes.REWARD_PENDING,
                java.util.Objects.requireNonNullElse(ctx.request().locale(), java.util.Locale.ENGLISH)).orElse(null) : null;
        List<QuestionOutput> questions = plan.result().questions().stream().map(q -> new QuestionOutput(q.no(),
            q.selected(), q.correct(), q.points(), q.earned())).toList();
        ctx.put(OUTPUT, new SubmitOutput(String.valueOf(attempt.id()), plan.status(), plan.result().score(),
            plan.result().fullScore(), plan.passScore(), plan.passed(), plan.reward(), settlement.rewardStatus(),
            message, questions));
    }

    private AttemptFinishing() {}
}

package com.jabiz.quizbuks.play;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.MessageTemplate;
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
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.reward.CashRewardStatus;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.quizbuks.reward.RedemptionStatus;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.quizbuks.invitation.InvitationStatus;
import com.jabiz.quizbuks.invitation.QbInvitations;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * lock, the attempt again, the counters and the card; scoring and the tier; for a reward above 0 the sponsor's wallet
 * lock and wallet; the reward paid (posted with the attempt as its source document, in this transaction) or pending;
 * the attempt, counters and wallet written; only when its winners or budget are used up or the reward is pending, the
 * publication moved ({@link PublicationMoves#MOVE}, which reads it then: Q10a); an event for the messages. Both queue
 * for the publication's lock before their transaction when their input names the publication (platform decision D45),
 * and refuse one that is not the attempt's before the lock.
 *
 * <p>Neither takes the publication's start lock nor reads the starts' side of the counters (Q10a4,
 * docs/quizbuks/plans/Q10-lock-split.md 2.4): ending the publication and leaving it underfunded look at the winners,
 * the spent Kudos and the wallet only, all exact under the publication's lock, which every completion holds.
 */
public final class AttemptFinishing {

    public static final String SUBMIT = "QB_ATTEMPT_SUBMIT";
    public static final String EXPIRE = "QB_ATTEMPT_EXPIRE";

    /**
     * The minute's per-item job (Q5b, D-Q5-9; platform decision D42) that runs {@link #EXPIRE} for every attempt
     * selected by {@link #DUE_TEMPLATE}, each in a transaction of its own.
     */
    public static final String EXPIRE_JOB = "qb.attempt-expire";

    /** The attempts in progress whose deadline and grace ran out before a cutoff, earliest deadline first. */
    public static final String DUE_TEMPLATE = "qb.system.attempts-due";

    /** Events for the takers' messages (Q7): a reward paid, a reward pending. */
    public static final String SETTLED_EVENT = "qb.reward.settled";
    public static final String PENDING_EVENT = "qb.reward.pending";
    /** Q22: a reward owed in cash by the sponsor, a prize to collect at the sponsor's place. */
    public static final String CASH_EVENT = "qb.reward.cash-due";
    public static final String REDEEM_EVENT = "qb.reward.redeem-due";

    /**
     * @param answers       last answers, each replacing that question's; applied only in time
     * @param currentIndex  the question the taker was on
     * @param publicationId the attempt's publication, as the App knows it: the key of the queue before the
     *                      publication's lock (platform decision D45), refused when it is not the attempt's
     *                      ({@link PlayCodes#PUBLICATION_MISMATCH}); required, so that no submission skips the queue
     *                      and waits for the lock on a connection (Q10a2 review M2)
     */
    public record SubmitInput(@NotBlank @Size(max = 36) String attemptId, Integer currentIndex,
        @Size(max = ContentLimits.MAX_QUESTIONS) List<Answers.@Valid @NotNull Given> answers,
        @NotNull UUID publicationId) {}

    /**
     * @param publicationId the attempt's publication from the job's selection ({@link #DUE_TEMPLATE}): the key of
     *                      the queue before the publication's lock, as for a submission. The job always gives it;
     *                      only the system runs this process (null, as tests running it by hand do, does not queue)
     */
    public record ExpireInput(@NotBlank @Size(max = 36) String attemptId, UUID publicationId) {}

    /** One question of the result page: the options chosen and the correct ones, its points and what it earned. */
    public record QuestionOutput(int no, List<Integer> selected, List<Integer> correct, int points, int earned) {}

    /**
     * The result (U-33, U-34).
     *
     * @param message the notice of a pending reward in the caller's language; null otherwise
     * @param kind    {@code QUIZ} or {@code SURVEY} (Q12): a survey's questions have no correct options
     */
    public record SubmitOutput(String attemptId, String status, int score, int fullScore, int passScore,
        boolean passed, BigDecimal reward, String rewardStatus, String message, List<QuestionOutput> questions,
        String kind) {}

    /** @param status the attempt's state afterwards; null when there is no such attempt */
    public record ExpireOutput(String attemptId, String status, boolean finished) {}

    static final String ATTEMPT_KEY = "play.attemptId";
    /** The publication the input names (its queue's key), when it names one. */
    static final String GIVEN_PUBLICATION = "play.givenPublicationId";
    static final String FOUND = "play.found";
    static final String ACTIVE = "play.active";
    static final String CURRENT = "play.current";
    static final String COUNTER = "play.counter";
    static final String PARAMS = "play.params";
    static final String PLAN = "play.plan";
    static final String WALLET = "play.wallet";
    static final String SETTLEMENT = "play.settlement";
    static final String POSTING = "play.posting";
    static final String MOVE = "play.move";
    static final String MOVED = "play.moved";
    /** Q22: the key of the cash reward or redemption written with the attempt. */
    static final String EXTERNAL_ID = "play.externalId";
    /** Q25: the invitation the attempt started on, when its publication is invited. */
    static final String INVITATION = "play.invitation";

    /** What the finishing decided before the wallet: the attempt in its new state, without the money's outcome. */
    record Plan(EntityInstance attempt, EntityInstance card, EntityInstance counter, String status,
        List<List<Integer>> answers, Integer currentIndex, Scoring.Result result, int passScore, boolean passed,
        CardTiers.Tier tier, BigDecimal reward) {

        boolean rewarded() {
            return reward.signum() > 0;
        }

        /** How the reward reaches the taker (Q22): the card's method, the platform's for a card from before. */
        String method() {
            Object method = card.get("rewardMethod");
            return method == null ? RewardMethod.PLATFORM : method.toString();
        }

        /** Whether the reward is paid from the sponsor's wallet: only then is the wallet locked and read. */
        boolean fromWallet() {
            return rewarded() && RewardMethod.platform(method());
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
                    // Queues for the publication's lock before the transaction (platform decision D45, 16o A-6):
                    // the publication as the App sends it, checked against the attempt's before the lock.
                    .admission(PublicationMoves.LOCK_FAMILY, SubmitInput::publicationId)
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        ctx.put(ATTEMPT_KEY, input.attemptId());
                        if (input.publicationId() != null) {
                            ctx.put(GIVEN_PUBLICATION, input.publicationId());
                        }
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
                        if (mismatched(ctx, attempt)) {
                            return;
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
                    // A job's item queues too (platform decision D45 item 4): the publication from the selection;
                    // refused by the queue, the item is busy and the next minute's run selects it again.
                    .admission(PublicationMoves.LOCK_FAMILY, ExpireInput::publicationId)
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        ctx.put(ATTEMPT_KEY, input.attemptId());
                        if (input.publicationId() != null) {
                            ctx.put(GIVEN_PUBLICATION, input.publicationId());
                        }
                        return ctx;
                    })
                    .outputMapper(ctx -> {
                        // The attempt as read again under the lock when it was (a submission may have come first).
                        EntityInstance current = first(ctx, CURRENT);
                        EntityInstance attempt = current != null ? current : first(ctx, FOUND);
                        return new ExpireOutput(String.valueOf((Object) ctx.get(ATTEMPT_KEY)),
                            ctx.contains(PLAN) ? AttemptStatus.EXPIRED
                                : attempt == null ? null : attempt.get("status"), ctx.contains(PLAN));
                    })
                    .step("Load the attempt", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                        ctx -> byId(ctx.get(ATTEMPT_KEY)), FOUND))
                    .compute("Check the attempt", (metadata, ctx) -> {
                        // A finished attempt, or one still in time, is left as it is: the call is idempotent.
                        EntityInstance attempt = first(ctx, FOUND);
                        if (attempt != null && !mismatched(ctx, attempt) && due(attempt, ctx.opTime())) {
                            ctx.put(ACTIVE, true);
                        }
                    });
                steps(pb, true, messages);
            });
    }

    /**
     * Whether the input names another publication than the attempt's (refused): the input's publication chose the
     * queue (platform decision D45), so a wrong one would have queued for one lock and then taken another. Checked
     * before the lock. (A submission always names one; an expiry run by hand without one queued for nothing.)
     */
    static boolean mismatched(ProcessContext ctx, EntityInstance attempt) {
        Object given = ctx.get(GIVEN_PUBLICATION);
        if (given == null || given.equals(PlaySteps.uuid(attempt.get("publicationId")))) {
            return false;
        }
        ctx.reject(new Violation("publicationId", PlayCodes.PUBLICATION_MISMATCH, "The attempt is not of "
            + "publication " + given, Map.of("publicationId", String.valueOf(given))));
        return true;
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
            // Q22: with the days a sponsor has to pay a cash reward and a taker to collect a prize.
            .step("Load the commission rate", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.CREATOR_FEE_RATE, QbParams.CASH_PAY_DAYS, QbParams.REDEEM_DAYS))
            .step("Lock the publication", HoldLock.<ProcessContext>exclusive(
                ctx -> PublicationMoves.lockName(publicationOf(ctx))))
            // The card only: its state is the publication's, its limits the publication's frozen ones (Q10a). One
            // statement for the three (platform decision D46, Q10a3): each round trip under the lock is ~0.8 ms of it.
            .step("Load the attempt, the counters and the card again", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                    ctx -> byId(active(ctx) ? ctx.get(ATTEMPT_KEY) : null), CURRENT),
                PlaySteps.counter(AttemptFinishing::publicationOf, COUNTER),
                PublicationMoves.cardRead(AttemptFinishing::publicationOf)))
            .compute("Score the attempt", (metadata, ctx) -> score(ctx, expire))
            // Q25: an invited card only (no statement otherwise): the invitation the attempt started on.
            .step("Load the invitation", QueryEntities.<ProcessContext>of(QbInvitations.DEFAULT_DATASET, ctx -> {
                Plan plan = plan(ctx);
                return EntityQuery.builder().where(new QueryPredicate.In("attemptId",
                    plan != null && PlaySteps.invited(plan.card()) ? List.of(String.valueOf(plan.attempt().id()))
                        : List.of())).limit(1).build();
            }, INVITATION))
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> {
                Plan plan = plan(ctx);
                return plan != null && plan.fromWallet() ? QbWallets.lockName(QbWallets.SPONSOR,
                    plan.attempt().get("sponsorId")) : null;
            }))
            .step("Load the sponsor's wallet", QueryEntities.<ProcessContext>of(
                QbWallets.defaultDataset(QbWallets.WALLET), ctx -> {
                    Plan plan = plan(ctx);
                    return QbWallets.query(QbWallets.SPONSOR, plan != null && plan.fromWallet()
                        ? plan.attempt().get("sponsorId") : null);
                }, WALLET))
            .compute("Settle the reward", (metadata, ctx) -> settle(ctx))
            .step("Post the reward", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && settlement(ctx) != null && settlement(ctx).posting() != null,
                LedgerProcesses.POST, 1, ctx -> settlement(ctx).posting(), POSTING))
            .compute("Record the result", (metadata, ctx) -> record(ctx, messages))
            // Only when its winners or budget are used up, or a reward is pending: the publication and its schedule.
            .step("Move the publication", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && ctx.contains(MOVE), PublicationMoves.MOVE, 1,
                ctx -> ctx.get(MOVE), MOVED))
            .step("Announce a paid reward", PublishEvent.<ProcessContext>when(
                ctx -> announced(ctx, RewardStatus.PAID), SETTLED_EVENT, AttemptFinishing::payload))
            .step("Announce a pending reward", PublishEvent.<ProcessContext>when(
                ctx -> announced(ctx, RewardStatus.PENDING), PENDING_EVENT, AttemptFinishing::payload))
            .step("Announce a cash reward", PublishEvent.<ProcessContext>when(
                ctx -> announced(ctx, RewardStatus.CASH), CASH_EVENT, AttemptFinishing::payload))
            .step("Announce a prize", PublishEvent.<ProcessContext>when(
                ctx -> announced(ctx, RewardStatus.REDEEM), REDEEM_EVENT, AttemptFinishing::payload));
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
        // Q22: the record of a reward the sponsor settles themselves, for the messages.
        if (ctx.contains(EXTERNAL_ID)) {
            payload.put(RewardStatus.CASH.equals(settlement(ctx).rewardStatus()) ? "cashRewardId" : "redemptionId",
                String.valueOf((Object) ctx.get(EXTERNAL_ID)));
        }
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
        QuizSnapshot snapshot = PlaySteps.snapshot(ctx);
        if (!expire && snapshot.survey()) {
            // A survey is submitted complete (Q12, D-Q12-5); at its deadline it ends with what was saved (D-Q12-6).
            List<Violation> problems = Answers.incomplete(snapshot, answers);
            if (!problems.isEmpty()) {
                problems.forEach(ctx::reject);
                return;
            }
        }
        EntityInstance card = PublicationMoves.card(ctx);
        EntityInstance counter = first(ctx, COUNTER);
        if (card == null || counter == null) {
            throw new IllegalStateException("A publication that went on has a card and counters");
        }
        Scoring.Result result = Scoring.scoreShown(snapshot, order, answers);
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
        if (!plan.fromWallet()) {
            // Q22: the sponsor settles it themselves; the record of it is written with the attempt (record).
            ctx.put(SETTLEMENT, new Settlement(RewardMethod.SPONSOR_CASH.equals(plan.method()) ? RewardStatus.CASH
                : RewardStatus.REDEEM, null, null));
            return;
        }
        EntityInstance wallet = first(ctx, WALLET);
        Object sponsorId = plan.attempt().get("sponsorId");
        if (QbWallets.pays(wallet, plan.reward())) {
            BigDecimal rate = ctx.get(PARAMS, ParamValues.class).get(QbParams.CREATOR_FEE_RATE, BigDecimal.class);
            if (!RewardSplit.validRate(rate)) {
                ctx.reject(feeRateInvalid(rate));
                return;
            }
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

    /** A commission rate that cannot be applied: refused rather than paid wrong (422). */
    static Violation feeRateInvalid(BigDecimal rate) {
        return new Violation(null, PlayCodes.FEE_RATE_INVALID, "The commission rate " + rate + " is not from 0 to "
            + "below 1", Map.of("rate", String.valueOf(rate)));
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
        EntityInstance invitation = first(ctx, INVITATION);
        if (invitation != null) {
            // Q25: the sponsor sees on the list when the invitee answered and what they scored and won.
            Map<String, Object> answered = new LinkedHashMap<>();
            answered.put("status", InvitationStatus.ANSWERED);
            answered.put("answeredAt", ctx.opTime());
            answered.put("score", BigDecimal.valueOf(plan.result().score()));
            answered.put("reward", plan.reward());
            ctx.changes().update(QbInvitations.INVITATION, invitation.id(), invitation.version(), answered);
        }
        if (RewardStatus.PAID.equals(settlement.rewardStatus())) {
            values.put("rewardPaidAt", ctx.opTime());
            values.put("ledgerTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
        }
        ctx.changes().update(QbPlay.ATTEMPT, attempt.id(), attempt.version(), values);
        if (RewardStatus.external(settlement.rewardStatus())) {
            external(ctx, plan, settlement.rewardStatus());
        }

        EntityInstance counter = plan.counter();
        long winners = PlaySteps.number(counter.get("winners")) + (plan.rewarded() ? 1 : 0);
        BigDecimal spent = PlaySteps.money(counter.get("spent")).add(plan.reward());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("completions", BigDecimal.valueOf(PlaySteps.number(counter.get("completions")) + 1));
        counts.put("passes", BigDecimal.valueOf(PlaySteps.number(counter.get("passes")) + (plan.passed() ? 1 : 0)));
        counts.put("winners", BigDecimal.valueOf(winners));
        // What the attempt's start reserved is released (Q10a4: added to the completions' side; the starts' side,
        // the start lock's, is never written here).
        counts.put("released", PlaySteps.money(counter.get("released"))
            .add(PlaySteps.money(plan.card().get("maxReward"))));
        counts.put("spent", spent);
        ctx.changes().update(QbPublications.COUNTER, counter.id(), counter.version(), counts);
        boolean pending = RewardStatus.PENDING.equals(settlement.rewardStatus());
        String move = PublicationMoves.moveAfter(plan.card(), winners, spent, pending);
        if (move != null) {
            ctx.put(MOVE, new PublicationMoves.MoveInput(PlaySteps.uuid(attempt.get("publicationId")), winners, spent,
                pending, move));
        }

        String notice = switch (settlement.rewardStatus()) {
            case RewardStatus.PENDING -> PlayCodes.REWARD_PENDING;
            case RewardStatus.CASH -> PlayCodes.REWARD_CASH;
            case RewardStatus.REDEEM -> PlayCodes.REWARD_REDEEM;
            default -> null;
        };
        // The cash notice names the code the taker shows the sponsor (Q22).
        String message = notice == null ? null : messages.find(notice,
            java.util.Objects.requireNonNullElse(ctx.request().locale(), java.util.Locale.ENGLISH))
            .map(text -> MessageTemplate.format(text, Map.of("code", QbRewards.codeOf(attempt.id())))).orElse(null);
        List<QuestionOutput> questions = plan.result().questions().stream().map(q -> new QuestionOutput(q.no(),
            q.selected(), q.correct(), q.points(), q.earned())).toList();
        ctx.put(OUTPUT, new SubmitOutput(String.valueOf(attempt.id()), plan.status(), plan.result().score(),
            plan.result().fullScore(), plan.passScore(), plan.passed(), plan.reward(), settlement.rewardStatus(),
            message, questions, plan.card().get("kind")));
    }

    /**
     * The record of a reward the sponsor settles themselves (Q22): a cash reward owed from now, to be paid within
     * {@code qb.reward.cash.pay-days}; or a prize to collect within {@code qb.reward.redeem-days} (the publication's
     * end if that is later), described as the card describes it. One per attempt; its code is the attempt's.
     */
    static void external(ProcessContext ctx, Plan plan, String rewardStatus) {
        EntityInstance attempt = plan.attempt();
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("attemptId", String.valueOf(attempt.id()));
        values.put("publicationId", attempt.get("publicationId"));
        values.put("sponsorId", attempt.get("sponsorId"));
        values.put("userId", attempt.get("userId"));
        values.put("amount", plan.reward());
        values.put("code", QbRewards.codeOf(attempt.id()));
        values.put("dueAt", ctx.opTime());
        values.put("revision", BigDecimal.ONE);
        if (RewardStatus.CASH.equals(rewardStatus)) {
            values.put("status", CashRewardStatus.DUE);
            values.put("payBy", ctx.opTime().plus(Duration.ofDays(days(params, QbParams.CASH_PAY_DAYS))));
            ctx.put(EXTERNAL_ID, ctx.changes().insert(QbRewards.CASH_REWARD, values));
        } else {
            Instant expires = ctx.opTime().plus(Duration.ofDays(days(params, QbParams.REDEEM_DAYS)));
            Instant end = PlaySteps.instant(plan.card().get("endAt"));
            values.put("status", RedemptionStatus.DUE);
            values.put("description", plan.card().get("redeemDescription") == null ? ""
                : plan.card().get("redeemDescription"));
            values.put("expiresAt", end != null && end.isAfter(expires) ? end : expires);
            ctx.put(EXTERNAL_ID, ctx.changes().insert(QbRewards.REDEMPTION, values));
        }
    }

    private static int days(ParamValues params, String key) {
        return QbParams.days(params, key);
    }

    private AttemptFinishing() {}
}

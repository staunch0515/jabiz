package com.jabiz.quizbuks.play;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.jabiz.quizbuks.play.PlaySteps.INPUT;
import static com.jabiz.quizbuks.play.PlaySteps.OUTPUT;
import static com.jabiz.quizbuks.play.PlaySteps.first;

/**
 * The result of one of the caller's attempts, read later (docs/quizbuks/plans/Q20-result-review.md, closing gap A-3
 * of QP-pages): the questions as that attempt showed them (the same view as {@code QB_ATTEMPT_START}: options by
 * their position, never which are correct) together with the stored result, which names, by the same positions, the
 * options chosen and the correct ones. Only a finished attempt has them: the correct answers still appear only once
 * the attempt is finished (design section 9), and an attempt in progress gets its state alone. The option order stays
 * on the server, as everywhere else.
 */
public final class AttemptResults {

    public static final String RESULT = "QB_ATTEMPT_RESULT";

    public record ResultInput(@NotBlank @Size(max = 36) String attemptId) {}

    /**
     * @param deadline   when the attempt must be (or had to be) submitted, by the server's clock
     * @param serverTime the server's clock now (an attempt in progress is still answerable before its deadline)
     * @param name       the publication's name, as its card has it
     * @param passScore  the publication's pass mark; null for a survey
     * @param questions  once finished: the questions in the attempt's order, each option by its position
     * @param result     once finished: per question, the positions chosen and the correct ones (none for a survey)
     */
    public record ResultOutput(String attemptId, UUID publicationId, String name, String status, String kind,
        Instant deadline, Instant serverTime, Integer score, Integer fullScore, Integer passScore, Boolean passed, BigDecimal reward,
        String rewardStatus, List<AttemptProcesses.QuestionView> questions,
        List<AttemptFinishing.QuestionOutput> result, String rewardMethod, CashView cash, RedeemView redeem) {}

    /** Q22: where the cash reward stands, for the result page's confirm and dispute. */
    public record CashView(String cashRewardId, String status, String code, BigDecimal amount, Instant markedPaidAt,
        Instant confirmBy, String sponsorCompany) {}

    /** Q22: the prize to collect and until when (claiming it is Q22b). */
    public record RedeemView(String redemptionId, String status, String code, BigDecimal amount, String description,
        Instant expiresAt, String sponsorCompany) {}

    static final String ATTEMPT_KEY = "play.attemptId";
    static final String ATTEMPT = "play.attempt";
    static final String CARD = "play.card";
    static final String CASH = "play.cash";
    static final String REDEEM = "play.redeem";
    static final String SPONSOR = "play.sponsor";

    public static ProcessDefinition<ResultInput, ResultOutput, ProcessContext> result() {
        return ProcessDefinition.define(RESULT, 1, ResultInput.class, ResultOutput.class, ProcessContext.class,
            pb -> pb
                .description("Reads the result of one of the caller's attempts: once finished, the questions as "
                    + "shown with the options chosen and the correct ones.")
                .permissions(QbPermissions.ME)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(ATTEMPT_KEY, input.attemptId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ResultOutput.class))
                // Through the caller's own dataset: another taker's attempt is not found (404).
                .step("Load the attempt", LoadEntity.by(QbPlay.TAKER_ATTEMPTS, ATTEMPT_KEY, ATTEMPT))
                // The frozen version the attempt was answered from, and the card (its name and pass mark): one
                // statement, as the two depend on the attempt alone (platform decision D46).
                .step("Load the version and the card", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbContent.defaultDataset(QbContent.VERSION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("versionId", PlaySteps.ids(ctx.get(ATTEMPT, EntityInstance.class)
                            .get("versionId")))).limit(1).build(), PlaySteps.VERSION),
                    PlaySteps.card(ctx -> ctx.get(ATTEMPT, EntityInstance.class).get("publicationId"), CARD),
                    // Q22: the reward's record when the sponsor settles it themselves, and the sponsor's company.
                    // (Only for the attempts settled that way: the others read nothing here.)
                    QueryEntities.query(QbRewards.takerDataset(QbRewards.CASH_REWARD),
                        ctx -> byAttempt(ctx, RewardStatus.CASH), CASH),
                    QueryEntities.query(QbRewards.takerDataset(QbRewards.REDEMPTION),
                        ctx -> byAttempt(ctx, RewardStatus.REDEEM), REDEEM),
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("userId", external(ctx)
                            ? List.of(String.valueOf((Object) ctx.get(ATTEMPT, EntityInstance.class).get("sponsorId")))
                            : List.of())).limit(1).build(), SPONSOR)))
                .compute("Read the result", (metadata, ctx) -> read(ctx)));
    }

    static void read(ProcessContext ctx) {
        EntityInstance attempt = ctx.get(ATTEMPT, EntityInstance.class);
        QuizSnapshot snapshot = PlaySteps.snapshot(ctx);
        EntityInstance card = first(ctx, CARD);
        boolean finished = AttemptStatus.finished(attempt.get("status"));
        Integer passScore = snapshot.survey() || card == null ? null : PlaySteps.integer(card.get("passScore"));
        List<AttemptProcesses.QuestionView> questions = finished
            ? AttemptProcesses.questions(snapshot, Answers.fromJson(attempt.get("optionOrder"))) : List.of();
        List<AttemptFinishing.QuestionOutput> result = finished
            ? Scoring.fromJson(attempt.get("result")).stream().map(question -> new AttemptFinishing.QuestionOutput(
                question.no(), question.selected(), question.correct(), question.points(), question.earned()))
                .toList()
            : List.of();
        Object passed = attempt.get("passed");
        ctx.put(OUTPUT, new ResultOutput(String.valueOf(attempt.id()), PlaySteps.uuid(attempt.get("publicationId")),
            card == null ? null : card.get("name"), attempt.get("status"), snapshot.kind(), PlaySteps.instant(attempt.get("deadline")), ctx.opTime(),
            number(attempt.get("score")), number(attempt.get("fullScore")), passScore,
            passed == null ? null : Boolean.TRUE.equals(passed) || "true".equals(String.valueOf(passed)),
            attempt.get("reward") == null ? null : PlaySteps.money(attempt.get("reward")),
            attempt.get("rewardStatus"), questions, result,
            card == null || card.get("rewardMethod") == null ? RewardMethod.PLATFORM : card.get("rewardMethod"),
            cash(first(ctx, CASH), company(ctx)), redeem(first(ctx, REDEEM), company(ctx))));
    }

    private static EntityQuery byAttempt(ProcessContext ctx, String rewardStatus) {
        boolean wanted = rewardStatus.equals(ctx.get(ATTEMPT, EntityInstance.class).get("rewardStatus"));
        return EntityQuery.builder().where(new QueryPredicate.In("attemptId",
            wanted ? List.of(String.valueOf((Object) ctx.get(ATTEMPT_KEY))) : List.of())).limit(1).build();
    }

    private static boolean external(ProcessContext ctx) {
        return RewardStatus.external(String.valueOf((Object) ctx.get(ATTEMPT, EntityInstance.class)
            .get("rewardStatus")));
    }

    private static String company(ProcessContext ctx) {
        EntityInstance sponsor = first(ctx, SPONSOR);
        return sponsor == null ? null : sponsor.get("company");
    }

    static CashView cash(EntityInstance cash, String company) {
        return cash == null ? null : new CashView(String.valueOf(cash.id()), cash.get("status"), cash.get("code"),
            PlaySteps.money(cash.get("amount")), PlaySteps.instant(cash.get("markedPaidAt")),
            PlaySteps.instant(cash.get("confirmBy")), company);
    }

    static RedeemView redeem(EntityInstance redemption, String company) {
        return redemption == null ? null : new RedeemView(String.valueOf(redemption.id()),
            redemption.get("status"), redemption.get("code"), PlaySteps.money(redemption.get("amount")),
            redemption.get("description"), PlaySteps.instant(redemption.get("expiresAt")), company);
    }

    private static Integer number(Object value) {
        return value == null ? null : PlaySteps.integer(value);
    }

    private AttemptResults() {}
}

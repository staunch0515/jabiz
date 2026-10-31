package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.reward.CashRewardProcesses;
import com.jabiz.quizbuks.reward.CashRewardStatus;
import com.jabiz.quizbuks.reward.RewardCodes;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reward methods and cash rewards (docs/quizbuks/plans/Q22-reward-methods.md, Q22a): an administrator allows a
 * sponsor methods beyond the platform's Kudos; a publication paid in cash or with a prize never wants the sponsor's
 * wallet; a cash reward is owed, marked paid by the sponsor and confirmed by the taker, or disputed and decided by an
 * administrator; a taker who neither confirms nor disputes in time starts no new attempt; a prize is recorded with
 * its description and its time to collect it.
 */
class RewardMethodsIT extends PlayItSupport {

    @Autowired
    @Qualifier("qbCashRewardOverdueJob")
    JobDefinition<CashRewardProcesses.CashRewardRef> overdueJob;

    @Autowired
    @Qualifier("qbCashRewardRemindJob")
    JobDefinition<CashRewardProcesses.CashRewardRef> remindJob;

    private String methodsAdmin() {
        return TestTokens.bearer(tokens, "q22-admin", QbPermissions.ADMIN_REWARD_METHODS, QbPermissions.CASH_RESOLVE,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private void allow(String sponsor, String... methods) {
        run(com.jabiz.quizbuks.publishing.RewardMethodProcesses.SET, methodsAdmin(), body("userId", sponsor, "methods",
            List.of(methods), "note", "Q22 test"));
    }

    /** Sets one of the controlled reward parameters (Q28), with four eyes. */
    private void rewardParam(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Q28 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private Map<String, Object> cashReward(String attemptId) {
        return latest("qb_cash_reward_version", "cash_reward_id", "attempt_id = '" + attemptId + "'").getFirst();
    }

    private Map<String, Object> redemption(String attemptId) {
        return latest("qb_redemption_version", "redemption_id", "attempt_id = '" + attemptId + "'").getFirst();
    }

    private static List<Map<String, Object>> permissionTasks(String permission, String sourceKey) {
        return query("SELECT task_type, status FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version "
            + "ORDER BY task_id, version_no DESC) t WHERE assignee_permission = ? AND source_key = ? "
            + "AND NOT is_deleted", permission, sourceKey);
    }

    private List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 200));
    }

    /** Runs a job at {@code at} as the scheduler would (the lock of the last run released first). */
    private JobRunner.Outcome runJob(JobDefinition<CashRewardProcesses.CashRewardRef> job, Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.run(job, at);
    }

    private static Instant at(Object value) {
        return value instanceof java.sql.Timestamp time ? time.toInstant()
            : value instanceof java.time.OffsetDateTime time ? time.toInstant() : (Instant) value;
    }

    private Map<String, Object> result(String token, String attemptId) {
        return run("QB_ATTEMPT_RESULT", token, body("attemptId", attemptId));
    }

    private Map<String, Object> cashOf(String sponsor, String name) {
        Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.SPONSOR_CASH);
        return Map.of("publicationId", livePublication(sponsor, name, overrides));
    }

    /**
     * Q28: a sponsor without a setting of their own has the methods of {@code qb.reward.default-methods} (the launch
     * phase: prizes only), which the console reads from {@code qb.sponsor.reward-methods}; a tier paying more than
     * {@code qb.reward.max-per-attempt} is a problem of the draft and refuses the submission.
     */
    @Test
    void theDefaultMethodsAndTheHighestRewardComeFromTheParameters() {
        String sponsor = newSponsor("s-q28-defaults");
        String token = sponsorToken(sponsor);
        String versionId = version(token, "Defaults quiz");
        Instant now = clock.instant();
        rewardParam(QbParams.REWARD_DEFAULT_METHODS, "OFFLINE_REDEEM, NOT_A_METHOD");
        rewardParam(QbParams.REWARD_MAX_PER_ATTEMPT, "50");
        try {
            assertThat(rows("qb.sponsor.reward-methods", token, Map.of())).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("methods", "OFFLINE_REDEEM, NOT_A_METHOD").containsEntry("own", false);
                assertThat(new BigDecimal(String.valueOf(row.get("maxRewardPerAttempt")))).isEqualByComparingTo("50");
            });
            Map<String, Object> save = complete(versionId, "Prize week", now, now.plus(Duration.ofDays(7)));
            assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
                .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
            save.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
            save.put("redeemDescription", "A coffee at the counter");
            Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, save);
            // The top tier pays 100: above the cap until it is raised.
            assertThat(problemCodes(saved)).containsExactly(PublishingCodes.REWARD_ABOVE_MAX);
            String publicationId = (String) saved.get("publicationId");
            assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId), 422)))
                .containsExactly(PublishingCodes.REWARD_ABOVE_MAX);
            rewardParam(QbParams.REWARD_MAX_PER_ATTEMPT, "100");
            assertThat(submit(token, publicationId)).containsEntry("status", "LIVE");
            // An administrator's own setting replaces the default.
            allow(sponsor, RewardMethod.PLATFORM);
            assertThat(rows("qb.sponsor.reward-methods", token, Map.of())).singleElement().satisfies(row ->
                assertThat(row).containsEntry("methods", "PLATFORM").containsEntry("own", true));
        } finally {
            rewardParam(QbParams.REWARD_DEFAULT_METHODS, QbParams.defaultOf(QbParams.REWARD_DEFAULT_METHODS));
            rewardParam(QbParams.REWARD_MAX_PER_ATTEMPT, QbParams.defaultOf(QbParams.REWARD_MAX_PER_ATTEMPT));
        }
    }

    @Test
    void anAdministratorAllowsTheMethodsASponsorPublishesWith() {
        String sponsor = newSponsor("s-q22-methods");
        String token = sponsorToken(sponsor);
        String versionId = version(token, "Methods quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Cash week", now, now.plus(Duration.ofDays(7)));
        save.put("rewardMethod", RewardMethod.SPONSOR_CASH);
        // Nothing allowed yet: the platform's Kudos only.
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
            .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
        // A method nobody knows, a sponsor nobody knows.
        assertThat(codes(refused(com.jabiz.quizbuks.publishing.RewardMethodProcesses.SET, methodsAdmin(),
            body("userId", sponsor, "methods", List.of("GOLD")), 422)))
            .containsExactly(PublishingCodes.REWARD_METHODS_FORMAT);
        refused(com.jabiz.quizbuks.publishing.RewardMethodProcesses.SET, methodsAdmin(), body("userId",
            UUID.randomUUID().toString(), "methods", List.of(RewardMethod.PLATFORM)), 404);
        // A sponsor cannot set their own.
        refused(com.jabiz.quizbuks.publishing.RewardMethodProcesses.SET, token, body("userId", sponsor, "methods",
            List.of(RewardMethod.SPONSOR_CASH)), 403);

        allow(sponsor, RewardMethod.SPONSOR_CASH, RewardMethod.PLATFORM);
        assertThat(latest("qb_sponsor_reward_methods_version", "setting_id", "user_id = '" + sponsor + "'")
            .getFirst()).containsEntry("methods", "PLATFORM,SPONSOR_CASH").containsEntry("note", "Q22 test");
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, save);
        assertThat(saved.get("problems")).isEqualTo(List.of());
        String publicationId = (String) saved.get("publicationId");
        assertThat(submit(token, publicationId)).containsEntry("status", "LIVE");
        assertThat(card(publicationId)).containsEntry("reward_method", "SPONSOR_CASH");
        assertThat(rows("qb.sponsor.publications", token, Map.of())).anySatisfy(row -> assertThat(row)
            .containsEntry("publicationId", publicationId).containsEntry("rewardMethod", "SPONSOR_CASH"));
        assertThat(rows("qb.admin.sponsors", methodsAdmin(), Map.of())).anySatisfy(row -> assertThat(row)
            .containsEntry("userId", sponsor).containsEntry("rewardMethods", "PLATFORM,SPONSOR_CASH"));
        // A copy carries the method; once the method is taken away, saving says so and submitting refuses.
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", publicationId, "name",
            "Cash week 2", "startAt", now.plus(Duration.ofDays(1)).toString(),
            "endAt", now.plus(Duration.ofDays(8)).toString())).get("publicationId");
        allow(sponsor, RewardMethod.PLATFORM);
        Map<String, Object> second = run("QB_PUBLICATION_COPY", token, body("publicationId", publicationId));
        assertThat(problemCodes(second)).containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", copy), 422)))
            .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
        // The live one goes on as it is.
        assertThat(status(publicationId)).isEqualTo("LIVE");
    }

    /** The codes of a save's problems (what submitting would be refused for). */
    @SuppressWarnings("unchecked")
    private static List<String> problemCodes(Map<String, Object> saved) {
        return ((List<Map<String, Object>>) saved.get("problems")).stream().map(p -> (String) p.get("code")).toList();
    }

    @Test
    void aCashRewardIsOwedMarkedPaidAndConfirmed() {
        String sponsor = newSponsor("s-q22-cash");
        allow(sponsor, RewardMethod.PLATFORM, RewardMethod.SPONSOR_CASH);
        String publicationId = (String) cashOf(sponsor, "Cash quiz").get("publicationId");
        // The wallet is empty, and the publication is in the feed all the same: nothing is paid from it.
        assertThat(wallet(sponsor)).isNull();
        String takerId = newTaker("t-q22-cash", "JP");
        // Signed again after the hour passes: a token lasts less.
        String token = taker(takerId);
        assertThat(feed(token, "JP", Map.of())).anySatisfy(item -> assertThat(item)
            .containsEntry("publicationId", publicationId).containsEntry("rewardMethod", "SPONSOR_CASH"));
        assertThat(feed(token, "JP", Map.of("method", RewardMethod.PLATFORM)))
            .noneMatch(item -> publicationId.equals(item.get("publicationId")));
        assertThat(feed(token, "JP", Map.of("method", RewardMethod.SPONSOR_CASH)))
            .allMatch(item -> "SPONSOR_CASH".equals(item.get("rewardMethod")));

        String attemptId = (String) start(token, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        Map<String, Object> submitted = submit(token, attemptId, answers(true, true));
        assertThat(submitted).containsEntry("rewardStatus", "CASH").containsEntry("reward", 100);
        assertThat(postingOf(attemptId)).isEmpty();
        assertThat(wallet(sponsor)).isNull();
        assertThat(counter(publicationId)).containsEntry("winners", new BigDecimal("1"))
            .containsEntry("spent", new BigDecimal("100"));
        Map<String, Object> reward = cashReward(attemptId);
        String code = (String) reward.get("code");
        assertThat(code).startsWith("C-").hasSize(10);
        assertThat((String) submitted.get("message")).contains(code);
        assertThat(reward).containsEntry("status", "DUE").containsEntry("user_id", takerId)
            .containsEntry("sponsor_id", sponsor).containsEntry("amount", new BigDecimal("100"));
        assertThat(at(reward.get("pay_by")))
            .isEqualTo(clock.instant().plus(Duration.ofDays(14)));
        String cashRewardId = String.valueOf(reward.get("cash_reward_id"));
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(takerId)).containsExactly("CASH_DUE");

        // What each side reads.
        Map<String, Object> cash = (Map<String, Object>) result(token, attemptId).get("cash");
        assertThat(cash).containsEntry("cashRewardId", cashRewardId).containsEntry("status", "DUE")
            .containsEntry("code", code).containsEntry("amount", 100);
        assertThat(result(token, attemptId)).containsEntry("rewardMethod", "SPONSOR_CASH")
            .containsEntry("redeem", null);
        assertThat(rows("qb.app.history", token, Map.of("at", clock.instant().toString()))).anySatisfy(row ->
            assertThat(row).containsEntry("attemptId", attemptId).containsEntry("cashStatus", "DUE")
                .containsEntry("cashCode", code).containsEntry("rewardMethod", "SPONSOR_CASH"));
        assertThat(rows("qb.app.cash-rewards", token, Map.of("open", true))).singleElement().satisfies(row ->
            assertThat(row).containsEntry("cashRewardId", cashRewardId).containsEntry("status", "DUE"));
        assertThat(rows("qb.sponsor.cash-rewards", sponsorToken(sponsor), Map.of("q", code))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("code", code).containsEntry("takerName", "Taker JP")
                .containsEntry("publicationName", "Cash quiz"));
        assertThat(rows("qb.admin.cash-rewards", methodsAdmin(), Map.of("q", code))).singleElement();
        // Nothing of another's: 404 for the other taker, the other sponsor, and a sponsor confirming.
        refused(CashRewardProcesses.CONFIRM, taker(u2()), body("cashRewardId", cashRewardId), 404);
        refused(CashRewardProcesses.MARK_PAID, sponsorToken(sponsorB()), body("cashRewardId", cashRewardId), 404);
        refused(CashRewardProcesses.CONFIRM, sponsorToken(sponsor), body("cashRewardId", cashRewardId), 404);
        // Not yet marked paid: nothing to confirm.
        assertThat(codes(refused(CashRewardProcesses.CONFIRM, token, body("cashRewardId", cashRewardId), 422)))
            .containsExactly(RewardCodes.CASH_STATE);

        clock.advance(Duration.ofHours(1));
        token = taker(takerId);
        Map<String, Object> marked = run(CashRewardProcesses.MARK_PAID, sponsorToken(sponsor),
            body("cashRewardId", cashRewardId, "note", "Receipt 17"));
        assertThat(marked).containsEntry("status", "MARKED_PAID").containsEntry("revision", 2);
        reward = cashReward(attemptId);
        assertThat(reward).containsEntry("marked_paid_note", "Receipt 17");
        assertThat(at(reward.get("confirm_by")))
            .isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(codes(refused(CashRewardProcesses.MARK_PAID, sponsorToken(sponsor),
            body("cashRewardId", cashRewardId), 422))).containsExactly(RewardCodes.CASH_STATE);
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(takerId)).containsExactly("CASH_DUE", "CASH_MARKED_PAID");
        assertThat(InboxItSupport.messagesOf(takerId).getLast()).containsEntry("action", "RESULT")
            .containsEntry("action_id", attemptId);

        assertThat(run(CashRewardProcesses.CONFIRM, token, body("cashRewardId", cashRewardId)))
            .containsEntry("status", "CONFIRMED");
        assertThat(cashReward(attemptId)).containsEntry("status", "CONFIRMED");
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(sponsor)).containsExactly("CASH_CONFIRMED");
        assertThat(InboxItSupport.messagesOf(sponsor).getFirst()).containsEntry("action", "CASH")
            .containsEntry("action_id", cashRewardId);
        // Q68: in the app the sponsor reads it under the sponsor's category; the taker's own are not there.
        assertThat(rows("qb.app.inbox", taker(sponsor), Map.of("category", "SPONSOR"))).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("kind", "CASH_CONFIRMED"));
        assertThat(rows("qb.app.inbox", token, Map.of("category", "SPONSOR"))).isEmpty();
        assertThat(rows("qb.app.cash-rewards", token, Map.of("open", true))).isEmpty();
        // Only ever inserted into.
        assertThat(query("SELECT count(*) AS n FROM qb_cash_reward_version WHERE attempt_id = ?", attemptId)
            .getFirst().get("n")).isEqualTo(3L);
        assertBooksBalance();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOverdueCashRewardStopsTheTakerUntilTheyConfirmOrDispute() {
        String sponsor = newSponsor("s-q22-overdue");
        allow(sponsor, RewardMethod.PLATFORM, RewardMethod.SPONSOR_CASH);
        String publicationId = (String) cashOf(sponsor, "Overdue quiz").get("publicationId");
        // Open long enough for the weeks this test lets pass.
        String other = livePublication(sponsorA(), "Other quiz", Map.of("regions", List.of("GLOBAL"),
            "endAt", clock.instant().plus(Duration.ofDays(60))));
        credit(sponsorA(), 1_000);
        String takerId = newTaker("t-q22-overdue", null);
        // Tokens are signed for a while only: the test's days pass, so the taker signs in again as it goes.
        String token = taker(takerId);
        String attemptId = (String) start(token, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        submit(token, attemptId, answers(true, true));
        String cashRewardId = String.valueOf(cashReward(attemptId).get("cash_reward_id"));
        run(CashRewardProcesses.MARK_PAID, sponsorToken(sponsor), body("cashRewardId", cashRewardId));

        // Within the time: nothing happens, and the taker plays on.
        clock.advance(Duration.ofDays(6));
        assertThat(runJob(overdueJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(cashReward(attemptId)).containsEntry("status", "MARKED_PAID");
        // Past it: overdue, and no new attempt.
        clock.advance(Duration.ofDays(2));
        token = taker(takerId);
        assertThat(runJob(overdueJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(cashReward(attemptId)).containsEntry("status", "OVERDUE");
        Map<String, Object> refusal = refused("QB_ATTEMPT_START", token, body("publicationId", other), 422);
        assertThat(codes(refusal)).containsExactly(RewardCodes.CASH_CONFIRM_DUE);
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(takerId)).contains("CASH_OVERDUE");
        // A run again changes nothing.
        assertThat(runJob(overdueJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(cashReward(attemptId)).containsEntry("status", "OVERDUE").containsEntry("revision",
            new BigDecimal("3"));

        // Disputing lifts the stop; an administrator gets a task; the sponsor hears.
        assertThat(codes(refused(CashRewardProcesses.DISPUTE, token, body("cashRewardId", cashRewardId, "note",
            " "), 400))).isNotEmpty();
        assertThat(run(CashRewardProcesses.DISPUTE, token, body("cashRewardId", cashRewardId, "note",
            "I got 50, not 100"))).containsEntry("status", "DISPUTED");
        assertThat(permissionTasks(QbPermissions.CASH_RESOLVE, "qb.cash-dispute:" + cashRewardId))
            .singleElement().satisfies(task -> assertThat(task).containsEntry("task_type", "qb.cash-reward.dispute")
                .containsEntry("status", "OPEN"));
        String otherAttempt = (String) start(token, other).get("attemptId");
        assertThat(otherAttempt).isNotBlank();
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(sponsor)).contains("CASH_DISPUTED");

        // Only an administrator resolves; the sponsor still owes it after "unpaid", and the task is closed.
        refused(CashRewardProcesses.RESOLVE, sponsorToken(sponsor), body("cashRewardId", cashRewardId,
            "resolution", "PAID"), 403);
        assertThat(codes(refused(CashRewardProcesses.RESOLVE, methodsAdmin(), body("cashRewardId", cashRewardId,
            "resolution", "MAYBE"), 422))).containsExactly(RewardCodes.CASH_RESOLUTION);
        assertThat(run(CashRewardProcesses.RESOLVE, methodsAdmin(), body("cashRewardId", cashRewardId, "resolution",
            "UNPAID", "note", "The sponsor agrees"))).containsEntry("status", "DUE");
        Map<String, Object> reward = cashReward(attemptId);
        assertThat(reward).containsEntry("resolution", "UNPAID").containsEntry("marked_paid_at", null)
            .containsEntry("confirm_by", null).containsEntry("overdue_at", null).containsEntry("reminded_at", null);
        // The sponsor's time to pay starts afresh from the decision.
        assertThat(at(reward.get("pay_by"))).isEqualTo(clock.instant().plus(Duration.ofDays(14)));
        assertThat(permissionTasks(QbPermissions.CASH_RESOLVE, "qb.cash-dispute:" + cashRewardId))
            .singleElement().satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(takerId)).contains("CASH_RESOLVED");
        assertThat(InboxItSupport.kindsOf(sponsor)).contains("CASH_RESOLVED_SPONSOR");

        // Owed again and late: the daily job reminds the sponsor once; marking it paid closes the task.
        clock.advance(Duration.ofDays(15));
        token = taker(takerId);
        assertThat(runJob(remindJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(cashReward(attemptId).get("reminded_at")).isNotNull();
        assertThat(openTasksOf(sponsor)).anySatisfy(task -> assertThat(task)
            .containsEntry("task_type", "qb.cash-reward.pay").containsEntry("source_key", "qb.cash-pay:" + cashRewardId));
        assertThat(runJob(remindJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(openTasksOf(sponsor)).filteredOn(task -> "qb.cash-reward.pay".equals(task.get("task_type")))
            .hasSize(1);
        run(CashRewardProcesses.MARK_PAID, sponsorToken(sponsor), body("cashRewardId", cashRewardId));
        assertThat(openTasksOf(sponsor)).noneMatch(task -> "qb.cash-reward.pay".equals(task.get("task_type")));
        // The second round has its own message (the first "paid" was undone; its own message was never made here, as
        // this test let its event grow older than the inbox takes), and the decision names the state in words.
        deliverEvents();
        assertThat(InboxItSupport.messagesOf(takerId)).filteredOn(m -> "CASH_MARKED_PAID".equals(m.get("kind")))
            .singleElement().satisfies(m -> assertThat(at(m.get("created_at"))).isEqualTo(clock.instant()));
        assertThat(InboxItSupport.messagesOf(takerId)).filteredOn(m -> "CASH_RESOLVED".equals(m.get("kind")))
            .singleElement().satisfies(m -> assertThat(String.valueOf(m.get("body")))
                .contains("to be paid by the sponsor").doesNotContain("DUE"));
        // Disputed again and found paid: confirmed by the administrator.
        run(CashRewardProcesses.DISPUTE, token, body("cashRewardId", cashRewardId, "note", "Still nothing"));
        assertThat(run(CashRewardProcesses.RESOLVE, methodsAdmin(), body("cashRewardId", cashRewardId, "resolution",
            "PAID", "note", "The receipt shows it"))).containsEntry("status", "CONFIRMED");
        assertThat(codes(refused(CashRewardProcesses.RESOLVE, methodsAdmin(), body("cashRewardId", cashRewardId,
            "resolution", "VOID"), 422))).containsExactly(RewardCodes.CASH_STATE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPrizeIsRecordedWithWhatItIsAndUntilWhen() {
        String sponsor = newSponsor("s-q22-prize");
        allow(sponsor, RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM);
        String token = sponsorToken(sponsor);
        String versionId = version(token, "Prize quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Prize week", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, save);
        String publicationId = (String) saved.get("publicationId");
        // No description: the save says so, and submitting refuses.
        assertThat(problemCodes(saved)).contains(PublishingCodes.FIELD_REQUIRED);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId), 422)))
            .contains(PublishingCodes.FIELD_REQUIRED);
        save.put("publicationId", publicationId);
        save.put("redeemDescription", "A coffee at our counter, Shibuya, 9-18h");
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", token, save))).isEmpty();
        assertThat(submit(token, publicationId)).containsEntry("status", "LIVE");
        assertThat(card(publicationId)).containsEntry("reward_method", "OFFLINE_REDEEM")
            .containsEntry("redeem_description", "A coffee at our counter, Shibuya, 9-18h");

        String takerId = newTaker("t-q22-prize", null);
        String takerToken = taker(takerId);
        String attemptId = (String) start(takerToken, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(takerToken, attemptId, answers(true, true))).containsEntry("rewardStatus", "REDEEM");
        Map<String, Object> redemption = redemption(attemptId);
        assertThat(redemption).containsEntry("status", "DUE").containsEntry("amount", new BigDecimal("100"))
            .containsEntry("description", "A coffee at our counter, Shibuya, 9-18h");
        // 30 days to collect, longer than the publication's week.
        assertThat(at(redemption.get("expires_at")))
            .isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        assertThat(postingOf(attemptId)).isEmpty();
        Map<String, Object> redeem = (Map<String, Object>) result(takerToken, attemptId).get("redeem");
        assertThat(redeem).containsEntry("status", "DUE").containsEntry("description",
            "A coffee at our counter, Shibuya, 9-18h").containsEntry("amount", 100);
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(takerId)).containsExactly("REDEEM_DUE");
        assertThat(String.valueOf(InboxItSupport.messagesOf(takerId).getFirst().get("body"))).contains("Shibuya");
        assertThat(rows("qb.app.history", takerToken, Map.of("at", clock.instant().toString()))).anySatisfy(row ->
            assertThat(row).containsEntry("attemptId", attemptId).containsEntry("redeemStatus", "DUE"));
    }
}

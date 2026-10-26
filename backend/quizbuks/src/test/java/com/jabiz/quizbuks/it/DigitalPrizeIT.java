package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.DigitalCodeProcesses;
import com.jabiz.quizbuks.reward.DigitalPrizeProcesses;
import com.jabiz.quizbuks.reward.DigitalPrizeStatus;
import com.jabiz.quizbuks.reward.RewardCodes;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Digital prizes (docs/quizbuks/plans/Q33-digital-prize.md, Q33a): a publication whose prize is a code or a link the
 * winner gets without going anywhere. A shared one is sealed with the publication and handed to every winner at once;
 * a pool of codes is loaded by the sponsor, must cover the winners before the publication goes live, and is issued in
 * the order loaded. The winner sees the content only by opening it, confirms it, or disputes it for an administrator
 * to decide. The content is never in a list, a result, a message or an operation record; nothing is paid from a
 * wallet.
 */
class DigitalPrizeIT extends PlayItSupport {

    private static final String LINK = "https://capitals.example/premium?code=WELCOME-2026";

    private String digitalAdmin() {
        return TestTokens.bearer(tokens, "q33-admin", QbPermissions.ADMIN_REWARD_METHODS, QbPermissions.DIGITAL_RESOLVE,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private void allowDigital(String sponsor) {
        run(RewardMethodProcesses.SET, digitalAdmin(), body("userId", sponsor, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.DIGITAL_PRIZE), "note", "Q33 test"));
    }

    private Map<String, Object> prize(String attemptId) {
        return latest("qb_digital_prize_version", "digital_prize_id", "attempt_id = '" + attemptId + "'").getFirst();
    }

    private static List<Map<String, Object>> codesOf(String publicationId) {
        return query("SELECT seq, status, hint, sealed_content, issued_attempt_id FROM qb_digital_code "
            + "WHERE publication_id = ?::uuid ORDER BY seq", publicationId);
    }

    private static List<Map<String, Object>> permissionTasks(String permission, String sourceKey) {
        return query("SELECT task_type, status FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version "
            + "ORDER BY task_id, version_no DESC) t WHERE assignee_permission = ? AND source_key = ? "
            + "AND NOT is_deleted", permission, sourceKey);
    }

    private List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 200));
    }

    private Map<String, Object> result(String token, String attemptId) {
        return run("QB_ATTEMPT_RESULT", token, body("attemptId", attemptId));
    }

    private static Instant at(Object value) {
        return value instanceof java.sql.Timestamp time ? time.toInstant()
            : value instanceof java.time.OffsetDateTime time ? time.toInstant()
            : value instanceof String text ? Instant.parse(text) : (Instant) value;
    }

    private static Map<String, Object> digital(String delivery, String description) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.DIGITAL_PRIZE);
        overrides.put("digitalDelivery", delivery);
        overrides.put("digitalDescription", description);
        return overrides;
    }

    @SuppressWarnings("unchecked")
    private static List<String> problemCodes(Map<String, Object> saved) {
        return ((List<Map<String, Object>>) saved.get("problems")).stream().map(p -> (String) p.get("code")).toList();
    }

    /** The operation records of a process, newest last: the input summaries must never hold the content. */
    private static List<String> summaries(String process) {
        return query("SELECT coalesce(input_summary::text, '') AS s "
            + "FROM op_process WHERE process_name = ? ORDER BY process_seq_id", process)
            .stream().map(row -> (String) row.get("s")).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSharedPrizeIsSealedHandedToEveryWinnerAtOnceShownAndConfirmed() {
        String sponsor = newSponsor("s-q33-shared");
        String sponsorToken = sponsorToken(sponsor);
        // Not allowed yet.
        Map<String, Object> save = complete(version(sponsorToken, "Shared quiz"), "Shared week", clock.instant(),
            clock.instant().plus(Duration.ofDays(7)));
        save.putAll(digital(DigitalDelivery.SHARED, "A month of Premium"));
        assertThat(codes(refused("QB_PUBLICATION_SAVE", sponsorToken, save, 422)))
            .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
        allowDigital(sponsor);
        // A digital prize without its delivery and description, and a shared one without its content, cannot go live.
        save.remove("digitalDelivery");
        save.remove("digitalDescription");
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", sponsorToken, save);
        assertThat(problemCodes(saved)).containsExactly(PublishingCodes.DIGITAL_DELIVERY_INVALID,
            PublishingCodes.FIELD_REQUIRED);
        String draft = (String) saved.get("publicationId");
        save.put("publicationId", draft);
        save.putAll(digital(DigitalDelivery.SHARED, "A month of Premium"));
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", sponsorToken, save))).containsExactly(
            PublishingCodes.FIELD_REQUIRED);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken, body("publicationId", draft), 422)))
            .containsExactly(PublishingCodes.FIELD_REQUIRED);
        save.put("digitalContent", LINK);
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", sponsorToken, save))).isEmpty();
        // Saved again without the content: the sealed one stays.
        save.remove("digitalContent");
        save.put("digitalDescription", "A month of Premium, on us");
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", sponsorToken, save))).isEmpty();
        assertThat(submit(sponsorToken, draft)).containsEntry("status", "LIVE");
        String publicationId = draft;
        Map<String, Object> card = card(publicationId);
        assertThat(card).containsEntry("reward_method", "DIGITAL_PRIZE").containsEntry("digital_delivery", "SHARED")
            .containsEntry("digital_description", "A month of Premium, on us");
        assertThat((String) card.get("sealed_digital_content")).isNotBlank().doesNotContain("WELCOME");
        // The content is in no record of the save.
        assertThat(summaries("QB_PUBLICATION_SAVE")).allSatisfy(s -> assertThat(s).doesNotContain("WELCOME"));
        assertThat(wallet(sponsor)).isNull();

        String takerId = newTaker("t-q33-shared", "JP");
        String token = taker(takerId);
        assertThat(feed(token, "JP", Map.of("method", RewardMethod.DIGITAL_PRIZE))).anySatisfy(item ->
            assertThat(item).containsEntry("publicationId", publicationId).containsEntry("rewardMethod", "DIGITAL_PRIZE")
                .containsEntry("digitalDelivery", "SHARED").containsEntry("digitalDescription",
                    "A month of Premium, on us").doesNotContainKey("sealedDigitalContent"));
        String attemptId = (String) start(token, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        Map<String, Object> submitted = submit(token, attemptId, answers(true, true));
        assertThat(submitted).containsEntry("rewardStatus", "DIGITAL").containsEntry("reward", 100);
        assertThat((String) submitted.get("message")).contains("Show prize").doesNotContain("WELCOME");
        assertThat(postingOf(attemptId)).isEmpty();
        assertThat(wallet(sponsor)).isNull();
        assertThat(counter(publicationId)).containsEntry("winners", new BigDecimal("1"));
        Map<String, Object> prize = prize(attemptId);
        assertThat(prize).containsEntry("status", "DELIVERED").containsEntry("delivery", "SHARED")
            .containsEntry("delivered_by", DigitalPrizeStatus.BY_SYSTEM).containsEntry("user_id", takerId)
            .containsEntry("sponsor_id", sponsor).containsEntry("amount", new BigDecimal("100"))
            .containsEntry("hint", null).containsEntry("opened_at", null).containsEntry("send_by", null);
        assertThat((String) prize.get("sealed_content")).isNotBlank().doesNotContain("WELCOME");
        assertThat(at(prize.get("delivered_at"))).isEqualTo(clock.instant());
        String digitalPrizeId = String.valueOf(prize.get("digital_prize_id"));
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(takerId)).containsExactly("DIGITAL_DELIVERED");
        assertThat(InboxItSupport.messagesOf(takerId).getFirst()).containsEntry("action", "RESULT")
            .containsEntry("action_id", attemptId);
        assertThat(String.valueOf(InboxItSupport.messagesOf(takerId).getFirst().get("body")))
            .contains("A month of Premium").doesNotContain("WELCOME");

        // The result and the lists name the prize, never the content (a shared one has no hint: it is sealed once,
        // with the publication, and the sponsor knows it).
        Map<String, Object> resultOutput = result(token, attemptId);
        Map<String, Object> view = (Map<String, Object>) resultOutput.get("digital");
        assertThat(view).containsEntry("digitalPrizeId", digitalPrizeId).containsEntry("status", "DELIVERED")
            .containsEntry("delivery", "SHARED").containsEntry("hint", null).containsEntry("amount", 100)
            .containsEntry("openedAt", null).doesNotContainKey("prizeContent");
        assertThat(resultOutput).containsEntry("rewardMethod", "DIGITAL_PRIZE").containsEntry("cash", null);
        assertThat(resultOutput.toString()).doesNotContain("WELCOME");
        assertThat(rows("qb.app.history", token, Map.of("at", clock.instant().toString()))).anySatisfy(row ->
            assertThat(row).containsEntry("attemptId", attemptId).containsEntry("digitalStatus", "DELIVERED")
                .containsEntry("digitalPrizeId", digitalPrizeId).containsEntry("rewardMethod", "DIGITAL_PRIZE"));
        assertThat(rows("qb.sponsor.digital-prizes", sponsorToken, Map.of("q", "Taker JP"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("digitalPrizeId", digitalPrizeId)
                .containsEntry("hint", null).containsEntry("publicationName", "Shared week")
                .doesNotContainKey("sealedContent"));
        assertThat(rows("qb.admin.digital-prizes", digitalAdmin(), Map.of("q", "Taker JP"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("publisherId", sponsor).doesNotContainKey("sealedContent"));

        // Nobody else opens it: another taker, the sponsor (404, as if it were not there).
        refused(DigitalPrizeProcesses.OPEN, taker(u2()), body("digitalPrizeId", digitalPrizeId), 404);
        refused(DigitalPrizeProcesses.OPEN, sponsorToken, body("digitalPrizeId", digitalPrizeId), 404);
        refused(DigitalPrizeProcesses.OPEN, token, body("digitalPrizeId", UUID.randomUUID().toString()), 404);
        clock.advance(Duration.ofMinutes(1));
        // As the App calls it: with an idempotency key, so that the output is kept for a replay.
        Map<String, Object> opened = (Map<String, Object>) runWithKey(DigitalPrizeProcesses.OPEN, token,
            body("digitalPrizeId", digitalPrizeId), UUID.randomUUID().toString()).get("output");
        assertThat(opened).containsEntry("prizeContent", LINK).containsEntry("status", "DELIVERED")
            .containsEntry("description", "A month of Premium, on us");
        assertThat(at(opened.get("openedAt"))).isEqualTo(clock.instant());
        // Opened again later: the first time stays, and nothing is written.
        clock.advance(Duration.ofMinutes(1));
        Map<String, Object> again = run(DigitalPrizeProcesses.OPEN, token, body("digitalPrizeId", digitalPrizeId));
        assertThat(again).containsEntry("prizeContent", LINK);
        assertThat(at(again.get("openedAt"))).isEqualTo(clock.instant().minus(Duration.ofMinutes(1)));
        assertThat(summaries(DigitalPrizeProcesses.OPEN)).isNotEmpty()
            .allSatisfy(s -> assertThat(s).doesNotContain("WELCOME"));
        // The client sends an idempotency key, so the output is kept for a replay: without the content.
        assertThat(query("SELECT r.output::text AS s FROM op_process_result r JOIN op_process p "
            + "ON p.process_seq_id = r.process_seq_id WHERE p.process_name = ?", DigitalPrizeProcesses.OPEN))
            .isNotEmpty().allSatisfy(row -> assertThat((String) row.get("s")).doesNotContain("WELCOME"));

        assertThat(run(DigitalPrizeProcesses.CONFIRM, token, body("digitalPrizeId", digitalPrizeId)))
            .containsEntry("status", "CONFIRMED");
        assertThat(codes(refused(DigitalPrizeProcesses.CONFIRM, token, body("digitalPrizeId", digitalPrizeId), 422)))
            .containsExactly(RewardCodes.DIGITAL_STATE);
        assertThat(prize(attemptId)).containsEntry("status", "CONFIRMED");
        // Confirmed, it still opens.
        assertThat(run(DigitalPrizeProcesses.OPEN, token, body("digitalPrizeId", digitalPrizeId)))
            .containsEntry("prizeContent", LINK).containsEntry("status", "CONFIRMED");
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(sponsor)).containsExactly("DIGITAL_CONFIRMED");
        assertThat(InboxItSupport.messagesOf(sponsor).getFirst()).containsEntry("action", "DIGITAL")
            .containsEntry("action_id", digitalPrizeId);
        // Delivered, opened, confirmed: only ever inserted into.
        assertThat(query("SELECT count(*) AS n FROM qb_digital_prize_version WHERE attempt_id = ?", attemptId)
            .getFirst().get("n")).isEqualTo(3L);
        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed FROM pg_stat_user_tables "
            + "WHERE schemaname = current_schema() AND relname = 'qb_digital_prize_version'").getFirst().get("changed"))
            .satisfies(changed -> assertThat(((Number) changed).longValue()).isZero());
        assertBooksBalance();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPoolCoversTheWinnersAndHandsOutItsCodesInOrder() {
        String sponsor = newSponsor("s-q33-pool");
        allowDigital(sponsor);
        String sponsorToken = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(sponsorToken, "Pool quiz"), "Pool week", now,
            now.plus(Duration.ofDays(7)));
        save.putAll(digital(DigitalDelivery.POOL, "A 1000 yen gift code"));
        save.put("maxWinners", 2);
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", sponsorToken, save);
        assertThat(problemCodes(saved)).isEmpty();
        String publicationId = (String) saved.get("publicationId");
        // An empty pool: the submission says how short it is.
        Map<String, Object> refusal = refused("QB_PUBLICATION_SUBMIT", sponsorToken, body("publicationId",
            publicationId), 422);
        assertThat(codes(refusal)).containsExactly(PublishingCodes.DIGITAL_POOL_SHORT);
        // Loading: the sponsor's own publication only, pooled only, codes within their limits, blank lines and
        // repeats dropped.
        refused(DigitalCodeProcesses.LOAD, sponsorToken(sponsorB()), body("publicationId", publicationId, "pastedCodes",
            "X-1"), 404);
        String shared = (String) run("QB_PUBLICATION_SAVE", sponsorToken, complete(version(sponsorToken,
            "Other quiz"), "Shared week", now, now.plus(Duration.ofDays(7)))).get("publicationId");
        assertThat(codes(refused(DigitalCodeProcesses.LOAD, sponsorToken, body("publicationId", shared, "pastedCodes",
            "X-1"), 422))).containsExactly(RewardCodes.DIGITAL_NOT_POOLED);
        // Nothing to load is refused before the process runs (the lines are required).
        refused(DigitalCodeProcesses.LOAD, sponsorToken, body("publicationId", publicationId, "pastedCodes", " \n\n"), 400);
        assertThat(codes(refused(DigitalCodeProcesses.LOAD, sponsorToken, body("publicationId", publicationId,
            "pastedCodes", "A-1\n" + "x".repeat(201)), 422))).containsExactly(RewardCodes.DIGITAL_CODE_TOO_LONG);
        Map<String, Object> loaded = run(DigitalCodeProcesses.LOAD, sponsorToken, body("publicationId", publicationId,
            "pastedCodes", "GIFT-0001-AAAA\n\n  GIFT-0002-BBBB  \nGIFT-0001-AAAA\n"));
        assertThat(loaded).containsEntry("loaded", 2).containsEntry("available", 2);
        assertThat(summaries(DigitalCodeProcesses.LOAD)).isNotEmpty()
            .allSatisfy(s -> assertThat(s).doesNotContain("GIFT-"));
        assertThat(codesOf(publicationId)).hasSize(2).allSatisfy(code -> assertThat((String) code.get("sealed_content"))
            .doesNotContain("GIFT")).extracting(code -> code.get("hint")).containsExactly("…AAAA", "…BBBB");
        assertThat(rows("qb.sponsor.digital-codes", sponsorToken, Map.of("publicationId", publicationId)))
            .extracting(row -> row.get("status")).containsExactly("AVAILABLE", "AVAILABLE");
        assertThat(rows("qb.sponsor.digital-pool-summary", sponsorToken, Map.of("publicationId", publicationId)))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("available", 2).containsEntry("issued", 0)
                .containsEntry("revoked", 0));
        // Three winners for two codes: still short; two winners: live.
        save.put("publicationId", publicationId);
        save.put("maxWinners", 3);
        run("QB_PUBLICATION_SAVE", sponsorToken, save);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken, body("publicationId", publicationId), 422)))
            .containsExactly(PublishingCodes.DIGITAL_POOL_SHORT);
        save.put("maxWinners", 2);
        run("QB_PUBLICATION_SAVE", sponsorToken, save);
        assertThat(submit(sponsorToken, publicationId)).containsEntry("status", "LIVE");
        // Running: the codes stay.
        assertThat(codes(refused(DigitalCodeProcesses.REVOKE, sponsorToken, body("publicationId", publicationId),
            422))).containsExactly(RewardCodes.DIGITAL_POOL_LOCKED);

        // Another sponsor's live pool with codes of its own: a pick must never reach into it.
        String otherSponsor = newSponsor("s-q33-pool-other");
        allowDigital(otherSponsor);
        Map<String, Object> otherSave = complete(version(sponsorToken(otherSponsor), "Other pool quiz"), "Other pool",
            now, now.plus(Duration.ofDays(7)));
        otherSave.putAll(digital(DigitalDelivery.POOL, "Another code"));
        otherSave.put("maxWinners", 1);
        String otherPool = (String) run("QB_PUBLICATION_SAVE", sponsorToken(otherSponsor), otherSave).get("publicationId");
        run(DigitalCodeProcesses.LOAD, sponsorToken(otherSponsor), body("publicationId", otherPool, "pastedCodes", "OTHER-0000-ZZZZ"));
        assertThat(submit(sponsorToken(otherSponsor), otherPool)).containsEntry("status", "LIVE");

        String first = newTaker("t-q33-pool-1", "JP");
        String second = newTaker("t-q33-pool-2", "JP");
        String firstToken = taker(first);
        String secondToken = taker(second);
        String firstAttempt = (String) start(firstToken, publicationId).get("attemptId");
        String secondAttempt = (String) start(secondToken, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(secondToken, secondAttempt, answers(true, true))).containsEntry("rewardStatus", "DIGITAL");
        assertThat(submit(firstToken, firstAttempt, answers(true, true))).containsEntry("rewardStatus", "DIGITAL");
        // Issued in the order loaded, to the attempt that won first.
        assertThat(prize(secondAttempt)).containsEntry("status", "DELIVERED").containsEntry("delivery", "POOL")
            .containsEntry("hint", "…AAAA");
        assertThat(prize(firstAttempt)).containsEntry("hint", "…BBBB");
        assertThat(codesOf(publicationId)).extracting(code -> code.get("status"))
            .containsExactly("ISSUED", "ISSUED");
        assertThat(codesOf(publicationId).getFirst()).containsEntry("issued_attempt_id", secondAttempt);
        assertThat(codesOf(otherPool)).singleElement().satisfies(code -> assertThat(code).containsEntry("status",
            "AVAILABLE"));
        assertThat(run(DigitalPrizeProcesses.OPEN, secondToken, body("digitalPrizeId",
            String.valueOf(prize(secondAttempt).get("digital_prize_id"))))).containsEntry("prizeContent",
            "GIFT-0001-AAAA");
        assertThat(run(DigitalPrizeProcesses.OPEN, firstToken, body("digitalPrizeId",
            String.valueOf(prize(firstAttempt).get("digital_prize_id"))))).containsEntry("prizeContent",
            "GIFT-0002-BBBB");
        assertThat(rows("qb.sponsor.digital-pool-summary", sponsorToken, Map.of("publicationId", publicationId)))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("available", 0).containsEntry("issued", 2));
        assertThat(postingOf(firstAttempt)).isEmpty();
        assertThat(wallet(sponsor)).isNull();

        // A dispute: a note is needed; an administrator gets a task and the sponsor hears; found undelivered, the
        // sponsor owes it again, by hand, within the days of the parameter; the content is gone.
        String digitalPrizeId = String.valueOf(prize(firstAttempt).get("digital_prize_id"));
        refused(DigitalPrizeProcesses.DISPUTE, firstToken, body("digitalPrizeId", digitalPrizeId, "note", " "), 400);
        assertThat(run(DigitalPrizeProcesses.DISPUTE, firstToken, body("digitalPrizeId", digitalPrizeId, "note",
            "The code was used already"))).containsEntry("status", "DISPUTED");
        assertThat(permissionTasks(QbPermissions.DIGITAL_RESOLVE, "qb.digital-dispute:" + digitalPrizeId))
            .singleElement().satisfies(task -> assertThat(task).containsEntry("task_type", "qb.digital-prize.dispute")
                .containsEntry("status", "OPEN"));
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(sponsor)).contains("DIGITAL_DISPUTED");
        // Disputed, it still opens (the taker shows the sponsor what they got).
        assertThat(run(DigitalPrizeProcesses.OPEN, firstToken, body("digitalPrizeId", digitalPrizeId)))
            .containsEntry("prizeContent", "GIFT-0002-BBBB").containsEntry("status", "DISPUTED");
        refused(DigitalPrizeProcesses.RESOLVE, sponsorToken, body("digitalPrizeId", digitalPrizeId, "resolution",
            "DELIVERED"), 403);
        assertThat(codes(refused(DigitalPrizeProcesses.RESOLVE, digitalAdmin(), body("digitalPrizeId", digitalPrizeId,
            "resolution", "MAYBE"), 422))).containsExactly(RewardCodes.DIGITAL_RESOLUTION);
        clock.advance(Duration.ofHours(1));
        // A token lasts less than the hour: both signed again.
        firstToken = taker(first);
        sponsorToken = sponsorToken(sponsor);
        assertThat(run(DigitalPrizeProcesses.RESOLVE, digitalAdmin(), body("digitalPrizeId", digitalPrizeId,
            "resolution", "UNDELIVERED", "note", "The sponsor agrees"))).containsEntry("status", "DUE");
        Map<String, Object> owed = prize(firstAttempt);
        assertThat(owed).containsEntry("resolution", "UNDELIVERED").containsEntry("sealed_content", null)
            .containsEntry("hint", null).containsEntry("delivered_at", null).containsEntry("resolve_note",
                "The sponsor agrees");
        assertThat(at(owed.get("send_by"))).isEqualTo(clock.instant().plus(Duration.ofDays(3)));
        assertThat(permissionTasks(QbPermissions.DIGITAL_RESOLVE, "qb.digital-dispute:" + digitalPrizeId))
            .singleElement().satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
        assertThat(codes(refused(DigitalPrizeProcesses.OPEN, firstToken, body("digitalPrizeId", digitalPrizeId),
            422))).containsExactly(RewardCodes.DIGITAL_STATE);
        deliverEvents();
        assertThat(InboxItSupport.kindsOf(first)).contains("DIGITAL_RESOLVED");
        assertThat(InboxItSupport.messagesOf(first)).filteredOn(m -> "DIGITAL_RESOLVED".equals(m.get("kind")))
            .singleElement().satisfies(m -> assertThat(String.valueOf(m.get("body")))
                .contains("to be sent by the sponsor").doesNotContain("DUE"));
        assertThat(InboxItSupport.kindsOf(sponsor)).contains("DIGITAL_RESOLVED_SPONSOR");
        assertThat(rows("qb.app.history", firstToken, Map.of("at", clock.instant().toString()))).anySatisfy(row ->
            assertThat(row).containsEntry("attemptId", firstAttempt).containsEntry("digitalStatus", "DUE"));
        // Owed: not disputed again until it is delivered; void by an administrator.
        assertThat(codes(refused(DigitalPrizeProcesses.RESOLVE, digitalAdmin(), body("digitalPrizeId", digitalPrizeId,
            "resolution", "DELIVERED"), 422))).containsExactly(RewardCodes.DIGITAL_STATE);
        assertThat(run(DigitalPrizeProcesses.RESOLVE, digitalAdmin(), body("digitalPrizeId", digitalPrizeId,
            "resolution", "VOID", "note", "The taker asked"))).containsEntry("status", "VOID");

        // Codes of a publication that never went live are taken back; the lists show them.
        String spare = (String) run("QB_PUBLICATION_SAVE", sponsorToken, complete(version(sponsorToken,
            "Spare quiz"), "Spare week", now, now.plus(Duration.ofDays(7)))).get("publicationId");
        Map<String, Object> spareSave = complete(version(sponsorToken, "Spare quiz 2"), "Spare week", now,
            now.plus(Duration.ofDays(7)));
        spareSave.put("publicationId", spare);
        spareSave.putAll(digital(DigitalDelivery.POOL, "Spare codes"));
        run("QB_PUBLICATION_SAVE", sponsorToken, spareSave);
        run(DigitalCodeProcesses.LOAD, sponsorToken, body("publicationId", spare, "pastedCodes", "S-1\nS-2\nS-3"));
        assertThat(run(DigitalCodeProcesses.REVOKE, sponsorToken, body("publicationId", spare)))
            .containsEntry("revoked", 3).containsEntry("available", 0);
        assertThat(run(DigitalCodeProcesses.REVOKE, sponsorToken, body("publicationId", spare)))
            .containsEntry("revoked", 0);
        assertThat(codesOf(spare)).extracting(code -> code.get("status")).containsExactly("REVOKED", "REVOKED",
            "REVOKED");
        assertThat(rows("qb.sponsor.digital-pool-summary", sponsorToken, Map.of("publicationId", spare)))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("revoked", 3));
        // Short codes show no hint.
        assertThat(codesOf(spare)).allSatisfy(code -> assertThat(code.get("hint")).isNull());
        // A pool holds at most what it may cover: the sequence counts the revoked ones too.
        String thousand = java.util.stream.IntStream.rangeClosed(1, 1000).mapToObj(i -> "K-" + i)
            .collect(java.util.stream.Collectors.joining("\n"));
        for (int load = 0; load < 9; load++) {
            run(DigitalCodeProcesses.LOAD, sponsorToken, body("publicationId", spare, "pastedCodes", thousand));
        }
        assertThat(run(DigitalCodeProcesses.LOAD, sponsorToken, body("publicationId", spare, "pastedCodes",
            java.util.stream.IntStream.rangeClosed(1, 997).mapToObj(i -> "L-" + i)
                .collect(java.util.stream.Collectors.joining("\n"))))).containsEntry("available", 9997);
        assertThat(codes(refused(DigitalCodeProcesses.LOAD, sponsorToken, body("publicationId", spare, "pastedCodes",
            "M-1\nM-2"), 422))).containsExactly(RewardCodes.DIGITAL_CODES_TOO_MANY);
        assertBooksBalance();
    }
}

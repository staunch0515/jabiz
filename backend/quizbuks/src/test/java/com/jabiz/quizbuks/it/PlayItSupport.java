package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.AttemptFinishing;
import com.jabiz.quizbuks.play.PublicationStats;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answering integration tests (docs/quizbuks/plans/Q5-answering.md, 测试): real platform users throughout, since the
 * ledger's party is a platform user: sponsors A and B (onboarded), takers U1 (Japan) and U2 (the United States) with a
 * verified address and U3 without. Publications go on at once (the review switch is on), and the development credit
 * stands in for top-ups. Each class installs QuizBuks once, as the publishing tests do; users are made once per
 * schema and found again by name.
 */
abstract class PlayItSupport extends AccountItSupport {

    /** The tables of answering and wallets: ordinary, written only by the processes. */
    static final List<String> PLAY_TABLES = List.of("qb_attempt", "qb_publication_view", "qb_publication_card",
        "qb_publication_stats", "qb_wallet");

    /** The ledger's tables, only ever inserted into. */
    static final List<String> LEDGER_TABLES = List.of("ledger_transaction_version", "ledger_entry_version");

    @Autowired
    JobRunner jobRunner;

    @Autowired
    @Qualifier("qbPublicationStatsJob")
    JobDefinition<PublicationStats.RefreshInput> statsJob;

    @Autowired
    @Qualifier("qbAttemptExpireJob")
    JobDefinition<AttemptFinishing.ExpireInput> expireJob;

    @BeforeEach
    void players() {
        if (query("SELECT 1 FROM sec_user_version WHERE user_name = 'u1-taker'").isEmpty()) {
            autoApprove(true);
            onboard(user("a-sponsor"));
            onboard(user("b-sponsor"));
            profile(user("u1-taker"), "JP");
            profile(user("u2-taker"), "US");
            user("u3-taker");
        }
    }

    /** A platform user by name, made the first time. */
    String user(String name) {
        List<Map<String, Object>> rows = query("SELECT DISTINCT user_id FROM sec_user_version WHERE user_name = ?",
            name);
        return rows.isEmpty() ? createUser(name) : String.valueOf(rows.getFirst().get("user_id"));
    }

    /** A sponsor of the test's own (so that its wallet starts empty), onboarded the first time. */
    String newSponsor(String name) {
        String userId = user(name);
        if (latest("qb_sponsor_version", "onboarding_id", "user_id = '" + userId + "'").isEmpty()) {
            onboard(userId);
        }
        return userId;
    }

    /** A taker of the test's own, with a profile in {@code country} (none when null: they see GLOBAL only). */
    String newTaker(String name, String country) {
        String userId = user(name);
        if (country != null && profileOf(userId) == null) {
            profile(userId, country);
        }
        return userId;
    }

    String sponsorA() {
        return user("a-sponsor");
    }

    String sponsorB() {
        return user("b-sponsor");
    }

    String u1() {
        return user("u1-taker");
    }

    String u2() {
        return user("u2-taker");
    }

    String u3() {
        return user("u3-taker");
    }

    /**
     * Starts and submits once, one call after the other, before a test calls the same processes at once: the first
     * use of an input or output type builds its JSON (de)serializer under a lock, a wait BlockHound reports when calls
     * race for it in a fresh application (a test artefact: without BlockHound the others just wait).
     */
    void warmUp() {
        String sponsor = newSponsor("s-warm-up");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Warm-up", Map.of("regions", List.of("GLOBAL")));
        play(newTaker("t-warm-up-" + java.util.UUID.randomUUID(), null), publicationId, true, true);
        call("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", publicationId)).expectStatus()
            .isOk();
    }

    /** A taker's token, with the permissions of {@code QB_TAKER} and a verified address. */
    String taker(String userId) {
        return tokenOf(userId, QbRoles.TAKER);
    }

    /** A sponsor's token as {@code QB_SPONSOR} has it, so that a sponsor may also answer others' quizzes (G1). */
    String sponsorToken(String userId) {
        List<String> permissions = new ArrayList<>(QbRoles.of(QbRoles.SPONSOR).permissions());
        permissions.addAll(QbRoles.of(QbRoles.TAKER).permissions());
        return TestTokens.bearer(tokens, userId, permissions.stream().distinct().toArray(String[]::new));
    }

    void profile(String userId, String country) {
        run("QB_PROFILE_SAVE", TestTokens.bearer(tokens, userId, QbPermissions.ME), body("displayName",
            "Taker " + country, "countryCode", country, "preferredCurrency", "JPY"));
    }

    /** The system's token for its own processes (as the platform's jobs and events run them). */
    String system() {
        return TestTokens.bearer(tokens, "system-test", QbPermissions.PLAY_SYSTEM, QbPermissions.PUBLICATION_SYSTEM,
            QbPermissions.WALLET_DEV_CREDIT);
    }

    /**
     * Credits a sponsor's wallet (the development credit: a top-up without payment); the settlement of what is
     * pending follows by event, delivered here as the platform would.
     */
    Map<String, Object> credit(String sponsorId, long amount) {
        Map<String, Object> output = run("QB_WALLET_DEV_CREDIT", system(), body("sponsorId", sponsorId, "amount",
            amount));
        deliverEvents();
        return output;
    }

    /** Runs an {@code internal()} process as the system (D41), as its job or event would; returns its output. */
    @SuppressWarnings("unchecked")
    Object systemRun(String process, Map<String, ?> body) {
        var definition = (com.jabiz.process.ProcessDefinition<Object, Object, com.jabiz.process.ProcessContext>)
            processes.findLatest(process).orElseThrow();
        Object input = inputs.convert(definition.inputType(), (Map<String, Object>) body);
        return asRequest(com.jabiz.context.RequestContext.system(java.util.Locale.ENGLISH, "qb-it"),
            executor.execute(definition, input)).block();
    }

    /** Three tiers over a full score of 5: nothing below 3, 10 Kudos for 3–4, 100 for 5. */
    static List<Object> playTiers() {
        return tiers();
    }

    /**
     * A publication of the sponsor's, live now for a week; {@code overrides} replace fields of the save (regions,
     * budgetCap, maxWinners, tiers, startAt, endAt, timeLimitSec).
     */
    String livePublication(String sponsorId, String name, Map<String, Object> overrides) {
        String token = sponsorToken(sponsorId);
        String versionId = version(token, name + " quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.putAll(overrides);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        Map<String, Object> submitted = run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));
        assertThat(submitted.get("status")).as("submitted " + submitted).isIn("LIVE", "SCHEDULED");
        return publicationId;
    }

    String livePublication(String sponsorId, String name) {
        return livePublication(sponsorId, name, Map.of());
    }

    Map<String, Object> start(String token, String publicationId) {
        return run("QB_ATTEMPT_START", token, body("publicationId", publicationId));
    }

    /**
     * Answers given by their correctness: {@code right} questions (from the first) answered right, the rest wrong.
     * The quiz of {@link #version}: question 1 has options Paris (1, correct) and Lyon (2); question 2 Tokyo (1,
     * correct) and Osaka (2). Two right: 5 points; the first only: 2; the second only: 3.
     */
    static List<Object> answers(boolean first, boolean second) {
        return List.of(body("questionNo", 1, "selected", List.of(first ? 1 : 2)),
            body("questionNo", 2, "selected", List.of(second ? 1 : 2)));
    }

    Map<String, Object> submit(String token, String attemptId, List<Object> answers) {
        Map<String, Object> input = body("attemptId", attemptId);
        if (answers != null) {
            input.put("answers", answers);
        }
        return run("QB_ATTEMPT_SUBMIT", token, input);
    }

    /** Starts and submits in one go; returns the submission's output. */
    Map<String, Object> play(String userId, String publicationId, boolean first, boolean second) {
        String token = taker(userId);
        String attemptId = (String) start(token, publicationId).get("attemptId");
        return submit(token, attemptId, answers(first, second));
    }

    Map<String, Object> attempt(String attemptId) {
        return query("SELECT * FROM qb_attempt WHERE attempt_id = ?", attemptId).getFirst();
    }

    Map<String, Object> counter(String publicationId) {
        return query("SELECT * FROM qb_publication_counter WHERE publication_id = ?::uuid", publicationId).getFirst();
    }

    Map<String, Object> card(String publicationId) {
        return query("SELECT * FROM qb_publication_card WHERE publication_id = ?::uuid", publicationId).getFirst();
    }

    /** The sponsor's wallet row, or null. */
    Map<String, Object> wallet(String sponsorId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_wallet WHERE owner_id = ? AND kind = 'SPONSOR'",
            sponsorId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    BigDecimal balance(String sponsorId) {
        Map<String, Object> wallet = wallet(sponsorId);
        return wallet == null ? BigDecimal.ZERO : (BigDecimal) wallet.get("balance");
    }

    /** An account's balance in the ledger for a party (credit less debit; null party: every line). */
    static BigDecimal ledger(String account, String party) {
        return (BigDecimal) query("SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount "
            + "ELSE -e.amount END), 0) AS b FROM ledger_entry_version e JOIN ledger_account_version a "
            + "ON a.account_id = e.account_id WHERE a.account_code = ? AND (CAST(? AS text) IS NULL OR "
            + "e.dimension_1 = ?)", account, party, party).getFirst().get("b");
    }

    /** Every posted line's debit less credit, which balances to 0. */
    static BigDecimal ledgerTotal() {
        return (BigDecimal) query("SELECT coalesce(sum(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), "
            + "0) AS b FROM ledger_entry_version").getFirst().get("b");
    }

    /** The lines of the transaction posted for an attempt. */
    static List<Map<String, Object>> postingOf(String attemptId) {
        return query("SELECT t.transaction_id, t.booking_time, t.description, t.reference, t.source_entity, "
            + "t.source_id, a.account_code, e.direction, e.amount, e.dimension_1, e.dimension_2 "
            + "FROM ledger_transaction_version t JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbAttempt' AND t.source_id = ? ORDER BY e.line_no", attemptId);
    }

    /** Sets the controlled commission rate (four eyes). */
    void feeRate(String rate) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.CREATOR_FEE_RATE, "value", rate), "reason", "Commission"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** Runs the statistics job at {@code at}. */
    JobRunner.Outcome runStats(Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.run(statsJob, at);
    }

    /** Runs the expiry job at {@code at} as the scheduler would; its outcome and item counts. */
    JobRunner.JobRun runExpire(Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(expireJob, at);
    }

    /** A feed query as a taker of the country: its items. */
    List<Map<String, Object>> feed(String token, String country, Map<String, Object> more) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("at", clock.instant().toString());
        if (country != null) {
            params.put("country", country);
        }
        params.putAll(more);
        return items("qb.app.feed", token, Map.of("params", params, "limit", 200));
    }

    /**
     * Every sponsor wallet's balance equals the ledger's 2110 for them; every account adds up to 0; the ledger and the
     * temporal tables of content and publishing were only ever inserted into (D-Q5-4).
     */
    static void assertBooksBalance() {
        assertThat(ledgerTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        for (Map<String, Object> wallet : query("SELECT w.owner_id, w.balance, w.held, (SELECT coalesce(sum("
            + "t.dispute_held), 0) FROM qb_topup t WHERE t.sponsor_id = w.owner_id) AS holds FROM qb_wallet w "
            + "WHERE w.kind = 'SPONSOR'")) {
            // The balance (not less what is held: a hold is no ledger movement) is the ledger's.
            assertThat(ledger("2110", (String) wallet.get("owner_id"))).as("wallet of " + wallet.get("owner_id"))
                .isEqualByComparingTo((BigDecimal) wallet.get("balance"));
            assertThat((BigDecimal) wallet.get("held")).as("held of " + wallet.get("owner_id"))
                .isEqualByComparingTo((BigDecimal) wallet.get("holds"));
        }
        for (String table : LEDGER_TABLES) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
        }
    }
}

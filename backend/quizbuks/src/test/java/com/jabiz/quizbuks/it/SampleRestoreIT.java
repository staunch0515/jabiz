package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.it.PackageFixtures.Sample;
import com.jabiz.quizbuks.it.PackageFixtures.Step;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.sponsorpackage.PackageImportProcesses;
import com.jabiz.runtime.storage.WriteBatching;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-F11a (docs/quizbuks/plans/impl/F-F11.md section 6.1): the five sample Sponsors come back from their packages after a
 * reset. Their packages (the shape of {@link PackageFixtures#sample}: 15 quizzes of which one has 1000 questions of
 * six options in three languages in two versions and the current content, 30 publications of which one is bound to
 * a node of the Sponsor's own graph, two have a link to YouTube in their quiz and four give a digital prize) are
 * imported with the real {@code QB_PACKAGE_IMPORT_START} / {@code _STEP}, the steps in the order and under the keys the
 * page plans them, batches of 200 questions and 3 MiB; a stop half-way is gone on from. The times are printed for the
 * report (§10 of the plan); the assertions on them are wide, so that a slow machine does not fail the build.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class SampleRestoreIT extends TreeItSupport {

    private static final int BIG_BANK = 1000;
    private static final int PICK = 20;
    private static final int BATCH_SIZE = 200;
    private static final int BATCH_BYTES = 3_145_728;
    /** Far above what any machine needs (a minute or two in the plan's measure): fails only for a hang. */
    private static final Duration WIDE_LIMIT = Duration.ofMinutes(20);

    @Autowired
    WriteBatching batching;

    /** A bank of 1000 questions takes a step longer than the other tests' minute: the report wants its time, not a timeout. */
    @org.junit.jupiter.api.BeforeEach
    void patientClient() {
        client = client.mutate().responseTimeout(Duration.ofMinutes(15)).build();
    }

    private String methodsAdmin() {
        return TestTokens.bearer(tokens, "ff11-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    /** A sample Sponsor allowed the reward methods the samples use, with Kudos for their rewards. */
    private String sampleSponsor(String name) {
        String sponsor = newSponsor(unique(name));
        run(RewardMethodProcesses.SET, methodsAdmin(), body("userId", sponsor, "methods", List.of(
            RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM, RewardMethod.DIGITAL_PRIZE), "note", "F-F11 sample"));
        credit(sponsor, 1_000_000);
        return sponsor;
    }

    /** The calls of a Sponsor's import: each must succeed, and says which step it was when it does not. */
    private PackageFixtures.Calls calls(String sponsor) {
        return (process, body, label) -> {
            // A token for each call: a 1000-question bank takes minutes, longer than a token lives.
            var response = call(process, sponsorToken(sponsor), body).expectBody(MAP).returnResult();
            assertThat(response.getStatus().value()).as(label + " answered " + response.getResponseBody())
                .isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) response.getResponseBody().get("output");
            return output;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> doneOf(Map<String, Object> view) {
        return (Map<String, Map<String, Object>>) view.get("done");
    }

    private static List<String> stems(Object quizId) {
        return latest("qb_question_version", "question_id", "quiz_id = '" + quizId + "'").stream()
            .filter(row -> !Boolean.TRUE.equals(row.get("is_deleted")))
            .sorted(java.util.Comparator.comparing(row -> ((Number) row.get("seq")).intValue()))
            .map(row -> String.valueOf(row.get("stem"))).toList();
    }

    private static long versionsOf(Object quizId) {
        return ((Number) query("SELECT count(DISTINCT version_id) AS n FROM qb_quiz_version_version WHERE "
            + "quiz_id = ?::uuid", String.valueOf(quizId)).getFirst().get("n")).longValue();
    }

    /** The big bank's quiz id and the samples' sponsors, kept for the second test of the class's order. */
    private record Restored(String sponsor, String token, Sample sample, PackageFixtures.Imported imported) {}

    /**
     * The first sample, restored once for the class (its 1000-question bank is replaced three times, about a minute
     * each): the first test that needs it does it, with a stop half-way, and the others use what it made.
     */
    private static Restored first;

    private synchronized Restored firstSample() {
        if (first == null) {
            first = restore(0, true);
        }
        return first;
    }

    private Restored restore(int index, boolean stopHalfWay) {
        Sample sample = PackageFixtures.sample(index, BIG_BANK, PICK);
        String sponsor = sampleSponsor("s-ff11-sample-" + index);
        String token = sponsorToken(sponsor);
        // The page starts the publications now plus a day, as a restored sample is not live before it is reviewed.
        Instant startAt = clock.instant().plus(Duration.ofDays(1));
        List<Step> plan = PackageFixtures.plan(sample, BATCH_SIZE, startAt);
        PackageFixtures.Calls calls = calls(sponsor);
        String hash = String.format("%064x", 0xF11L * 1000 + index);
        Map<String, Object> started = PackageFixtures.start(calls, hash, plan.size(), BATCH_SIZE, BATCH_BYTES);
        assertThat(started).containsEntry("resumed", false).containsEntry("batchSize", BATCH_SIZE)
            .containsEntry("batchBytes", BATCH_BYTES);
        Object importId = started.get("importId");

        PackageFixtures.Imported imported;
        long millis = 0;
        if (stopHalfWay) {
            // A stop after a few steps (the first bank's first batches): going on does not make anything twice.
            int stop = Math.min(plan.size() - 1, 4);
            PackageFixtures.Imported first = PackageFixtures.runSteps(calls, plan, 0, stop, Map.of(), importId);
            millis += first.millis();
            Map<String, Object> resumed = PackageFixtures.start(calls, hash, plan.size(), BATCH_SIZE, BATCH_BYTES);
            assertThat(resumed).containsEntry("resumed", true).containsEntry("importId", importId)
                .containsEntry("stepsDone", stop).containsEntry("batchSize", BATCH_SIZE)
                .containsEntry("batchBytes", BATCH_BYTES);
            assertThat(doneOf(resumed)).containsOnlyKeys(first.results().keySet().toArray(String[]::new));
            // A step done before answers what it made and does nothing again.
            Step again = plan.get(stop - 1);
            Map<String, Object> input = new LinkedHashMap<>(again.input().apply(first.results()));
            input.put("importId", importId);
            input.put("step", again.key());
            assertThat(calls.run(PackageImportProcesses.STEP, input, "a step done before")).containsEntry("repeated",
                true);
            imported = PackageFixtures.runSteps(calls, plan, 0, plan.size(), doneOf(resumed), importId);
        } else {
            imported = PackageFixtures.runSteps(calls, plan, 0, plan.size(), Map.of(), importId);
        }
        imported = new PackageFixtures.Imported(importId, imported.results(), imported.millis() + millis, imported.slowestMillis(),
            imported.slowestStep());
        assertThat(query("SELECT status, steps_done FROM qb_package_import WHERE import_id = ?",
            String.valueOf(importId)).getFirst()).containsEntry("status", "DONE")
            .containsEntry("steps_done", new java.math.BigDecimal(plan.size()));
        return new Restored(sponsor, token, sample, imported);
    }

    private void assertRestored(Restored restored) {
        Sample sample = restored.sample();
        Map<String, Map<String, Object>> results = restored.imported().results();
        // The quizzes: as many as the package has, each with the current content's questions and its saved versions.
        for (PackageFixtures.SampleQuiz quiz : sample.quizzes()) {
            String firstStep = "quiz:" + quiz.key() + ":v:" + quiz.versions().keySet().iterator().next() + ":content";
            Object quizId = results.get(firstStep).get("quizId");
            List<String> stems = stems(quizId);
            assertThat(stems).as(quiz.key() + " questions").hasSize(quiz.questions());
            assertThat(stems).first().isEqualTo(((Map<?, ?>) ((List<?>) quiz.current().get("questions")).getFirst())
                .get("stem"));
            assertThat(versionsOf(quizId)).as(quiz.key() + " versions").isEqualTo(quiz.versions().size());
        }
        assertThat(results.keySet().stream().filter(k -> k.startsWith("quiz:") && k.endsWith(":content")).map(
            k -> results.get(k).get("quizId")).distinct()).hasSize(sample.quizzes().size());
        // The publications: drafts, each on a version of its quiz.
        List<String> publicationKeys = results.keySet().stream().filter(k -> k.startsWith("publication:")).toList();
        assertThat(publicationKeys).hasSize(sample.publications().size());
        for (String key : publicationKeys) {
            Map<String, Object> row = row(String.valueOf(results.get(key).get("publicationId")));
            assertThat(row).containsEntry("status", "DRAFT");
        }
        // The graph (the first sample only) and the publication bound to its node.
        if (sample.graph()) {
            String treeId = String.valueOf(results.get("tree:1").get("treeId"));
            assertThat(query("SELECT status FROM qb_tree WHERE tree_id = ?", treeId).getFirst())
                .containsEntry("status", "ACTIVE");
            assertThat(row(String.valueOf(results.get("publication:1").get("publicationId"))))
                .containsEntry("tree_id", treeId);
        }
    }

    @Test
    void theFiveSamplesComeBackFromTheirPackagesStepByStepAndAStopIsGoneOnFrom() {
        long total = 0;
        long steps = 0;
        List<Restored> all = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            Restored restored = index == 0 ? firstSample() : restore(index, false);
            assertRestored(restored);
            all.add(restored);
            total += restored.imported().millis();
            steps += restored.imported().results().size();
            System.out.printf("F-F11 sample %d (%s): %d steps in %d ms; slowest step %s %d ms%n", index + 1,
                restored.sample().company(), restored.imported().results().size(), restored.imported().millis(),
                restored.imported().slowestStep(), restored.imported().slowestMillis());
        }
        System.out.printf("F-F11 the five samples: %d steps in %d ms (15 quizzes, 30 publications; the big bank of %d "
            + "questions x %d options x 3 languages in 2 versions and the current content)%n", steps, total, BIG_BANK,
            PackageFixtures.OPTIONS);
        // 15 quizzes in all, 30 publications in all.
        assertThat(all.stream().mapToLong(r -> r.sample().quizzes().size()).sum()).isEqualTo(15);
        assertThat(all.stream().mapToLong(r -> r.sample().publications().size()).sum()).isEqualTo(30);
        assertThat(Duration.ofMillis(total)).isLessThan(WIDE_LIMIT);
    }

    @Test
    void theRestoredBigBankIsSavedPublishedAndEveryTakerIsDrawnTwentyOfThousand() {
        autoApprove(true);
        Restored restored = firstSample();
        String sponsor = restored.sponsor();
        // A fresh token: the restore took longer than one lives.
        String token = sponsorToken(sponsor);
        Object quizId = restored.imported().results().get("quiz:q1:v:v1:content").get("quizId");
        assertThat(stems(quizId)).hasSize(BIG_BANK);

        // The page's next step: the current content saved as a version, and a publication on it drawing 20.
        long started = System.nanoTime();
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        System.out.printf("F-F11 big bank: current content saved as a version in %d ms%n",
            Duration.ofNanos(System.nanoTime() - started).toMillis());
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Safety knowledge (sample)", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);
        save.put("pickCount", PICK);
        save.put("passScore", 10);
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 9, "reward", 0),
            body("fromScore", 10, "toScore", PICK, "reward", 10)));
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, save);
        assertThat(saved).containsEntry("fullScore", PICK).containsEntry("problems", List.of());
        String publicationId = (String) saved.get("publicationId");
        assertThat(submit(token, publicationId).get("status")).isIn("LIVE", "SCHEDULED");
        assertThat(sponsor).isNotBlank();

        // Three Takers, each drawn exactly 20 of the 1000 and not the same 20 (Q81: drawn once per session).
        List<List<?>> drawn = new ArrayList<>();
        // No profile, as the budget's takers: a country in the profile adds a read to the start.
        String warm = taker(newTaker(unique("t-ff11-warm"), null));
        start(warm, publicationId);
        for (int i = 0; i < 3; i++) {
            String taker = taker(newTaker(unique("t-ff11-draw"), null));
            com.jabiz.quizbuks.it.SqlStatementLog.clear();
            Map<String, Object> out = start(taker, publicationId);
            List<List<String>> start = com.jabiz.quizbuks.it.SqlStatementLog.transactions();
            List<?> questions = (List<?>) out.get("questions");
            assertThat(questions).hasSize(PICK);
            drawn.add(questions.stream().map(q -> ((Map<?, ?>) q).get("stem")).toList());
            // The start's statements are the budget's, whatever the size of the bank drawn from.
            List<String> own = start.stream().filter(t -> t.stream().anyMatch(sql -> sql.startsWith(
                "INSERT INTO op_process ("))).findFirst().orElseThrow();
            assertThat(own).as("QB_ATTEMPT_START of a draw of " + PICK + " from " + BIG_BANK).hasSize(
                batching.acrossTransaction() ? AttemptStatementBudgetIT.START_TOTAL.held()
                    : AttemptStatementBudgetIT.START_TOTAL.perCommit());
            assertThat(AttemptStatementBudgetIT.underLock(own)).hasSize(batching.acrossTransaction()
                ? AttemptStatementBudgetIT.START_LOCKED.held() : AttemptStatementBudgetIT.START_LOCKED.perCommit());
        }
        assertThat(drawn.stream().map(l -> l.stream().map(String::valueOf).collect(Collectors.toSet())).distinct())
            .as("the draws are not all the same twenty").hasSizeGreaterThan(1);
        assertThat(drawn.getFirst()).doesNotHaveDuplicates();
    }

    @Test
    void aSampleShapedPublicationEndsWithoutAnythingInTheBooks() {
        autoApprove(true);
        String sponsor = sampleSponsor("s-ff11-ends");
        String token = sponsorToken(sponsor);
        // A coffee at the counter, a digital prize and one bound to a node: rewards that pay nothing in Kudos.
        Map3 map = map(sponsor);
        List<String> ids = new ArrayList<>();
        Instant now = clock.instant();
        for (String kind : List.of("redeem", "digital", "node")) {
            String versionId = version(token, "Sample " + kind + " quiz");
            Map<String, Object> save = complete(versionId, "Sample " + kind, now, now.plus(Duration.ofDays(3)));
            save.put("regions", List.of("GLOBAL"));
            save.put("maxWinners", 50);
            save.put("passScore", 3);
            // A tier needs a reward above 0 (QB_TIER_NO_REWARD): the samples pay in kind, which costs no Kudos.
            save.put("tiers", List.of(body("fromScore", 0, "toScore", 2, "reward", 0),
                body("fromScore", 3, "toScore", 5, "reward", 10)));
            if (kind.equals("digital")) {
                save.put("rewardMethod", RewardMethod.DIGITAL_PRIZE);
                save.put("digitalDelivery", DigitalDelivery.SHARED);
                save.put("digitalDescription", "A month of Premium (sample)");
                save.put("digitalContent", "https://example.com/premium?code=SAMPLE-ONLY");
            } else {
                save.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
                save.put("redeemDescription", "A coffee at our counter (sample)");
            }
            if (kind.equals("node")) {
                save.put("treeId", map.treeId());
                save.put("treeNodeId", map.basics());
            }
            Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, save);
            assertThat((List<?>) saved.get("problems")).as(kind + " problems").isEmpty();
            String id = (String) saved.get("publicationId");
            assertThat(submit(token, id).get("status")).as(kind).isIn("LIVE", "SCHEDULED");
            ids.add(id);
        }
        long entries = count("ledger_entry_version");
        long transactions = count("ledger_transaction_version");
        long fees = count("qb_fee_charge_version") + count("qb_fee_payment_version");
        // Their time passes: the minute's job ends each by its end date.
        clock.advance(Duration.ofDays(4));
        runTick();
        deliverEvents();
        for (String id : ids) {
            assertThat(status(id)).as(id).isEqualTo("ENDED");
        }
        assertThat(count("ledger_entry_version")).as("ledger entries").isEqualTo(entries);
        assertThat(count("ledger_transaction_version")).as("ledger transactions").isEqualTo(transactions);
        assertThat(count("qb_fee_charge_version") + count("qb_fee_payment_version")).as("fees").isEqualTo(fees);
    }
}

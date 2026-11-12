package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.play.PlayLimits;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S6 (docs/quizbuks/plans/impl/F-S6.md): a bank of 1000 questions of six options in three languages is imported,
 * versioned (its snapshot within the limit), published drawing 100 per attempt, started and submitted; the times are
 * printed for the test report (docs/quizbuks/perf/F-S6-large-bank.md). A version larger than one attempt holds must be
 * drawn from.
 */
class LargeBankIT extends PlayItSupport {

    static final int QUESTIONS = 1000;
    static final int OPTIONS = PackageFixtures.OPTIONS;
    static final int DRAWN = 100;

    /** A quiz file of {@code count} one-point questions, base English, with Chinese and Japanese for every text. */
    static Map<String, Object> bank(int count) {
        return PackageFixtures.bank(count);
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static long since(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    @Test
    void aThousandQuestionBankIsVersionedPublishedAndAnsweredDrawingAHundred() {
        String sponsor = newSponsor("s-large-bank");
        credit(sponsor, 20_000);
        String token = sponsorToken(sponsor);

        long t = System.nanoTime();
        String quizId = (String) run("QB_QUIZ_IMPORT", token, body("document", bank(QUESTIONS))).get("quizId");
        long imported = since(t);
        t = System.nanoTime();
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        long versioned = since(t);
        int snapshotBytes = String.valueOf(query("SELECT content FROM qb_quiz_version_version WHERE version_id = "
            + "?::uuid", versionId).getFirst().get("content")).getBytes(StandardCharsets.UTF_8).length;
        assertThat(snapshotBytes).isLessThan(ContentLimits.MAX_SNAPSHOT_BYTES);

        // Asking all 1000 cannot be published: one attempt holds at most PlayLimits.MAX_PER_ATTEMPT_TECH.
        Instant now = clock.instant();
        Map<String, Object> all = complete(versionId, "Large bank, all", now, now.plus(Duration.ofDays(7)));
        all.put("regions", List.of("GLOBAL"));
        all.put("maxWinners", 1000);
        Map<String, Object> whole = run("QB_PUBLICATION_SAVE", token, all);
        assertThat(whole.get("problems").toString()).contains(PublishingCodes.PICK_REQUIRED);
        Map<String, Object> tooMany = new LinkedHashMap<>(all);
        tooMany.put("publicationId", whole.get("publicationId"));
        tooMany.put("pickCount", PlayLimits.MAX_PER_ATTEMPT_TECH + 1);
        // Over the technical ceiling the field itself refuses it (400; PICK_TOO_MANY answers a pick under the ceiling
        // but over the parameter, F-S7).
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, tooMany, 400)))
            .containsExactly(PublishingCodes.PICK_RANGE);

        Map<String, Object> save = complete(versionId, "Large bank", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);
        save.put("pickCount", DRAWN);
        save.put("passScore", 50);
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 49, "reward", 0),
            body("fromScore", 50, "toScore", DRAWN, "reward", 10)));
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, save);
        assertThat(saved).containsEntry("fullScore", DRAWN).containsEntry("problems", List.of());
        String publicationId = (String) saved.get("publicationId");
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isIn("LIVE", "SCHEDULED");

        String taker = taker(newTaker("t-large-bank", "JP"));
        t = System.nanoTime();
        Map<String, Object> started = start(taker, publicationId);
        long startMs = since(t);
        String attemptId = (String) started.get("attemptId");
        assertThat((List<?>) started.get("questions")).hasSize(DRAWN);
        t = System.nanoTime();
        start(taker, publicationId);
        long continueMs = since(t);

        List<Object> answers = new ArrayList<>();
        for (int q = 1; q <= DRAWN; q++) {
            answers.add(body("questionNo", q, "selected", List.of(position(attemptId, q, 1))));
        }
        t = System.nanoTime();
        Map<String, Object> result = run("QB_ATTEMPT_SUBMIT", taker, body("attemptId", attemptId, "publicationId",
            publicationId, "answers", answers));
        long submitMs = since(t);
        assertThat(result).containsEntry("score", DRAWN).containsEntry("fullScore", DRAWN);

        System.out.printf("F-S6 large bank: %d questions x %d options x 3 languages; snapshot %d bytes; import %d ms, "
            + "version %d ms, start %d ms, continue %d ms, submit %d ms%n", QUESTIONS, OPTIONS, snapshotBytes,
            imported, versioned, startMs, continueMs, submitMs);
    }

    /**
     * F-F11a (docs/quizbuks/plans/impl/F-F11.md section 6.1): the most a batch can hold (200 questions of a
     * 1000-character stem and six 300-character options, in three languages, every character of three bytes: about 5
     * MB) is refused by the framework before any process sees it, because the body passes
     * {@code spring.http.codecs.max-in-memory-size}; the same questions cut by {@code qb.content.import.batch-bytes} are
     * all imported, each batch within the technical ceiling and the limit.
     */
    @Test
    void theLargestBatchIsRefusedByTheFrameworkAndTheSameQuestionsCutByBytesAreImported() {
        String token = sponsorToken(newSponsor("s-large-bytes"));
        String quizId = (String) run("QB_QUIZ_IMPORT", token, body("document", PackageFixtures.bank("Worst bank", 1, OPTIONS, 9L)))
            .get("quizId");

        Map<String, Object> worst = PackageFixtures.worstBatch(200);
        int whole = PackageFixtures.bytesOf(worst);
        // The premise: the batch is within every limit of the question count and of the characters, and too big a body.
        assertThat(whole).isGreaterThan(4 * 1024 * 1024);
        var refused = call("QB_QUIZ_IMPORT_QUESTIONS", token, body("quizId", quizId, "document", worst)).expectBody(MAP)
            .returnResult();
        assertThat(refused.getStatus().value()).as("the whole batch of " + whole + " bytes: " + refused.getResponseBody())
            .isEqualTo(413);
        assertThat(query("SELECT count(*) AS n FROM qb_question_version WHERE quiz_id = ?::uuid", quizId).getFirst()
            .get("n")).isEqualTo(1L);

        // Cut by the parameter's value (3 MiB): several batches, each a request the framework takes.
        int limit = Integer.parseInt(com.jabiz.quizbuks.setup.QbParams.defaultOf(
            com.jabiz.quizbuks.setup.QbParams.CONTENT_IMPORT_BATCH_BYTES));
        List<Map<String, Object>> batches = PackageFixtures.splitByBytes(worst, 200, limit);
        assertThat(batches).hasSizeGreaterThan(1);
        assertThat(batches.stream().mapToInt(b -> ((List<?>) b.get("questions")).size()).sum()).isEqualTo(200);
        long imported = 0;
        for (Map<String, Object> batch : batches) {
            Map<String, Object> request = body("quizId", quizId, "document", batch);
            assertThat(PackageFixtures.bytesOf(request)).isLessThan(ContentLimits.IMPORT_BATCH_BYTES_TECH);
            imported += ((Number) run("QB_QUIZ_IMPORT_QUESTIONS", token, request).get("questions")).longValue();
        }
        assertThat(imported).isEqualTo(200);
        assertThat(query("SELECT count(DISTINCT question_id) AS n FROM qb_question_version WHERE quiz_id = ?::uuid",
            quizId).getFirst().get("n")).isEqualTo(201L);
        System.out.printf("F-F11 the largest batch: %d bytes refused (413); cut by %d bytes into %d batches of %s "
            + "questions%n", whole, limit, batches.size(),
            batches.stream().map(b -> String.valueOf(((List<?>) b.get("questions")).size())).toList());
    }
}

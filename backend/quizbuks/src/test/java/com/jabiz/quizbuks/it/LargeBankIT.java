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
    static final int OPTIONS = 6;
    static final int DRAWN = 100;

    /** A quiz file of {@code count} one-point questions, base English, with Chinese and Japanese for every text. */
    static Map<String, Object> bank(int count) {
        List<Object> questions = new ArrayList<>();
        Map<String, Object> zh = new LinkedHashMap<>();
        Map<String, Object> ja = new LinkedHashMap<>();
        zh.put("title", "大题库");
        ja.put("title", "大きな問題集");
        for (int q = 1; q <= count; q++) {
            List<Object> options = new ArrayList<>();
            for (int o = 1; o <= OPTIONS; o++) {
                options.add(map("text", "Option " + o + " of question " + q + ", a plausible answer", "correct",
                    o == 1));
                zh.put("q" + q + ".o" + o + ".text", "第 " + q + " 题的选项 " + o + "，一个看似合理的答案");
                ja.put("q" + q + ".o" + o + ".text", "問題 " + q + " の選択肢 " + o + "、もっともらしい答え");
            }
            questions.add(map("stem", "Question " + q + ": which of these statements about the subject is the "
                + "correct one, given what the material explains?", "points", 1, "options", options));
            zh.put("q" + q + ".stem", "第 " + q + " 题：根据资料的说明，关于这个主题，下列哪一种说法是正确的？");
            ja.put("q" + q + ".stem", "問題 " + q + "：資料の説明によると、このテーマについて正しいのは次のうちどれですか？");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "quizbuks.quiz");
        document.put("formatVersion", 3);
        document.put("quiz", map("kind", "QUIZ", "title", "Large bank", "timeLimitSec", 3600, "language", "en"));
        document.put("materials", List.of());
        document.put("questions", questions);
        document.put("files", Map.of());
        document.put("translations", map("zh", map("texts", zh, "images", Map.of(), "materials", List.of()),
            "ja", map("texts", ja, "images", Map.of(), "materials", List.of())));
        return document;
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
        assertThat(run("QB_PUBLICATION_SAVE", token, tooMany).get("problems").toString())
            .contains(PublishingCodes.PICK_TOO_MANY);

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
}

package com.jabiz.quizbuks.content;

import com.jabiz.approval.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizSnapshotsTest {

    static QuizSnapshot everyKind() {
        return new QuizSnapshot("All", null, null, null, true, List.of(
            new QuizSnapshot.Material(1, "ARTICLE", "A", null, "text", null, null, null, List.of()),
            new QuizSnapshot.Material(2, "LINK", "L", "d", null, "https://example.com/a?b=c", null, null, List.of()),
            new QuizSnapshot.Material(3, "VIDEO_LINK", "V", null, null, "https://video.example/1", null, null,
                List.of()),
            new QuizSnapshot.Material(4, "PDF", "P", null, null, null, Drafts.PDF, null, List.of()),
            new QuizSnapshot.Material(5, "IMAGES", "I", null, null, null, null, null, List.of(
                new QuizSnapshot.Image(1, Drafts.PICTURE, "first"), new QuizSnapshot.Image(2, Drafts.COVER, null))),
            new QuizSnapshot.Material(6, "AUDIO", "S", null, null, null, null, Drafts.AUDIO, List.of())),
            Drafts.complete().questions());
    }

    @Test
    void survivesTheRoundTripThroughText() {
        for (QuizSnapshot snapshot : List.of(Drafts.complete(), everyKind())) {
            String text = QuizSnapshots.toJson(snapshot);
            assertThat(QuizSnapshots.fromJson(text)).isEqualTo(snapshot);
            assertThat(QuizSnapshots.fromMap(QuizSnapshots.toMap(snapshot))).isEqualTo(snapshot);
            assertThat(QuizSnapshots.hashOfText(text)).isEqualTo(QuizSnapshots.hash(snapshot));
            assertThat(QuizSnapshots.toJson(QuizSnapshots.fromJson(text))).isEqualTo(text);
        }
    }

    /** Q19: only the video to watch says so; every other material's text, and so every earlier hash, is as before. */
    @Test
    void onlyTheVideoToWatchSaysSo() {
        QuizSnapshot.Material plain = new QuizSnapshot.Material(1, "VIDEO_LINK", "V", null, null,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null, null, List.of());
        QuizSnapshot.Material watched = new QuizSnapshot.Material(1, "VIDEO_LINK", "V", null, null,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null, null, List.of(), true);
        QuizSnapshot without = new QuizSnapshot("T", null, null, null, false, List.of(plain),
            Drafts.complete().questions());
        QuizSnapshot with = new QuizSnapshot("T", null, null, null, false, List.of(watched),
            Drafts.complete().questions());
        assertThat(QuizSnapshots.toJson(without)).doesNotContain("watchToAnswer");
        assertThat(QuizSnapshots.toJson(with)).contains("\"watchToAnswer\":true");
        assertThat(QuizSnapshots.fromJson(QuizSnapshots.toJson(with))).isEqualTo(with);
        assertThat(QuizSnapshots.hash(with)).isNotEqualTo(QuizSnapshots.hash(without));
    }

    /** Q75: the duration is written on the video to watch only, when the sponsor gave it; nothing else changes. */
    @Test
    void onlyTheVideoToWatchCarriesItsDuration() {
        QuizSnapshot.Material plain = new QuizSnapshot.Material(1, "VIDEO_LINK", "V", null, null,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null, null, List.of()).withDuration(120);
        QuizSnapshot.Material watched = new QuizSnapshot.Material(1, "VIDEO_LINK", "V", null, null,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", null, null, List.of(), true);
        QuizSnapshot.Material timed = watched.withDuration(120);
        List<QuizSnapshot.Question> questions = Drafts.complete().questions();
        // An ordinary video's duration is not written: its text and hash are as before Q75.
        assertThat(QuizSnapshots.toJson(new QuizSnapshot("T", null, null, null, false, List.of(plain), questions)))
            .doesNotContain("durationSec");
        assertThat(QuizSnapshots.toJson(new QuizSnapshot("T", null, null, null, false, List.of(watched), questions)))
            .doesNotContain("durationSec");
        QuizSnapshot with = new QuizSnapshot("T", null, null, null, false, List.of(timed), questions);
        String text = QuizSnapshots.toJson(with);
        assertThat(text).contains("\"durationSec\":120", "\"watchToAnswer\":true");
        assertThat(QuizSnapshots.fromJson(text)).isEqualTo(with);
        assertThat(QuizSnapshots.fromJson(text).materials().getFirst().durationSec()).isEqualTo(120);
    }

    @Test
    void writesSortedKeysWithoutSpaceAndEveryAbsentValueAsNull() {
        String text = QuizSnapshots.toJson(new QuizSnapshot("T", null, null, null, false, List.of(), List.of(
            Drafts.question(1, "Q", 1, Drafts.option(1, "x", true)))));
        assertThat(text).isEqualTo("{\"aiGenerated\":false,\"cover\":null,\"intro\":null,\"materials\":[],"
            + "\"questions\":[{\"image\":null,\"no\":1,\"options\":[{\"correct\":true,\"image\":null,\"no\":1,"
            + "\"text\":\"x\"}],\"points\":1,\"stem\":\"Q\"}],\"schema\":1,\"timeLimitSec\":null,\"title\":\"T\"}");
    }

    @Test
    void theHashDoesNotDependOnKeyOrderButOnEveryValue() {
        Map<String, Object> map = QuizSnapshots.toMap(Drafts.complete());
        Map<String, Object> reversed = new LinkedHashMap<>();
        List.copyOf(map.keySet()).reversed().forEach(key -> reversed.put(key, map.get(key)));
        assertThat(ContentHash.of(reversed)).isEqualTo(QuizSnapshots.hash(Drafts.complete()));
        assertThat(QuizSnapshots.hash(Drafts.complete())).hasSize(64);

        QuizSnapshot other = new QuizSnapshot("Capitals", "About *capitals*.", Drafts.COVER, 301, false,
            Drafts.complete().materials(), Drafts.complete().questions());
        assertThat(QuizSnapshots.hash(other)).isNotEqualTo(QuizSnapshots.hash(Drafts.complete()));
    }

    @Test
    void listsEachFileOnceWithItsPolicy() {
        assertThat(QuizSnapshots.files(everyKind())).containsExactly(
            Map.entry(Drafts.PDF, QuizSnapshots.PDF), Map.entry(Drafts.PICTURE, QuizSnapshots.IMAGE),
            Map.entry(Drafts.COVER, QuizSnapshots.IMAGE), Map.entry(Drafts.AUDIO, QuizSnapshots.AUDIO));
        assertThat(QuizSnapshots.files(Drafts.complete())).containsOnlyKeys(Drafts.COVER, Drafts.PICTURE);
    }

    @Test
    void addsUpTheFullScoreAndMeasuresTheText() {
        assertThat(Drafts.complete().fullScore()).isEqualTo(5);
        assertThat(QuizSnapshots.size("{\"t\":\"日本\"}")).isEqualTo(14);
    }

    @Test
    void refusesOtherSchemas() {
        assertThatThrownBy(() -> QuizSnapshots.fromJson("{\"schema\":3}"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSurveyNamesItsKindAndSelectionsWhileAQuizTextIsAsBefore() {
        QuizSnapshot survey = Drafts.survey();
        String text = QuizSnapshots.toJson(survey);
        assertThat(text).contains("\"kind\":\"SURVEY\"").contains("\"selection\":\"SINGLE\"")
            .contains("\"selection\":\"MULTIPLE\"");
        assertThat(QuizSnapshots.fromJson(text)).isEqualTo(survey);
        assertThat(QuizSnapshots.hashOfText(text)).isEqualTo(QuizSnapshots.hash(survey));
        assertThat(survey.kind()).isEqualTo(QbContent.KIND_SURVEY);
        // A scored quiz has neither key (its hash is unchanged by Q12); a missing kind reads as a quiz.
        String quiz = QuizSnapshots.toJson(Drafts.complete());
        assertThat(QuizSnapshots.toMap(Drafts.complete())).doesNotContainKey("kind");
        assertThat(quiz).doesNotContain("SURVEY").doesNotContain("selection");
        assertThat(QuizSnapshots.fromJson(quiz).survey()).isFalse();
        assertThat(QuizSnapshots.fromJson(quiz).kind()).isEqualTo(QbContent.KIND_QUIZ);
    }

    @Test
    void refusesAnUnknownKind() {
        Map<String, Object> map = QuizSnapshots.toMap(Drafts.survey());
        map.put("kind", "POLL");
        assertThatThrownBy(() -> QuizSnapshots.fromMap(map)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aScoredQuestionIsASingleChoiceByItsOneCorrectOption() {
        // D-Q5-2: one correct option makes a single choice (radio buttons), several a multiple choice; a survey's
        // question says which itself, and a question without a correct option says nothing.
        QuizSnapshot.Option right = new QuizSnapshot.Option(1, "Paris", null, true);
        QuizSnapshot.Option alsoRight = new QuizSnapshot.Option(2, "Lyon", null, true);
        QuizSnapshot.Option wrong = new QuizSnapshot.Option(3, "Rome", null, false);
        assertThat(new QuizSnapshot.Question(1, "Q", null, 2, List.of(right, wrong)).choice())
            .isEqualTo(QbContent.SINGLE);
        assertThat(new QuizSnapshot.Question(1, "Q", null, 2, List.of(right, alsoRight, wrong)).choice())
            .isEqualTo(QbContent.MULTIPLE);
        assertThat(new QuizSnapshot.Question(1, "Q", null, 2, List.of(wrong)).choice()).isNull();
        QuizSnapshot.Option a = new QuizSnapshot.Option(1, "A", null, false);
        QuizSnapshot.Option b = new QuizSnapshot.Option(2, "B", null, false);
        assertThat(new QuizSnapshot.Question(1, "Q", null, 1, List.of(a, b), QbContent.MULTIPLE).choice())
            .isEqualTo(QbContent.MULTIPLE);
        assertThat(new QuizSnapshot.Question(1, "Q", null, 1, List.of(a, b), QbContent.SINGLE).choice())
            .isEqualTo(QbContent.SINGLE);
    }
}

package com.jabiz.quizbuks.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuizSnapshotLanguagesTest {

    private static final UUID ZH_PICTURE = UUID.fromString("0190a000-0000-7000-8000-000000000011");
    private static final UUID ZH_PDF = UUID.fromString("0190a000-0000-7000-8000-000000000012");

    /** The complete quiz with a Chinese translation: all its text, an image and its article of its own. */
    static QuizSnapshot translated() {
        QuizSnapshot base = Drafts.complete();
        QuizSnapshot.Language zh = new QuizSnapshot.Language(Map.of(
            "title", "首都", "intro", "关于*首都*。", "q1.stem", "法国的首都？", "q1.o1.text", "巴黎",
            "q1.o2.text", "里昂", "q2.stem", "哪面旗？", "q2.o2.text", "都不是"),
            Map.of("q2.image", ZH_PICTURE),
            List.of(new QuizSnapshot.Material(1, "ARTICLE", "阅读", "先读", "巴黎是……", null, null, null, List.of())));
        return new QuizSnapshot(base.title(), base.intro(), base.cover(), base.timeLimitSec(), base.aiGenerated(),
            base.materials(), base.questions(), base.survey(), Map.of("zh", zh));
    }

    @Test
    void aSnapshotWithoutTranslationsIsAsBefore() {
        QuizSnapshot base = Drafts.complete();
        assertThat(QuizSnapshots.toMap(base)).containsEntry("schema", QuizSnapshot.SCHEMA)
            .doesNotContainKey("translations");
        assertThat(QuizSnapshots.toJson(base)).doesNotContain("translations");
    }

    @Test
    void translationsMakeTheNewSchemaAndSurviveTheRoundTrip() {
        QuizSnapshot snapshot = translated();
        String json = QuizSnapshots.toJson(snapshot);
        assertThat(QuizSnapshots.toMap(snapshot)).containsEntry("schema", QuizSnapshot.SCHEMA_LANGUAGES);
        QuizSnapshot back = QuizSnapshots.fromJson(json);
        assertThat(back).isEqualTo(snapshot);
        assertThat(QuizSnapshots.hashOfText(json)).isEqualTo(QuizSnapshots.hash(snapshot));
        assertThat(QuizSnapshots.hash(snapshot)).isNotEqualTo(QuizSnapshots.hash(Drafts.complete()));
    }

    @Test
    void readsTheQuizInALanguageWithTheBaseStructure() {
        QuizSnapshot zh = QuizSnapshots.in(translated(), "zh");
        assertThat(zh.title()).isEqualTo("首都");
        assertThat(zh.translations()).isEmpty();
        assertThat(zh.questions().get(0).stem()).isEqualTo("法国的首都？");
        assertThat(zh.questions().get(0).options()).extracting(QuizSnapshot.Option::text).containsExactly("巴黎", "里昂");
        assertThat(zh.questions().get(0).options()).extracting(QuizSnapshot.Option::correct)
            .containsExactly(true, false);
        assertThat(zh.questions().get(1).image()).isEqualTo(ZH_PICTURE);
        // An option the base has only an image for keeps the base's image.
        assertThat(zh.questions().get(1).options().get(0).image()).isEqualTo(Drafts.PICTURE);
        assertThat(zh.fullScore()).isEqualTo(Drafts.complete().fullScore());
        assertThat(zh.materials().get(0).title()).isEqualTo("阅读");
        assertThat(zh.materials().get(0).language()).isEqualTo("zh");
        // The base language, or any other the snapshot does not have, reads the base.
        QuizSnapshot ja = QuizSnapshots.in(translated(), "ja");
        assertThat(ja.title()).isEqualTo("Capitals");
        assertThat(ja.materials().get(0).language()).isNull();
    }

    @Test
    void aLanguageWithoutAMaterialOfItsOwnShowsTheBasesMaterial() {
        QuizSnapshot base = Drafts.complete();
        QuizSnapshot snapshot = new QuizSnapshot(base.title(), base.intro(), base.cover(), base.timeLimitSec(), false,
            base.materials(), base.questions(), false, Map.of("en", new QuizSnapshot.Language(Map.of("title", "T"),
                Map.of(), List.of())));
        QuizSnapshot en = QuizSnapshots.in(snapshot, "en");
        assertThat(en.materials().get(0)).isEqualTo(base.materials().get(0));
        assertThat(en.questions().get(0).stem()).isEqualTo(base.questions().get(0).stem());
    }

    @Test
    void listsTheLanguagesOwnFiles() {
        QuizSnapshot base = Drafts.complete();
        QuizSnapshot snapshot = new QuizSnapshot(base.title(), base.intro(), base.cover(), 300, false,
            base.materials(), base.questions(), false, Map.of("zh", new QuizSnapshot.Language(Map.of(),
                Map.of("q1.image", ZH_PICTURE), List.of(new QuizSnapshot.Material(1, "PDF", "资料", null, null, null,
                    ZH_PDF, null, List.of())))));
        assertThat(QuizSnapshots.files(snapshot)).containsEntry(ZH_PICTURE, QuizSnapshots.IMAGE)
            .containsEntry(ZH_PDF, QuizSnapshots.PDF).containsKey(Drafts.COVER);
    }
}

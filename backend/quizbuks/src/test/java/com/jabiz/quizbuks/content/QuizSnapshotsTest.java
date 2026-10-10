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
        assertThatThrownBy(() -> QuizSnapshots.fromJson("{\"schema\":2}"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

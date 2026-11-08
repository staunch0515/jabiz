package com.jabiz.quizbuks.topic;

import com.jabiz.entity.Violation;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-S9: the rules of topics, pure Java (TopicIT compares strict mode with the home's template). */
class TopicRulesTest {

    @Test
    void codesAreLowerCaseWordsJoinedByHyphens() {
        assertThat(TopicRules.validCode("cooking")).isTrue();
        assertThat(TopicRules.validCode("world-war-2")).isTrue();
        assertThat(TopicRules.validCode("c")).isFalse();
        assertThat(TopicRules.validCode("Cooking")).isFalse();
        assertThat(TopicRules.validCode("two--hyphens")).isFalse();
        assertThat(TopicRules.validCode("-edge")).isFalse();
        assertThat(TopicRules.validCode("with space")).isFalse();
        assertThat(TopicRules.validCode("a".repeat(41))).isFalse();
    }

    @Test
    void columnsKeepEachOnceInOrder() {
        assertThat(TopicRules.codes(" a, b,,a ,c")).containsExactly("a", "b", "c");
        assertThat(TopicRules.codes(null)).isEmpty();
        assertThat(TopicRules.joinCodes(List.of("b", "a", "b"))).isEqualTo("b,a");
        assertThat(TopicRules.joinCodes(List.of())).isNull();
        assertThat(TopicRules.customJson(List.of("  Edo   period ", "EDO PERIOD", "Ukiyo-e"))).isEqualTo(
            "[\"Edo period\",\"Ukiyo-e\"]");
        assertThat(TopicRules.custom("[\"Edo period\",\"Ukiyo-e\"]")).containsExactly("Edo period", "Ukiyo-e");
        assertThat(TopicRules.custom("not json")).isEmpty();
        assertThat(TopicRules.customJson(List.of(" "))).isNull();
    }

    @Test
    void aPublicationInheritsItsVersionsTopicsWithItsChanges() {
        TopicRules.Topics version = new TopicRules.Topics(List.of("history", "art"), List.of("Edo period", "Ukiyo-e"));
        TopicRules.Changes changes = new TopicRules.Changes(List.of("music", "history"), List.of("art", "absent"),
            List.of("Hokusai", "edo PERIOD"), List.of("ukiyo-E"));
        TopicRules.Changes kept = changes.against(version);
        assertThat(kept).isEqualTo(new TopicRules.Changes(List.of("music"), List.of("art"), List.of("Hokusai"),
            List.of("ukiyo-E")));
        TopicRules.Topics effective = TopicRules.effective(version, kept);
        assertThat(effective.codes()).containsExactly("history", "music");
        assertThat(effective.custom()).containsExactly("Edo period", "Hokusai");
        // The same changes on another version (a switch): what it lacks of the removals is simply not there.
        TopicRules.Topics other = new TopicRules.Topics(List.of("art", "science"), List.of());
        assertThat(TopicRules.effective(other, kept).codes()).containsExactly("science", "music");
        // No changes: the version's as they are; none kept as null.
        assertThat(TopicRules.effective(version, TopicRules.Changes.NONE)).isEqualTo(version);
        assertThat(TopicRules.Changes.NONE.json()).isNull();
        assertThat(TopicRules.Changes.of(kept.json())).isEqualTo(kept);
        assertThat(TopicRules.Changes.of("{broken")).isEqualTo(TopicRules.Changes.NONE);
    }

    @Test
    void strictModeComparesWordsOfTheListOnlyAsTheyStandNow() {
        Map<String, TopicRules.Word> words = new LinkedHashMap<>();
        words.put("travel", new TopicRules.Word("travel", true, null));
        words.put("hiking", new TopicRules.Word("hiking", false, "travel"));
        words.put("sport", new TopicRules.Word("sport", true, null));
        words.put("retired", new TopicRules.Word("retired", false, null));
        assertThat(TopicRules.strictAllows(false, List.of(), List.of(), words)).isTrue();
        assertThat(TopicRules.strictAllows(true, List.of("travel"), List.of("hiking"), words)).isTrue();
        assertThat(TopicRules.strictAllows(true, List.of("hiking"), List.of("travel"), words)).isTrue();
        assertThat(TopicRules.strictAllows(true, List.of("sport"), List.of("travel"), words)).isFalse();
        // A deactivated word still counts where it was chosen; a code not on the list never does.
        assertThat(TopicRules.strictAllows(true, List.of("retired"), List.of("retired"), words)).isTrue();
        assertThat(TopicRules.strictAllows(true, List.of("ghost"), List.of("ghost"), words)).isFalse();
        assertThat(TopicRules.strictAllows(true, List.of("sport"), List.of(), words)).isFalse();
        assertThat(TopicRules.strictAllows(true, List.of(), List.of("sport"), words)).isFalse();
    }

    @Test
    void aWordChosenAnewMustBeOfferedAndTheLimitsHold() {
        Map<String, TopicRules.Word> words = Map.of("on", new TopicRules.Word("on", true, null),
            "off", new TopicRules.Word("off", false, null), "merged", new TopicRules.Word("merged", false, "on"));
        assertThat(TopicRules.choiceProblems("topics", List.of("on", "off"), List.of("off"), words)).isEmpty();
        assertThat(TopicRules.choiceProblems("topics", List.of("off", "merged", "ghost"), List.of(), words))
            .extracting(Violation::ruleCode).containsOnly(TopicCodes.NOT_SELECTABLE).hasSize(3);
        assertThat(TopicRules.customProblems("customTopics", List.of("ok", " ", "x\u0007", "123456"), 5))
            .extracting(Violation::ruleCode).containsExactly(TopicCodes.CUSTOM_INVALID, TopicCodes.CUSTOM_INVALID,
                TopicCodes.CUSTOM_LENGTH);
        assertThat(TopicRules.countProblems("topics", new TopicRules.Topics(List.of("a", "b"), List.of("c")), 2))
            .extracting(Violation::ruleCode).containsExactly(TopicCodes.TOO_MANY);
        assertThat(TopicRules.maxPerQuiz(null)).isEqualTo(10);
        assertThat(TopicRules.maxPerQuiz(100)).isEqualTo(TopicRules.HARD_MAX);
        assertThat(TopicRules.maxPerQuiz(0)).isEqualTo(1);
        assertThat(TopicRules.customMaxLength(null)).isEqualTo(30);
        assertThat(TopicRules.customMaxLength(500)).isEqualTo(TopicRules.CUSTOM_HARD_MAX_LENGTH);
    }

    @Test
    void theStartupCheckNamesTheLanguagesAWordLacks() {
        Map<String, Map<String, Object>> names = new LinkedHashMap<>();
        names.put("whole", Map.of("zh", "全", "ja", "全", "en", "Whole"));
        names.put("half", Map.of("zh", "半", "en", " "));
        names.put("none", Map.of());
        List<CheckProblem> problems = TopicCheck.problems(names);
        assertThat(problems).extracting(CheckProblem::location).containsExactly("topic:half", "topic:none");
        assertThat(problems).allMatch(CheckProblem::isError);
        assertThat(problems.getFirst().message()).contains("ja, en");
        assertThat(problems.get(1).message()).contains("ja, zh, en");
    }
}

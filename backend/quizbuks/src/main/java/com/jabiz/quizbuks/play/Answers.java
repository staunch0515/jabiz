package com.jabiz.quizbuks.play;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QuizSnapshot;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An attempt's answers (docs/quizbuks/plans/Q5-answering.md, D-Q5-1): for each question, in the version's order, the
 * options chosen, sorted and each once; stored as canonical JSON ({@code [[1],[2,3],[]]}) on the attempt, like its
 * option order. A save or a submission gives the answers of some questions ({@link Given}), which replace theirs;
 * {@link #check} reports every question or option that is not the attempt's at once.
 *
 * <p>Options are named by their <em>position in this attempt</em>, 1..k in the order shown (Q10b, review M1), never by
 * their number in the version: the author's order says nothing to a taker, and numbers cannot be shared between
 * attempts that show the options in different orders. The attempt's option order ({@code order.get(i)}: the version's
 * numbers in the order shown) maps a position back only for scoring ({@link #inVersion}); it is never shown to the
 * taker (the field is sensitive).
 */
public final class Answers {

    /** The answer of one question as a caller gives it: the question's number and the options chosen. */
    public record Given(@NotNull Integer questionNo,
        @Size(max = ContentLimits.MAX_OPTIONS) List<@NotNull Integer> selected) {}

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private static final TypeReference<List<List<Integer>>> LISTS = new TypeReference<>() {};

    /** No answer yet to any of {@code questions} questions. */
    public static List<List<Integer>> none(int questions) {
        List<List<Integer>> answers = new ArrayList<>();
        for (int i = 0; i < questions; i++) {
            answers.add(List.of());
        }
        return List.copyOf(answers);
    }

    public static String toJson(List<List<Integer>> lists) {
        return JSON.writeValueAsString(lists);
    }

    public static List<List<Integer>> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, LISTS).stream().map(List::copyOf).toList();
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    /** Reads JSON written by {@link #json} back, with the same mapper. */
    static <T> T parse(String json, TypeReference<T> type) {
        return JSON.readValue(json, type);
    }

    /**
     * Every problem of {@code given} against the attempt's option order (question {@code i + 1} shows
     * {@code order.get(i).size()} options, at positions 1..k): a question that is not one, a question given twice, a
     * position that is not one of the question's, a position chosen twice; and a current question outside the quiz.
     * Each violation has the code {@link PlayCodes#ANSWER_INVALID} and names the field.
     */
    public static List<Violation> check(List<List<Integer>> order, List<Given> given, Integer currentIndex) {
        List<Violation> problems = new ArrayList<>();
        if (currentIndex != null && (currentIndex < 0 || currentIndex >= order.size())) {
            problems.add(invalid("currentIndex", null, null, "There is no question at index " + currentIndex));
        }
        if (given == null) {
            return List.copyOf(problems);
        }
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < given.size(); i++) {
            Given answer = given.get(i);
            String field = "answers[" + i + "]";
            if (answer == null || answer.questionNo() == null || answer.questionNo() < 1
                || answer.questionNo() > order.size()) {
                problems.add(invalid(field + ".questionNo", answer == null ? null : answer.questionNo(), null,
                    "There is no such question"));
                continue;
            }
            if (!seen.add(answer.questionNo())) {
                problems.add(invalid(field + ".questionNo", answer.questionNo(), null,
                    "Question " + answer.questionNo() + " is answered twice"));
                continue;
            }
            int shown = order.get(answer.questionNo() - 1).size();
            List<Integer> selected = answer.selected() == null ? List.of() : answer.selected();
            Set<Integer> chosen = new HashSet<>();
            for (int j = 0; j < selected.size(); j++) {
                Integer option = selected.get(j);
                if (option == null || option < 1 || option > shown || !chosen.add(option)) {
                    problems.add(invalid(field + ".selected[" + j + "]", answer.questionNo(), option,
                        "Question " + answer.questionNo() + " has no option " + option + " to choose"));
                }
            }
        }
        return List.copyOf(problems);
    }

    /**
     * What keeps a survey's answers from being submitted (Q12, D-Q12-5): a question not answered (its number by
     * position, as the taker sees it) and a single choice with more than one option, each reported once.
     *
     * @param answers per question, the positions chosen (as {@link #merge} gives them)
     */
    public static List<Violation> incomplete(QuizSnapshot snapshot, List<List<Integer>> answers) {
        List<Violation> problems = new ArrayList<>();
        List<Integer> unanswered = new ArrayList<>();
        List<QuizSnapshot.Question> questions = snapshot.questions();
        for (int i = 0; i < questions.size(); i++) {
            List<Integer> chosen = i < answers.size() ? answers.get(i) : List.of();
            QuizSnapshot.Question question = questions.get(i);
            if (chosen.isEmpty()) {
                unanswered.add(i + 1);
            } else if (!question.answeredBy(chosen)) {
                problems.add(new Violation("answers", PlayCodes.SINGLE_CHOICE, "Question " + (i + 1) + " takes one "
                    + "option, not " + chosen.size(), Map.of("questionNo", i + 1)));
            }
        }
        if (!unanswered.isEmpty()) {
            problems.addFirst(new Violation("answers", PlayCodes.INCOMPLETE, "Answer every question of the survey: "
                + unanswered + " are not answered", Map.of("questions", List.copyOf(unanswered),
                "count", unanswered.size())));
        }
        return List.copyOf(problems);
    }

    /** {@code current} with the given questions' answers replaced; call after {@link #check} found nothing. */
    public static List<List<Integer>> merge(List<List<Integer>> current, int questions, List<Given> given) {
        List<List<Integer>> answers = new ArrayList<>(current);
        while (answers.size() < questions) {
            answers.add(List.of());
        }
        if (given != null) {
            for (Given answer : given) {
                List<Integer> selected = answer.selected() == null ? List.of() : answer.selected();
                answers.set(answer.questionNo() - 1, List.copyOf(new TreeSet<>(selected)));
            }
        }
        return List.copyOf(answers);
    }

    /**
     * The answers in the version's numbering, for scoring: position {@code p} of question {@code i} is the option
     * {@code order.get(i).get(p - 1)}. Positions outside the question (never stored: {@link #check}) are dropped.
     */
    public static List<List<Integer>> inVersion(List<List<Integer>> order, List<List<Integer>> answers) {
        List<List<Integer>> mapped = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            List<Integer> shown = i < order.size() ? order.get(i) : List.of();
            TreeSet<Integer> numbers = new TreeSet<>();
            for (Integer position : answers.get(i)) {
                if (position != null && position >= 1 && position <= shown.size()) {
                    numbers.add(shown.get(position - 1));
                }
            }
            mapped.add(List.copyOf(numbers));
        }
        return List.copyOf(mapped);
    }

    /** The positions (1..k, sorted) at which the options numbered {@code numbers} in the version are shown. */
    public static List<Integer> positions(List<Integer> shown, List<Integer> numbers) {
        TreeSet<Integer> positions = new TreeSet<>();
        for (Integer number : numbers) {
            int index = shown.indexOf(number);
            if (index >= 0) {
                positions.add(index + 1);
            }
        }
        return List.copyOf(positions);
    }

    /** The answers as a caller reads them: one per question that has any. */
    public static List<Given> given(List<List<Integer>> answers) {
        List<Given> given = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            if (!answers.get(i).isEmpty()) {
                given.add(new Given(i + 1, answers.get(i)));
            }
        }
        return List.copyOf(given);
    }

    private static Violation invalid(String field, Integer questionNo, Integer option, String message) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("questionNo", questionNo == null ? "-" : questionNo);
        params.put("option", option == null ? "-" : option);
        return new Violation(field, PlayCodes.ANSWER_INVALID, message, params);
    }

    private Answers() {}
}

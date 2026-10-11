package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.content.QuizSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Scores an attempt (docs/quizbuks/plans/Q5-answering.md, D-Q5-2, default answer to Q8): a question earns its points
 * when the options chosen are exactly its correct ones (several correct options make it a multiple choice), else 0;
 * no partial credit; an unanswered question earns 0.
 */
public final class Scoring {

    /** One question's result: what was chosen, what is correct, its points and what it earned. */
    public record QuestionResult(int no, List<Integer> selected, List<Integer> correct, int points, int earned) {
        public QuestionResult {
            selected = List.copyOf(selected);
            correct = List.copyOf(correct);
        }
    }

    /** @param score the points earned; {@code fullScore} the sum of all questions' points */
    public record Result(int score, int fullScore, List<QuestionResult> questions) {
        public Result {
            questions = List.copyOf(questions);
        }
    }

    /** @param answers per question in the snapshot's order, the options chosen (fewer lists: the rest unanswered) */
    public static Result score(QuizSnapshot snapshot, List<List<Integer>> answers) {
        List<QuestionResult> results = new ArrayList<>();
        int score = 0;
        int full = 0;
        List<QuizSnapshot.Question> questions = snapshot.questions();
        for (int i = 0; i < questions.size(); i++) {
            QuizSnapshot.Question question = questions.get(i);
            List<Integer> selected = i < answers.size() ? List.copyOf(new TreeSet<>(answers.get(i))) : List.of();
            List<Integer> correct = question.options().stream().filter(QuizSnapshot.Option::correct)
                .map(QuizSnapshot.Option::no).sorted().toList();
            int earned = !correct.isEmpty() && selected.equals(correct) ? question.points() : 0;
            score += earned;
            full += question.points();
            results.add(new QuestionResult(question.no(), selected, correct, question.points(), earned));
        }
        return new Result(score, full, results);
    }

    /** The canonical JSON the attempt keeps as its result (written only when it is finished). */
    public static String toJson(Result result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QuestionResult question : result.questions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", question.no());
            row.put("selected", question.selected());
            row.put("correct", question.correct());
            row.put("points", question.points());
            row.put("earned", question.earned());
            rows.add(row);
        }
        return Answers.json(rows);
    }

    private Scoring() {}
}

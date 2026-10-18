package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.ContentLimits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The content rules a candidate question must meet (docs/quizbuks/plans/Q8-ai.md, D-Q8-4), the same as a version's
 * ({@code QuizCompleteness}) and the entities' lengths ({@code ContentLimits}): a text, 2 to 6 options each with text,
 * at least one correct, 1 to 1,000 points, no text longer than its column. Used three times: by the job's handler (so
 * that a malformed answer never fills the job's result), by the completion process (the result as stored is checked
 * again), and by {@code QB_AI_ACCEPT} on what the sponsor sends after reviewing.
 */
public final class AiCandidates {

    /** The points of a candidate the model gave none (A-12). */
    public static final int DEFAULT_POINTS = 10;

    /** The candidates kept, in order, and how many were dropped. */
    public record Checked(List<AiQuestionsJob.Candidate> valid, int invalid) {
        public Checked {
            valid = List.copyOf(valid);
        }
    }

    /**
     * Keeps the first {@code count} candidates that meet the rules, cleaned ({@link #clean}); the others, and any
     * beyond {@code count}, are counted as invalid.
     */
    public static Checked check(List<AiQuestionsJob.Candidate> raw, int count, Collection<Integer> materialNos) {
        List<AiQuestionsJob.Candidate> valid = new ArrayList<>();
        int invalid = 0;
        for (AiQuestionsJob.Candidate candidate : raw == null ? List.<AiQuestionsJob.Candidate>of() : raw) {
            if (candidate == null || valid.size() >= count) {
                invalid++;
                continue;
            }
            AiQuestionsJob.Candidate cleaned = clean(candidate, materialNos);
            if (problems(cleaned, "questions[" + valid.size() + "]", valid.size() + 1).isEmpty()) {
                valid.add(cleaned);
            } else {
                invalid++;
            }
        }
        return new Checked(valid, invalid);
    }

    /**
     * The candidate with its texts trimmed (empty text is none), {@value #DEFAULT_POINTS} points when it has none, and
     * only the material numbers that were sent ({@code materialNos}), once each, in order.
     */
    public static AiQuestionsJob.Candidate clean(AiQuestionsJob.Candidate candidate, Collection<Integer> materialNos) {
        List<AiQuestionsJob.Option> options = new ArrayList<>();
        for (AiQuestionsJob.Option option : candidate.options()) {
            if (option != null) {
                options.add(new AiQuestionsJob.Option(trimmed(option.text()), option.correct()));
            }
        }
        TreeSet<Integer> materials = new TreeSet<>();
        for (Integer no : candidate.materials()) {
            if (no != null && materialNos.contains(no)) {
                materials.add(no);
            }
        }
        return new AiQuestionsJob.Candidate(trimmed(candidate.stem()), options,
            candidate.points() == null ? DEFAULT_POINTS : candidate.points(), List.copyOf(materials));
    }

    /**
     * Every rule the question breaks, with the paths of its fields under {@code path} ({@code questions[2].stem}) and
     * the number people see ({@code questionNo}, from 1).
     */
    public static List<Violation> problems(AiQuestionsJob.Candidate candidate, String path, int questionNo) {
        List<Violation> problems = new ArrayList<>();
        String stem = candidate.stem();
        if (stem == null || stem.isBlank()) {
            problems.add(new Violation(path + ".stem", ContentCodes.STEM_REQUIRED, "Question " + questionNo
                + " has no text", Map.of("question", questionNo)));
        } else if (tooLong(stem, ContentLimits.STEM_LENGTH)) {
            problems.add(tooLongProblem(path + ".stem", ContentLimits.STEM_LENGTH));
        }
        int count = candidate.options().size();
        if (count < ContentLimits.MIN_OPTIONS || count > ContentLimits.MAX_OPTIONS) {
            problems.add(new Violation(path + ".options", ContentCodes.OPTION_COUNT, "Question " + questionNo
                + " has " + count + " options", Map.of("question", questionNo, "count", count,
                "min", ContentLimits.MIN_OPTIONS, "max", ContentLimits.MAX_OPTIONS)));
        }
        if (candidate.options().stream().noneMatch(AiQuestionsJob.Option::correct)) {
            problems.add(new Violation(path + ".options", ContentCodes.NO_CORRECT, "Question " + questionNo
                + " has no correct option", Map.of("question", questionNo)));
        }
        for (int i = 0; i < candidate.options().size(); i++) {
            String text = candidate.options().get(i).text();
            String at = path + ".options[" + i + "].text";
            if (text == null || text.isBlank()) {
                problems.add(new Violation(at, ContentCodes.OPTION_EMPTY, "Option " + (i + 1) + " of question "
                    + questionNo + " has no text", Map.of("question", questionNo, "option", i + 1)));
            } else if (tooLong(text, ContentLimits.OPTION_LENGTH)) {
                problems.add(tooLongProblem(at, ContentLimits.OPTION_LENGTH));
            }
        }
        Integer points = candidate.points();
        if (points == null || points < ContentLimits.MIN_POINTS || points > ContentLimits.MAX_POINTS) {
            problems.add(new Violation(path + ".points", ContentCodes.QUESTION_POINTS_RANGE, "A question is worth "
                + ContentLimits.MIN_POINTS + " to " + ContentLimits.MAX_POINTS + " points",
                Map.of("min", ContentLimits.MIN_POINTS, "max", ContentLimits.MAX_POINTS)));
        }
        return List.copyOf(problems);
    }

    private static boolean tooLong(String text, int max) {
        return text.codePointCount(0, text.length()) > max;
    }

    private static Violation tooLongProblem(String field, int max) {
        return new Violation(field, AiCodes.TEXT_TOO_LONG, "At most " + max + " characters", Map.of("max", max));
    }

    private static String trimmed(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        return value.isEmpty() ? null : value;
    }

    private AiCandidates() {}
}

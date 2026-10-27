package com.jabiz.quizbuks.audience;

import com.jabiz.entity.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registered criteria (docs/quizbuks/plans/Q39-audience.md, D-Q39-3) and the checks of what a publication sets
 * and what a taker tells. The four of Q39a ask nothing that Japan's APPI counts as requiring special care (health,
 * creed, record and the like); a new criterion must not either.
 */
public final class AudienceCriteria {

    public static final String GENDER = "gender";
    public static final String AGE = "age";
    public static final String OCCUPATION = "occupation";
    public static final String INTEREST = "interest";

    /** The youngest age a taker's birth year may give (the terms' minimum age). */
    public static final int MIN_AGE = 13;
    /** The earliest birth year a taker may give. */
    public static final int EARLIEST_BIRTH_YEAR = 1900;
    /** How many conditions (options and ranges together) one publication may set. */
    public static final int MAX_CONDITIONS = 100;

    /** In the order the pages show them. */
    public static final List<AudienceCriterion> ALL = List.of(
        AudienceCriterion.options(GENDER, false, "audience.gender", "FEMALE", "MALE", "OTHER", "NO_ANSWER"),
        // The profile keeps the birth year; a publication sets ages, read at the time a taker browses or starts.
        AudienceCriterion.range(AGE, MIN_AGE, 100, AudienceCriterion.Measure.YEARS_SINCE, "audience.age"),
        AudienceCriterion.options(OCCUPATION, false, "audience.occupation", "STUDENT", "EMPLOYEE", "CIVIL_SERVANT",
            "SELF_EMPLOYED", "PART_TIME", "HOMEMAKER", "RETIRED", "OTHER"),
        AudienceCriterion.options(INTEREST, true, "audience.interest", "FOOD", "TRAVEL", "TECHNOLOGY", "CULTURE",
            "SPORTS", "FASHION", "BEAUTY", "FINANCE", "PARENTING", "GAMES", "MUSIC", "BOOKS", "OUTDOORS", "PETS"));

    private static final Map<String, AudienceCriterion> BY_KEY;

    static {
        Map<String, AudienceCriterion> byKey = new LinkedHashMap<>();
        ALL.forEach(c -> {
            if (byKey.put(c.key(), c) != null) {
                throw new IllegalStateException("Two criteria named " + c.key());
            }
        });
        BY_KEY = Map.copyOf(byKey);
    }

    public static Optional<AudienceCriterion> find(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
    }

    /**
     * A publication's condition as the processes take it: options of a {@link AudienceCriterion.Kind#SET} criterion,
     * or the bounds of a {@link AudienceCriterion.Kind#RANGE} one.
     */
    public record ConditionInput(String key, List<String> options, Integer min, Integer max) {
        public ConditionInput {
            options = options == null ? null : List.copyOf(options);
        }
    }

    /** What a taker tells of one criterion: their options, or their number (a birth year). */
    public record AnswerInput(String key, List<String> options, Integer number) {
        public AnswerInput {
            options = options == null ? null : List.copyOf(options);
        }
    }

    /** Every problem of a publication's conditions at once, with the paths the page knows ({@code criteria[0].min}). */
    public static List<Violation> conditionProblems(List<ConditionInput> conditions) {
        List<Violation> problems = new ArrayList<>();
        if (conditions == null) {
            return problems;
        }
        Set<String> seen = new HashSet<>();
        int count = 0;
        for (int i = 0; i < conditions.size(); i++) {
            ConditionInput condition = conditions.get(i);
            String path = "criteria[" + i + "]";
            Optional<AudienceCriterion> criterion = find(condition == null ? null : condition.key());
            if (criterion.isEmpty()) {
                problems.add(violation(path + ".key", AudienceCodes.UNKNOWN, "No criterion of this name",
                    Map.of("key", String.valueOf(condition == null ? null : condition.key()))));
                continue;
            }
            if (!seen.add(criterion.get().key())) {
                problems.add(violation(path + ".key", AudienceCodes.REPEATED, "The criterion is set twice",
                    Map.of("key", criterion.get().key())));
                continue;
            }
            if (criterion.get().kind() == AudienceCriterion.Kind.SET) {
                List<String> options = condition.options() == null ? List.of() : condition.options();
                if (options.isEmpty() || condition.min() != null || condition.max() != null) {
                    problems.add(violation(path + ".options", AudienceCodes.OPTIONS_REQUIRED, "Choose at least one "
                        + "option", Map.of("key", criterion.get().key())));
                }
                for (String option : options) {
                    if (!criterion.get().options().contains(option)) {
                        problems.add(violation(path + ".options", AudienceCodes.OPTION, "Not an option of the "
                            + "criterion", Map.of("key", criterion.get().key(), "option", String.valueOf(option))));
                    }
                }
                count += new HashSet<>(options).size();
            } else {
                Integer min = condition.min();
                Integer max = condition.max();
                if (min == null || max == null || (condition.options() != null && !condition.options().isEmpty())) {
                    problems.add(violation(path + ".min", AudienceCodes.RANGE_REQUIRED, "Give both bounds",
                        Map.of("key", criterion.get().key())));
                } else if (min > max || min < criterion.get().min() || max > criterion.get().max()) {
                    problems.add(violation(path + ".min", AudienceCodes.RANGE, "The bounds are outside "
                        + criterion.get().min() + " to " + criterion.get().max() + " or reversed",
                        Map.of("key", criterion.get().key(), "min", criterion.get().min(),
                            "max", criterion.get().max())));
                }
                count++;
            }
        }
        if (count > MAX_CONDITIONS) {
            problems.add(violation("criteria", AudienceCodes.TOO_MANY, "More than " + MAX_CONDITIONS
                + " conditions", Map.of("max", MAX_CONDITIONS)));
        }
        return List.copyOf(problems);
    }

    /** Every problem of what a taker tells at once ({@code answers[0].number}); {@code year} bounds a birth year. */
    public static List<Violation> answerProblems(List<AnswerInput> answers, int year) {
        List<Violation> problems = new ArrayList<>();
        if (answers == null) {
            return problems;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < answers.size(); i++) {
            AnswerInput answer = answers.get(i);
            String path = "answers[" + i + "]";
            Optional<AudienceCriterion> criterion = find(answer == null ? null : answer.key());
            if (criterion.isEmpty()) {
                problems.add(violation(path + ".key", AudienceCodes.UNKNOWN, "No criterion of this name",
                    Map.of("key", String.valueOf(answer == null ? null : answer.key()))));
                continue;
            }
            if (!seen.add(criterion.get().key())) {
                problems.add(violation(path + ".key", AudienceCodes.REPEATED, "The criterion is told twice",
                    Map.of("key", criterion.get().key())));
                continue;
            }
            if (criterion.get().kind() == AudienceCriterion.Kind.SET) {
                List<String> options = answer.options() == null ? List.of() : answer.options();
                if (answer.number() != null || (!criterion.get().multiple() && options.size() > 1)) {
                    problems.add(violation(path + ".options", AudienceCodes.ONE_OPTION, "Choose one option",
                        Map.of("key", criterion.get().key())));
                }
                for (String option : options) {
                    if (!criterion.get().options().contains(option)) {
                        problems.add(violation(path + ".options", AudienceCodes.OPTION, "Not an option of the "
                            + "criterion", Map.of("key", criterion.get().key(), "option", String.valueOf(option))));
                    }
                }
            } else if (answer.number() != null) {
                int latest = year - MIN_AGE;
                if (answer.number() < EARLIEST_BIRTH_YEAR || answer.number() > latest
                    || (answer.options() != null && !answer.options().isEmpty())) {
                    problems.add(violation(path + ".number", AudienceCodes.BIRTH_YEAR, "A birth year from "
                        + EARLIEST_BIRTH_YEAR + " to " + latest, Map.of("min", EARLIEST_BIRTH_YEAR, "max", latest)));
                }
            }
        }
        return List.copyOf(problems);
    }

    private static Violation violation(String field, String code, String message, Map<String, Object> params) {
        return new Violation(field, code, message, params);
    }

    private AudienceCriteria() {}
}

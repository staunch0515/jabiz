package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Whether a draft is complete enough to become a version (M-31, the rules about content; docs/quizbuks/plans/
 * Q3-content.md, D-Q3-5). A draft may be incomplete while it is edited (M-22); a version may not. Every problem is
 * reported at once, with the path of the field ({@code questions[3].options}, indexes from 0) and the numbers people
 * see (question, option and material numbers from 1).
 */
public final class QuizCompleteness {

    public static List<Violation> check(QuizSnapshot draft) {
        List<Violation> problems = new ArrayList<>();
        if (draft.questions().isEmpty()) {
            problems.add(new Violation("questions", ContentCodes.NO_QUESTIONS, "A quiz needs at least one question",
                Map.of()));
        }
        for (int i = 0; i < draft.questions().size(); i++) {
            QuizSnapshot.Question question = draft.questions().get(i);
            String path = "questions[" + i + "]";
            if (blank(question.stem())) {
                problems.add(new Violation(path + ".stem", ContentCodes.STEM_REQUIRED,
                    "Question " + question.no() + " has no text", Map.of("question", question.no())));
            }
            int count = question.options().size();
            if (count < ContentLimits.MIN_OPTIONS || count > ContentLimits.MAX_OPTIONS) {
                problems.add(new Violation(path + ".options", ContentCodes.OPTION_COUNT, "Question " + question.no()
                    + " has " + count + " options, not " + ContentLimits.MIN_OPTIONS + " to "
                    + ContentLimits.MAX_OPTIONS, Map.of("question", question.no(), "count", count,
                    "min", ContentLimits.MIN_OPTIONS, "max", ContentLimits.MAX_OPTIONS)));
            }
            if (draft.survey()) {
                // A survey has no correct options (Q12, D-Q12-3), but each question says how it is answered.
                if (question.selection() == null) {
                    problems.add(new Violation(path + ".selection", ContentCodes.SELECTION_REQUIRED, "Question "
                        + question.no() + " is neither single nor multiple choice", Map.of("question", question.no())));
                }
                // The processes write a point each and no correct option; checked again here, so that neither a
                // version nor the submission's re-check takes a survey that scores (whatever wrote it).
                if (question.points() != 1 || question.options().stream().anyMatch(QuizSnapshot.Option::correct)) {
                    problems.add(new Violation(path, ContentCodes.SURVEY_SCORED, "Question " + question.no()
                        + " of a survey has points or a correct option", Map.of("question", question.no())));
                }
            } else if (question.options().stream().noneMatch(QuizSnapshot.Option::correct)) {
                problems.add(new Violation(path + ".options", ContentCodes.NO_CORRECT,
                    "Question " + question.no() + " has no correct option", Map.of("question", question.no())));
            }
            for (int j = 0; j < question.options().size(); j++) {
                QuizSnapshot.Option option = question.options().get(j);
                if (blank(option.text()) && option.image() == null) {
                    problems.add(new Violation(path + ".options[" + j + "]", ContentCodes.OPTION_EMPTY,
                        "Option " + option.no() + " of question " + question.no() + " has neither text nor image",
                        Map.of("question", question.no(), "option", option.no())));
                }
            }
        }
        for (int k = 0; k < draft.materials().size(); k++) {
            problems.addAll(material(draft.materials().get(k), "materials[" + k + "]"));
        }
        return List.copyOf(problems);
    }

    /**
     * What a material lacks to be in a version: a title, and the content of its kind. A material of one of the quiz's
     * other languages (Q73a) is checked the same; one that lacks something leaves its language out of the version.
     */
    public static List<Violation> material(QuizSnapshot.Material material, String path) {
        List<Violation> problems = new ArrayList<>();
        if (blank(material.title())) {
            problems.add(new Violation(path + ".title", ContentCodes.MATERIAL_TITLE_REQUIRED,
                "Material " + material.no() + " has no title", Map.of("material", material.no())));
        }
        MaterialKind kind = MaterialKind.of(material.kind()).orElseThrow(
            () -> new IllegalArgumentException("Unknown material kind " + material.kind()));
        if (kind == MaterialKind.IMAGES) {
            int images = material.images().size();
            if (images < 1 || images > ContentLimits.MAX_IMAGES) {
                problems.add(new Violation(path + ".images", ContentCodes.MATERIAL_NO_IMAGES, "Material "
                    + material.no() + " has " + images + " images, not 1 to " + ContentLimits.MAX_IMAGES,
                    Map.of("material", material.no(), "count", images, "max", ContentLimits.MAX_IMAGES)));
            }
        } else if (!hasContent(material, kind)) {
            problems.add(new Violation(path + "." + kind.contentField(), ContentCodes.MATERIAL_CONTENT_REQUIRED,
                "Material " + material.no() + " has no content of its kind " + kind,
                Map.of("material", material.no(), "kind", kind.name())));
        }
        return problems;
    }

    private static boolean hasContent(QuizSnapshot.Material material, MaterialKind kind) {
        return switch (kind) {
            case ARTICLE -> !blank(material.body());
            case LINK, VIDEO_LINK -> !blank(material.url());
            case PDF -> material.pdf() != null;
            case AUDIO -> material.audio() != null;
            case IMAGES -> !material.images().isEmpty();
        };
    }

    static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private QuizCompleteness() {}
}

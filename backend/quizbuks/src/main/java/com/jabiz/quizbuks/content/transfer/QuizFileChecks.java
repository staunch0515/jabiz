package com.jabiz.quizbuks.content.transfer;

import com.jabiz.entity.Violation;
import com.jabiz.file.MediaTypes;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.MaterialKind;
import com.jabiz.quizbuks.content.QbContent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What an import checks of a quiz file before anything is uploaded (docs/quizbuks/plans/Q16-quiz-transfer.md): the
 * content's limits as editing has them (counts, lengths, ranges, a material's one content field), and every file it
 * uses: listed in the file, of a type and size its field's policy takes. A draft may be incomplete (options without a
 * correct one, a survey question without its selection): saving a version checks that, as for a draft written by
 * hand. Every problem is reported at once, at its place in the file ({@code questions[2].options[0].text}).
 */
public final class QuizFileChecks {

    private static final Pattern URL = Pattern.compile(QbContent.URL_PATTERN);

    /** The policy of each kind of file field, and what it takes. */
    public enum FileField {
        IMAGE(QbContent.IMAGE_POLICY, ContentLimits.IMAGE_TYPES, ContentLimits.IMAGE_MAX_BYTES),
        PDF(QbContent.PDF_POLICY, ContentLimits.PDF_TYPES, ContentLimits.PDF_MAX_BYTES),
        AUDIO(QbContent.AUDIO_POLICY, ContentLimits.AUDIO_TYPES, ContentLimits.AUDIO_MAX_BYTES);

        private final String policy;
        private final List<MediaTypes> types;
        private final long maxBytes;

        FileField(String policy, List<MediaTypes> types, long maxBytes) {
            this.policy = policy;
            this.types = types;
            this.maxBytes = maxBytes;
        }

        public String policy() {
            return policy;
        }

        boolean takes(QuizFile.FileEntry file) {
            return file.size() >= 0 && file.size() <= maxBytes
                && types.stream().anyMatch(type -> type.contentType().equals(file.type()));
        }
    }

    /** A file the quiz uses: its key, where, and the kind of field. */
    public record Use(String key, String at, FileField field) {}

    private QuizFileChecks() {}

    /** Every place a file is used, in the file's order (a key used twice is listed twice). */
    public static List<Use> uses(QuizFile file) {
        List<Use> uses = new ArrayList<>();
        add(uses, file.cover(), "quiz.cover", FileField.IMAGE);
        for (int m = 0; m < file.materials().size(); m++) {
            QuizFile.Material material = file.materials().get(m);
            add(uses, material.pdf(), "materials[" + m + "].pdf", FileField.PDF);
            add(uses, material.audio(), "materials[" + m + "].audio", FileField.AUDIO);
            for (int i = 0; i < material.images().size(); i++) {
                add(uses, material.images().get(i).image(), "materials[" + m + "].images[" + i + "].image",
                    FileField.IMAGE);
            }
        }
        for (int q = 0; q < file.questions().size(); q++) {
            QuizFile.Question question = file.questions().get(q);
            add(uses, question.image(), "questions[" + q + "].image", FileField.IMAGE);
            for (int o = 0; o < question.options().size(); o++) {
                add(uses, question.options().get(o).image(), "questions[" + q + "].options[" + o + "].image",
                    FileField.IMAGE);
            }
        }
        return uses;
    }

    /** The keys of the files the quiz uses, each once, with the field kind of its first use. */
    public static Map<String, FileField> needed(QuizFile file) {
        Map<String, FileField> needed = new LinkedHashMap<>();
        for (Use use : uses(file)) {
            needed.putIfAbsent(use.key(), use.field());
        }
        return needed;
    }

    public static List<Violation> check(QuizFile file) {
        List<Violation> problems = new ArrayList<>();
        boolean survey = QbContent.KIND_SURVEY.equals(file.kind());
        if (file.title() == null || file.title().isBlank()) {
            problems.add(value("quiz.title", "a title is needed"));
        }
        length(problems, "quiz.title", file.title(), ContentLimits.TITLE_LENGTH);
        length(problems, "quiz.intro", file.intro(), ContentLimits.INTRO_LENGTH);
        Integer limit = file.timeLimitSec();
        if (limit != null && (limit < ContentLimits.MIN_TIME_LIMIT_SEC || limit > ContentLimits.MAX_TIME_LIMIT_SEC)) {
            problems.add(value("quiz.timeLimitSec", "between " + ContentLimits.MIN_TIME_LIMIT_SEC + " and "
                + ContentLimits.MAX_TIME_LIMIT_SEC + " seconds"));
        }
        count(problems, "materials", file.materials().size(), ContentLimits.MAX_MATERIALS);
        for (int m = 0; m < file.materials().size(); m++) {
            material(problems, "materials[" + m + "]", file.materials().get(m));
        }
        count(problems, "questions", file.questions().size(), ContentLimits.MAX_QUESTIONS);
        for (int q = 0; q < file.questions().size(); q++) {
            question(problems, "questions[" + q + "]", file.questions().get(q), survey);
        }
        for (Use use : uses(file)) {
            QuizFile.FileEntry entry = file.files().get(use.key());
            if (entry == null) {
                problems.add(new Violation(use.at(), ContentCodes.QUIZ_FILE_MISSING, "File " + use.key()
                    + " is not in the quiz file", Map.of("file", use.key())));
            } else if (!use.field().takes(entry)) {
                problems.add(new Violation(use.at(), ContentCodes.QUIZ_FILE_TYPE, "File " + use.key() + " ("
                    + entry.type() + ", " + entry.size() + " bytes) is not one its field takes",
                    Map.of("file", use.key(), "type", String.valueOf(entry.type()))));
            }
        }
        return List.copyOf(problems);
    }

    private static void material(List<Violation> problems, String at, QuizFile.Material material) {
        MaterialKind kind = MaterialKind.of(material.kind()).orElse(null);
        if (kind == null) {
            problems.add(value(at + ".kind", "not a kind of material"));
            return;
        }
        length(problems, at + ".title", material.title(), ContentLimits.MATERIAL_TITLE_LENGTH);
        length(problems, at + ".description", material.description(), ContentLimits.MATERIAL_DESCRIPTION_LENGTH);
        length(problems, at + ".body", material.body(), ContentLimits.MATERIAL_BODY_LENGTH);
        length(problems, at + ".url", material.url(), ContentLimits.URL_LENGTH);
        if (material.url() != null && !URL.matcher(material.url()).matches()) {
            problems.add(new Violation(at + ".url", ContentCodes.MATERIAL_URL_FORMAT, "Not an http(s) address"));
        }
        // Only the kind's own content field (as editing has it).
        Map<String, Boolean> given = Map.of("body", material.body() != null, "url", material.url() != null,
            "pdf", material.pdf() != null, "audio", material.audio() != null, "images", !material.images().isEmpty());
        for (String field : MaterialKind.CONTENT_FIELDS) {
            if (given.get(field) && !field.equals(kind.contentField())) {
                problems.add(new Violation(at + "." + field, ContentCodes.MATERIAL_FIELD_NOT_ALLOWED, "A "
                    + kind + " material has no " + field));
            }
        }
        count(problems, at + ".images", material.images().size(), ContentLimits.MAX_IMAGES);
        for (int i = 0; i < material.images().size(); i++) {
            QuizFile.Image image = material.images().get(i);
            if (image.image() == null) {
                problems.add(value(at + ".images[" + i + "].image", "an image is needed"));
            }
            length(problems, at + ".images[" + i + "].caption", image.caption(), ContentLimits.CAPTION_LENGTH);
        }
    }

    private static void question(List<Violation> problems, String at, QuizFile.Question question, boolean survey) {
        length(problems, at + ".stem", question.stem(), ContentLimits.STEM_LENGTH);
        Integer points = question.points();
        // A survey's question is worth 1 whatever the file says, as editing makes it (D-Q12-1).
        if (!survey && (points == null || points < ContentLimits.MIN_POINTS || points > ContentLimits.MAX_POINTS)) {
            problems.add(value(at + ".points", "between " + ContentLimits.MIN_POINTS + " and "
                + ContentLimits.MAX_POINTS));
        }
        String selection = question.selection();
        if (selection != null && (!survey || !QbContent.SINGLE.equals(selection)
            && !QbContent.MULTIPLE.equals(selection))) {
            problems.add(new Violation(at + ".selection", ContentCodes.SELECTION_NOT_ALLOWED, survey
                ? "SINGLE or MULTIPLE" : "A scored question has no selection"));
        }
        count(problems, at + ".options", question.options().size(), ContentLimits.MAX_OPTIONS);
        for (int o = 0; o < question.options().size(); o++) {
            QuizFile.Option option = question.options().get(o);
            length(problems, at + ".options[" + o + "].text", option.text(), ContentLimits.OPTION_LENGTH);
            if (survey && option.correct()) {
                problems.add(new Violation(at + ".options[" + o + "].correct", ContentCodes.SURVEY_SCORED,
                    "A survey's options have no correct one"));
            }
        }
    }

    private static void add(List<Use> uses, String key, String at, FileField field) {
        if (key != null) {
            uses.add(new Use(key, at, field));
        }
    }

    /** As the entities count: in characters (code points), not UTF-16 units. */
    private static void length(List<Violation> problems, String at, String text, int max) {
        if (text != null && text.codePointCount(0, text.length()) > max) {
            problems.add(value(at, "at most " + max + " characters"));
        }
    }

    private static void count(List<Violation> problems, String at, int count, int max) {
        if (count > max) {
            problems.add(new Violation(at, ContentCodes.QUIZ_FILE_TOO_MANY, count + " " + at + ", at most " + max,
                Map.of("field", at, "count", count, "max", max)));
        }
    }

    private static Violation value(String at, String what) {
        return new Violation(at, ContentCodes.QUIZ_FILE_VALUE, at + ": " + what, Map.of("field", at));
    }
}

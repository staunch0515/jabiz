package com.jabiz.quizbuks.content.transfer;

import com.jabiz.entity.Violation;
import com.jabiz.file.MediaTypes;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.MaterialKind;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.VideoEmbeds;

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
        // Q73a: each other language's own images and materials.
        file.translations().forEach((language, t) -> {
            String at = "translations." + language;
            t.images().forEach((key, image) -> add(uses, image, at + ".images." + key, FileField.IMAGE));
            for (int m = 0; m < t.materials().size(); m++) {
                QuizFile.Material material = t.materials().get(m).material();
                String materialAt = at + ".materials[" + m + "]";
                add(uses, material.pdf(), materialAt + ".pdf", FileField.PDF);
                add(uses, material.audio(), materialAt + ".audio", FileField.AUDIO);
                for (int i = 0; i < material.images().size(); i++) {
                    add(uses, material.images().get(i).image(), materialAt + ".images[" + i + "].image",
                        FileField.IMAGE);
                }
            }
        });
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
        if (file.materials().stream().filter(QuizFile.Material::watchToAnswer).count() > 1) {
            problems.add(new Violation("materials", ContentCodes.QUIZ_ONE_WATCH_VIDEO, "A quiz has one video to watch"));
        }
        count(problems, "questions", file.questions().size(), ContentLimits.MAX_QUESTIONS);
        for (int q = 0; q < file.questions().size(); q++) {
            question(problems, "questions[" + q + "]", file.questions().get(q), survey);
        }
        translations(problems, file);
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

    private static final Pattern TEXT_KEY = Pattern.compile("q(\\d{1,3})(?:\\.o(\\d{1,2}))?\\.(stem|text|image)");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{16}");

    /**
     * The other languages (Q73a), as editing takes them: platform languages other than the base (which a file with
     * translations names), each text a translation of a base text that exists and is not empty, within its field's
     * length; images of questions and options that exist; materials standing for base materials, of their kind,
     * one each, checked as a material is.
     */
    private static void translations(List<Violation> problems, QuizFile file) {
        if (file.language() != null && !QbContent.LANGUAGES.contains(file.language())) {
            problems.add(value("quiz.language", "one of " + QbContent.LANGUAGES));
        }
        if (file.translations().isEmpty()) {
            return;
        }
        if (file.language() == null) {
            problems.add(value("quiz.language", "a file with translations names its base language"));
        }
        count(problems, "translations", file.translations().size(), ContentLanguages.MAX_LANGUAGES - 1);
        file.translations().forEach((language, t) -> {
            String at = "translations." + language;
            if (!QbContent.LANGUAGES.contains(language) || language.equals(file.language())) {
                problems.add(value(at, "a platform language other than the base"));
                return;
            }
            t.texts().forEach((key, text) -> {
                String base = baseText(file, key);
                if (base == null) {
                    problems.add(value(at + ".texts." + key, "a translation of a text the quiz has"));
                } else {
                    length(problems, at + ".texts." + key, text, maxLength(key));
                }
            });
            t.hashes().forEach((key, hash) -> {
                if (!t.texts().containsKey(key) || !HASH.matcher(hash).matches()) {
                    problems.add(value(at + ".hashes." + key, "the hash of a translated text"));
                }
            });
            t.images().keySet().forEach(key -> {
                if (!hasImageSlot(file, key)) {
                    problems.add(value(at + ".images." + key, "an image of a question or option the quiz has"));
                }
            });
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int m = 0; m < t.materials().size(); m++) {
                QuizFile.NumberedMaterial own = t.materials().get(m);
                String materialAt = at + ".materials[" + m + "]";
                if (own.no() < 1 || own.no() > file.materials().size() || !seen.add(own.no())) {
                    problems.add(value(materialAt + ".no", "a base material's number, once"));
                    continue;
                }
                QuizFile.Material material = own.material();
                if (!String.valueOf(material.kind()).equals(file.materials().get(own.no() - 1).kind())) {
                    problems.add(new Violation(materialAt + ".kind", ContentCodes.MATERIAL_KIND_FIXED, "A material "
                        + "of another language is of its base's kind", Map.of("kind",
                        String.valueOf(file.materials().get(own.no() - 1).kind()))));
                    continue;
                }
                if (material.watchToAnswer()) {
                    problems.add(new Violation(materialAt + ".watchToAnswer", ContentCodes.MATERIAL_FIELD_NOT_ALLOWED,
                        "The video to watch is the base's choice"));
                }
                material(problems, materialAt, material);
            }
        });
    }

    /** The base text a translation's key names; null when the quiz has none there. */
    private static String baseText(QuizFile file, String key) {
        if ("title".equals(key)) {
            return file.title();
        }
        if ("intro".equals(key)) {
            return file.intro();
        }
        java.util.regex.Matcher m = TEXT_KEY.matcher(key);
        if (!m.matches() || "image".equals(m.group(3))) {
            return null;
        }
        QuizFile.Question question = question(file, Integer.parseInt(m.group(1)));
        if (question == null) {
            return null;
        }
        if (m.group(2) == null) {
            return "stem".equals(m.group(3)) ? question.stem() : null;
        }
        int option = Integer.parseInt(m.group(2));
        return "text".equals(m.group(3)) && option >= 1 && option <= question.options().size()
            ? question.options().get(option - 1).text() : null;
    }

    private static int maxLength(String key) {
        return switch (key) {
            case "title" -> ContentLimits.TITLE_LENGTH;
            case "intro" -> ContentLimits.INTRO_LENGTH;
            default -> key.endsWith(".stem") ? ContentLimits.STEM_LENGTH : ContentLimits.OPTION_LENGTH;
        };
    }

    private static boolean hasImageSlot(QuizFile file, String key) {
        java.util.regex.Matcher m = TEXT_KEY.matcher(key);
        if (!m.matches() || !"image".equals(m.group(3))) {
            return false;
        }
        QuizFile.Question question = question(file, Integer.parseInt(m.group(1)));
        return question != null && (m.group(2) == null
            || Integer.parseInt(m.group(2)) >= 1 && Integer.parseInt(m.group(2)) <= question.options().size());
    }

    private static QuizFile.Question question(QuizFile file, int no) {
        return no >= 1 && no <= file.questions().size() ? file.questions().get(no - 1) : null;
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
        // The video to watch, as editing has it (Q19).
        if (material.watchToAnswer() && kind != MaterialKind.VIDEO_LINK) {
            problems.add(new Violation(at + ".watchToAnswer", ContentCodes.MATERIAL_FIELD_NOT_ALLOWED, "A "
                + kind + " material has no watchToAnswer"));
        } else if (material.watchToAnswer() && VideoEmbeds.player(material.url()).isEmpty()) {
            problems.add(new Violation(at + ".url", ContentCodes.MATERIAL_VIDEO_NOT_EMBEDDABLE,
                "Only a YouTube or Vimeo video can be watched in the App"));
        }
        // Q75: the duration, as editing has it: a video's, within its range.
        if (material.durationSec() != null && kind != MaterialKind.VIDEO_LINK) {
            problems.add(new Violation(at + ".durationSec", ContentCodes.MATERIAL_FIELD_NOT_ALLOWED, "A "
                + kind + " material has no durationSec"));
        } else if (material.durationSec() != null && (material.durationSec() < ContentLimits.MIN_VIDEO_SECONDS
            || material.durationSec() > ContentLimits.MAX_VIDEO_SECONDS)) {
            problems.add(new Violation(at + ".durationSec", ContentCodes.MATERIAL_DURATION_RANGE, "A video lasts "
                + ContentLimits.MIN_VIDEO_SECONDS + " to " + ContentLimits.MAX_VIDEO_SECONDS + " seconds"));
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

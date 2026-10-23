package com.jabiz.quizbuks.content.transfer;

import com.jabiz.quizbuks.content.QbContent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The versions of the quiz file format and how each is read (docs/quizbuks/plans/Q16-quiz-transfer.md): a file names
 * its format ({@value #FORMAT}) and its {@code formatVersion}. A version this system reads is first brought up to
 * {@link #CURRENT} by the upgrades of each later version, one after the other, then read into a {@link QuizFile}. A
 * file of a newer version (made by a later QuizBuks) or older than {@link #OLDEST} is refused with the reason.
 *
 * <p>Changing the format: add the new version's number as {@code CURRENT}, put an upgrade from the previous version
 * into {@link #UPGRADES} (a plain change of the document's map), and change {@link #readCurrent} and the export
 * together. Raise {@code OLDEST} only to drop a version for good.
 */
public final class QuizFileFormat {

    public static final String FORMAT = "quizbuks.quiz";
    /** The version the export writes and {@link #readCurrent} reads. */
    public static final int CURRENT = 1;
    /** The oldest version still read. */
    public static final int OLDEST = 1;

    /** The upgrade from each version to the next, by the version it starts from. None yet: version 1 is current. */
    static final Map<Integer, UnaryOperator<Map<String, Object>>> UPGRADES = Map.of();

    /** Whether a file can be imported, and why not. */
    public enum Status { SUPPORTED, NOT_A_QUIZ_FILE, TOO_NEW, TOO_OLD, INVALID }

    /**
     * What reading a file found.
     *
     * @param version  the file's own format version; null when it names none
     * @param readers  the versions it went through on the way to the current one, its own first ({@code [1]} for a
     *                 current file); empty when it was not read
     * @param file     the quiz, when {@code SUPPORTED}
     * @param problem  why it was not read, otherwise null
     */
    public record Reading(Status status, Integer version, List<Integer> readers, QuizFile file, String problem) {
        public Reading {
            readers = List.copyOf(readers);
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    private QuizFileFormat() {}

    /** Reads a file's document (its JSON as maps and lists); never throws for what the file says. */
    public static Reading read(Map<String, ?> document) {
        return read(document, CURRENT, OLDEST, UPGRADES);
    }

    /** {@link #read(Map)} with the versions given: the tests try the upgrade chain before a second version exists. */
    static Reading read(Map<String, ?> document, int current, int oldest,
        Map<Integer, UnaryOperator<Map<String, Object>>> upgrades) {
        if (document == null || !FORMAT.equals(document.get("format"))) {
            return new Reading(Status.NOT_A_QUIZ_FILE, null, List.of(), null, "The file is not a QuizBuks quiz file");
        }
        if (!(document.get("formatVersion") instanceof Number n) || n.doubleValue() != n.intValue()) {
            return new Reading(Status.INVALID, null, List.of(), null, "The file names no whole formatVersion");
        }
        int version = n.intValue();
        if (version > current) {
            return new Reading(Status.TOO_NEW, version, List.of(), null, "Format version " + version
                + " is newer than this system reads (" + current + ")");
        }
        if (version < oldest) {
            return new Reading(Status.TOO_OLD, version, List.of(), null, "Format version " + version
                + " is older than this system still reads (" + oldest + ")");
        }
        Map<String, Object> upgraded = new LinkedHashMap<>(document);
        List<Integer> readers = new ArrayList<>(List.of(version));
        for (int v = version; v < current; v++) {
            UnaryOperator<Map<String, Object>> upgrade = upgrades.get(v);
            if (upgrade == null) {
                throw new IllegalStateException("No upgrade from quiz file version " + v);
            }
            upgraded = upgrade.apply(upgraded);
            readers.add(v + 1);
        }
        try {
            return new Reading(Status.SUPPORTED, version, readers, readCurrent(upgraded), null);
        } catch (IllegalArgumentException | ClassCastException malformed) {
            return new Reading(Status.INVALID, version, readers, null, malformed.getMessage());
        }
    }

    /** The current version's document as a quiz; a value of the wrong shape is {@link IllegalArgumentException}. */
    static QuizFile readCurrent(Map<String, ?> document) {
        Map<String, ?> quiz = map(document.get("quiz"), "quiz");
        String kind = text(quiz.get("kind"), "quiz.kind");
        if (!QbContent.KIND_QUIZ.equals(kind) && !QbContent.KIND_SURVEY.equals(kind)) {
            throw new IllegalArgumentException("quiz.kind must be QUIZ or SURVEY");
        }
        List<QuizFile.Material> materials = new ArrayList<>();
        int m = 0;
        for (Map<String, ?> material : maps(document.get("materials"), "materials")) {
            String at = "materials[" + m++ + "]";
            List<QuizFile.Image> images = new ArrayList<>();
            int i = 0;
            for (Map<String, ?> image : maps(material.get("images"), at + ".images")) {
                String imageAt = at + ".images[" + i++ + "]";
                images.add(new QuizFile.Image(text(image.get("image"), imageAt + ".image"),
                    text(image.get("caption"), imageAt + ".caption")));
            }
            materials.add(new QuizFile.Material(text(material.get("kind"), at + ".kind"),
                text(material.get("title"), at + ".title"), text(material.get("description"), at + ".description"),
                text(material.get("body"), at + ".body"), text(material.get("url"), at + ".url"),
                text(material.get("pdf"), at + ".pdf"), text(material.get("audio"), at + ".audio"), images));
        }
        List<QuizFile.Question> questions = new ArrayList<>();
        int q = 0;
        for (Map<String, ?> question : maps(document.get("questions"), "questions")) {
            String at = "questions[" + q++ + "]";
            List<QuizFile.Option> options = new ArrayList<>();
            int o = 0;
            for (Map<String, ?> option : maps(question.get("options"), at + ".options")) {
                String optionAt = at + ".options[" + o++ + "]";
                Object correct = option.get("correct");
                if (correct != null && !(correct instanceof Boolean)) {
                    throw new IllegalArgumentException(optionAt + ".correct must be true or false");
                }
                options.add(new QuizFile.Option(text(option.get("text"), optionAt + ".text"),
                    text(option.get("image"), optionAt + ".image"), Boolean.TRUE.equals(correct)));
            }
            questions.add(new QuizFile.Question(text(question.get("stem"), at + ".stem"),
                text(question.get("image"), at + ".image"), whole(question.get("points"), at + ".points"),
                text(question.get("selection"), at + ".selection"), options));
        }
        Map<String, QuizFile.FileEntry> files = new LinkedHashMap<>();
        Object listed = document.get("files");
        if (listed != null) {
            for (Map.Entry<String, ?> entry : map(listed, "files").entrySet()) {
                String at = "files." + entry.getKey();
                Map<String, ?> file = map(entry.getValue(), at);
                Integer size = whole(file.get("size"), at + ".size");
                files.put(entry.getKey(), new QuizFile.FileEntry(text(file.get("name"), at + ".name"),
                    text(file.get("type"), at + ".type"), size == null ? -1 : size));
            }
        }
        return new QuizFile(kind, text(quiz.get("title"), "quiz.title"), text(quiz.get("intro"), "quiz.intro"),
            text(quiz.get("cover"), "quiz.cover"), whole(quiz.get("timeLimitSec"), "quiz.timeLimitSec"), materials,
            questions, files);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> map(Object value, String at) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, ?>) map;
        }
        throw new IllegalArgumentException(at + " must be an object");
    }

    private static List<Map<String, ?>> maps(Object value, String at) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(at + " must be a list");
        }
        List<Map<String, ?>> maps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            maps.add(map(list.get(i), at + "[" + i + "]"));
        }
        return maps;
    }

    private static String text(Object value, String at) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException(at + " must be text");
    }

    private static Integer whole(Object value, String at) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n && n.doubleValue() == n.longValue()
            && Math.abs(n.longValue()) <= Integer.MAX_VALUE) {
            return n.intValue();
        }
        throw new IllegalArgumentException(at + " must be a whole number");
    }
}

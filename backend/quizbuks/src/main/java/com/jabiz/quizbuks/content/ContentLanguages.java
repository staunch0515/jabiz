package com.jabiz.quizbuks.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of a quiz's languages (Q73a, docs/quizbuks/plans/Q73-content-languages.md §1–§2): which elements of the
 * text are translated and under which key, the hash of the base text a translation was made from (a changed base
 * makes it out of date), and the lists of languages as their columns hold them. Pure Java, as the sponsor console's
 * {@code content/languages.ts} copies it (the hash too: {@code ContentLanguagesTest} and its tests share vectors).
 */
public final class ContentLanguages {

    /** The platform's languages ({@link QbContent#LANGUAGES}): a quiz's base and up to two others. */
    public static final int MAX_LANGUAGES = 3;

    /**
     * The most translation rows a quiz has: in each other language its title, introduction, and each question's text
     * and image and each option's text and image.
     */
    public static final int MAX_TRANSLATION_ROWS = (MAX_LANGUAGES - 1)
        * (2 + ContentLimits.MAX_QUESTIONS * (2 + 2 * ContentLimits.MAX_OPTIONS));

    /** The length of a column listing languages ({@code ja,zh,en}). */
    public static final int LIST_LENGTH = 20;

    /** The length of an element's key ({@code option:<uuid>.image} is the longest). */
    public static final int KEY_LENGTH = 64;

    /** Who wrote a translation: the sponsor, or the sponsor accepting an AI's candidate (Q73d). */
    public static final String BY_SPONSOR = "SPONSOR";
    public static final String BY_AI = "AI";

    public static final String TITLE = "quiz.title";
    public static final String INTRO = "quiz.intro";

    /** What a key names: the quiz's title or introduction, or a question's or an option's text or own image. */
    public enum Part { TITLE, INTRO, STEM, QUESTION_IMAGE, OPTION_TEXT, OPTION_IMAGE }

    /** A parsed key: its part and, for a question's or an option's, the item's key. */
    public record Element(Part part, UUID item) {

        /** Whether the element is text (else a language's own image). */
        public boolean text() {
            return part != Part.QUESTION_IMAGE && part != Part.OPTION_IMAGE;
        }

        /** The most characters its text may have: the base field's. */
        public int maxLength() {
            return switch (part) {
                case TITLE -> ContentLimits.TITLE_LENGTH;
                case INTRO -> ContentLimits.INTRO_LENGTH;
                case STEM -> ContentLimits.STEM_LENGTH;
                case OPTION_TEXT -> ContentLimits.OPTION_LENGTH;
                case QUESTION_IMAGE, OPTION_IMAGE -> 0;
            };
        }
    }

    private static final Pattern ITEM = Pattern.compile(
        "(question|option):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(stem|text|image)");

    public static String stem(Object questionId) {
        return "question:" + questionId + ".stem";
    }

    public static String questionImage(Object questionId) {
        return "question:" + questionId + ".image";
    }

    public static String optionText(Object optionId) {
        return "option:" + optionId + ".text";
    }

    public static String optionImage(Object optionId) {
        return "option:" + optionId + ".image";
    }

    /** The keys of a question's own elements and of its options'. */
    public static List<String> keysOf(Object questionId, List<?> optionIds) {
        List<String> keys = new ArrayList<>(List.of(stem(questionId), questionImage(questionId)));
        for (Object optionId : optionIds) {
            keys.add(optionText(optionId));
            keys.add(optionImage(optionId));
        }
        return keys;
    }

    /** What the key names; empty for a key that is none of an element's. */
    public static Optional<Element> parse(String key) {
        if (TITLE.equals(key)) {
            return Optional.of(new Element(Part.TITLE, null));
        }
        if (INTRO.equals(key)) {
            return Optional.of(new Element(Part.INTRO, null));
        }
        Matcher m = key == null ? null : ITEM.matcher(key);
        if (m == null || !m.matches()) {
            return Optional.empty();
        }
        UUID item = UUID.fromString(m.group(2));
        boolean question = "question".equals(m.group(1));
        return switch (m.group(3)) {
            case "stem" -> question ? Optional.of(new Element(Part.STEM, item)) : Optional.empty();
            case "text" -> question ? Optional.empty() : Optional.of(new Element(Part.OPTION_TEXT, item));
            default -> Optional.of(new Element(question ? Part.QUESTION_IMAGE : Part.OPTION_IMAGE, item));
        };
    }

    /**
     * The hash of a base text a translation records: the first 16 hexadecimal characters of the SHA-256 of its NFC
     * form in UTF-8. Null for no text (nothing to translate).
     */
    public static String sourceHash(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Normalizer.normalize(text, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A column's languages, in their order; none for an empty column. */
    public static List<String> parseList(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        Set<String> languages = new LinkedHashSet<>();
        for (String language : column.split(",")) {
            if (!language.isBlank()) {
                languages.add(language.trim());
            }
        }
        return List.copyOf(languages);
    }

    /** Languages as a column holds them; null for none. */
    public static String joinList(List<String> languages) {
        return languages == null || languages.isEmpty() ? null : String.join(",", languages);
    }

    private ContentLanguages() {}
}

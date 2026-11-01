package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.publishing.PublicationLanguages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A sponsor's own short texts in other languages (Q73c, docs/quizbuks/plans/Q73-content-languages.md §4): a page's
 * headline, the onboarding's introduction, a product's name and description. Each translation records the hash of the
 * base text it was made from ({@link ContentLanguages#sourceHash}), stored as a publication's are
 * ({@code {language: {field: {text, hash}}}}, {@link PublicationLanguages#parse} / {@link PublicationLanguages#toJson}):
 * a save that leaves one out of date is refused, so that what readers see always translates the present text. Pure
 * Java.
 */
public final class TextTranslations {

    public static final String LANGUAGES_INVALID = "QB_TEXT_LANGUAGES_INVALID";
    public static final String MISSING = "QB_TEXT_MISSING";
    public static final String STALE = "QB_TEXT_STALE";
    public static final String TOO_LONG = "QB_TEXT_TOO_LONG";

    /**
     * The translations to store after a save: the given ones of {@code languages}, each keeping the hash it was made
     * from while its text is as stored (so a base changed meanwhile leaves it out of date), else the present base's;
     * blank texts, texts of a field with no base text and of other languages are dropped. No translations given
     * (null) keeps the stored ones of those languages as they are. A translation the sponsor {@code confirmed}
     * ({@code <language>.<field>}: still right for the present base text) takes the present base's hash.
     */
    public static Map<String, Map<String, PublicationLanguages.Text>> merge(Map<String, Map<String, String>> given,
        Map<String, Map<String, PublicationLanguages.Text>> stored, Map<String, String> sources,
        List<String> languages, java.util.Collection<String> confirmed) {
        Map<String, Map<String, PublicationLanguages.Text>> out = new LinkedHashMap<>();
        for (String language : languages) {
            Map<String, PublicationLanguages.Text> before = stored.getOrDefault(language, Map.of());
            if (given == null) {
                if (!before.isEmpty()) {
                    out.put(language, before);
                }
                continue;
            }
            Map<String, PublicationLanguages.Text> texts = new LinkedHashMap<>();
            given.getOrDefault(language, Map.of()).forEach((field, text) -> {
                String value = text == null || text.isBlank() ? null : text.strip();
                if (value == null || sources.get(field) == null) {
                    return;
                }
                PublicationLanguages.Text old = before.get(field);
                boolean kept = old != null && value.equals(old.text())
                    && (confirmed == null || !confirmed.contains(language + "." + field));
                texts.put(field, kept ? old
                    : new PublicationLanguages.Text(value, ContentLanguages.sourceHash(sources.get(field))));
            });
            if (!texts.isEmpty()) {
                out.put(language, texts);
            }
        }
        return out;
    }

    /**
     * What a save is refused for, every problem at once: a language that is not the platform's or is given twice, a
     * text longer than its field ({@code maxLengths}), and in each of the {@code translated} languages (those other
     * than the base) a translation made from another base text (out of date); when {@code required}, also a base text
     * not translated.
     */
    public static List<Violation> problems(List<String> languages, List<String> translated,
        Map<String, Map<String, String>> given,
        Map<String, Map<String, PublicationLanguages.Text>> merged, Map<String, String> sources,
        Map<String, Integer> maxLengths, boolean required) {
        List<Violation> problems = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String language : languages) {
            if (!QbContent.LANGUAGES.contains(language) || seen.contains(language)) {
                problems.add(new Violation("languages", LANGUAGES_INVALID, "Not a language to offer once: "
                    + language, Map.of("language", String.valueOf(language))));
            }
            seen.add(language);
        }
        if (given != null) {
            given.forEach((language, texts) -> texts.forEach((field, text) -> {
                Integer max = maxLengths.get(field);
                if (text != null && max != null && text.strip().length() > max) {
                    problems.add(new Violation("translations." + language + "." + field, TOO_LONG,
                        "The translation is " + text.strip().length() + " characters, more than " + max,
                        Map.of("length", text.strip().length(), "max", max)));
                }
            }));
        }
        for (String language : translated) {
            Map<String, PublicationLanguages.Text> texts = merged.getOrDefault(language, Map.of());
            sources.forEach((field, source) -> {
                if (source == null) {
                    return;
                }
                PublicationLanguages.Text text = texts.get(field);
                if (text == null) {
                    if (required) {
                        problems.add(new Violation("translations." + language + "." + field, MISSING,
                            "No " + field + " in " + language, Map.of("language", language, "field", field)));
                    }
                } else if (!ContentLanguages.sourceHash(source).equals(text.hash())) {
                    problems.add(new Violation("translations." + language + "." + field, STALE,
                        "The " + field + " in " + language + " translates an older text",
                        Map.of("language", language, "field", field)));
                }
            });
        }
        return problems;
    }

    private TextTranslations() {}
}

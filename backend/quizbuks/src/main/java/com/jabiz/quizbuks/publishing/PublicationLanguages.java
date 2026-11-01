package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.QbContent;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The languages a publication offers and its own texts in each (Q73b, docs/quizbuks/plans/Q73-content-languages.md
 * §3): the languages are some of its version's (the first is its main one: the card's {@code language}); its name and
 * descriptions are its base texts (the version's base language) and, in each other language offered, a translation
 * that records the hash of the base text it was made from, as a quiz's do ({@link ContentLanguages#sourceHash}).
 * Submitting needs every offered language's texts translated and up to date. Pure Java.
 */
public final class PublicationLanguages {

    /** The publication's texts that are translated, by their field. */
    public static final String NAME = "name";
    public static final String REDEEM_DESCRIPTION = "redeemDescription";
    public static final String DIGITAL_DESCRIPTION = "digitalDescription";
    public static final String REFERRAL_DESCRIPTION = "referralDescription";
    public static final List<String> FIELDS = List.of(NAME, REDEEM_DESCRIPTION, DIGITAL_DESCRIPTION,
        REFERRAL_DESCRIPTION);

    /** The length of the stored JSON of the translations. */
    public static final int JSON_LENGTH = 20_000;

    /** One translated text and the hash of the base text it was made from. */
    public record Text(String text, String hash) {}

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final TypeReference<Map<String, Map<String, Map<String, String>>>> STORED = new TypeReference<>() {};

    /** The offered languages a column holds; none (null) is the version's base only. */
    public static List<String> offered(Object column, Object base) {
        List<String> languages = ContentLanguages.parseList(column == null ? null : column.toString());
        return languages.isEmpty() && base != null ? List.of(base.toString()) : languages;
    }

    /** The languages of a version (its {@code languages}, else its base {@code language}). */
    public static List<String> ofVersion(Object languages, Object language) {
        return offered(languages, language);
    }

    /** The stored translations: by language and field. */
    public static Map<String, Map<String, Text>> parse(Object json) {
        Map<String, Map<String, Text>> out = new LinkedHashMap<>();
        if (json == null || json.toString().isBlank()) {
            return out;
        }
        JSON.readValue(json.toString(), STORED).forEach((language, fields) -> {
            Map<String, Text> texts = new LinkedHashMap<>();
            fields.forEach((field, text) -> texts.put(field, new Text(text.get("text"), text.get("hash"))));
            out.put(language, texts);
        });
        return out;
    }

    /** The translations as stored; null for none. */
    public static String toJson(Map<String, Map<String, Text>> translations) {
        if (translations == null || translations.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Map<String, String>>> out = new LinkedHashMap<>();
        translations.forEach((language, texts) -> {
            Map<String, Map<String, String>> fields = new LinkedHashMap<>();
            texts.forEach((field, text) -> {
                Map<String, String> t = new LinkedHashMap<>();
                t.put("text", text.text());
                t.put("hash", text.hash());
                fields.put(field, t);
            });
            out.put(language, fields);
        });
        return JSON.writeValueAsString(out);
    }

    /** The texts only, for the card: by language and field (a card is frozen, so has nothing to be out of date). */
    public static String cardJson(Object stored) {
        Map<String, Map<String, Text>> translations = parse(stored);
        if (translations.isEmpty()) {
            return null;
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        translations.forEach((language, texts) -> {
            Map<String, String> fields = new LinkedHashMap<>();
            texts.forEach((field, text) -> fields.put(field, text.text()));
            out.put(language, fields);
        });
        return JSON.writeValueAsString(out);
    }

    /** The card's own field of the version's title in each language (Q73b). */
    public static final String QUIZ_TITLE = "quizTitle";

    /**
     * The card's texts: the publication's in each offered language other than the base, with the version's title in
     * it ({@link #QUIZ_TITLE}); null when it offers none but the base.
     */
    public static String cardJson(Object stored, com.jabiz.quizbuks.content.QuizSnapshot snapshot,
        List<String> offered, String base) {
        Map<String, Map<String, Text>> translations = parse(stored);
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String language : offered) {
            if (language.equals(base)) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            translations.getOrDefault(language, Map.of()).forEach((field, text) -> fields.put(field, text.text()));
            var t = snapshot == null ? null : snapshot.translations().get(language);
            String title = t == null ? null : t.texts().get(com.jabiz.quizbuks.content.QuizSnapshot.TITLE_KEY);
            if (title != null) {
                fields.put(QUIZ_TITLE, title);
            }
            if (!fields.isEmpty()) {
                out.put(language, fields);
            }
        }
        return out.isEmpty() ? null : JSON.writeValueAsString(out);
    }

    /** The publication's base texts, by field (blank ones none). */
    public static Map<String, String> sources(String name, String redeemDescription, String digitalDescription,
        ReferralPolicy referral) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(NAME, blank(name));
        sources.put(REDEEM_DESCRIPTION, blank(redeemDescription));
        sources.put(DIGITAL_DESCRIPTION, blank(digitalDescription));
        sources.put(REFERRAL_DESCRIPTION, referral == null ? null : blank(referral.description()));
        return sources;
    }

    /**
     * The translations to store after a save: the given texts of the offered languages other than the base, each
     * keeping the hash it was made from while its text is as stored (so a changed base leaves it out of date), else
     * the present base's; texts of a field with no base text, and of languages not offered, are dropped.
     */
    public static Map<String, Map<String, Text>> merge(Map<String, Map<String, String>> given,
        Map<String, Map<String, Text>> stored, Map<String, String> sources, List<String> languages, String base) {
        return merge(given, stored, sources, languages, base, null);
    }

    /**
     * As {@link #merge(Map, Map, Map, List, String)}; a translation the sponsor {@code confirmed}
     * ({@code <language>.<field>}: still right for the present base text, Q73c) takes the present base text's hash.
     */
    public static Map<String, Map<String, Text>> merge(Map<String, Map<String, String>> given,
        Map<String, Map<String, Text>> stored, Map<String, String> sources, List<String> languages, String base,
        java.util.Collection<String> confirmed) {
        Map<String, Map<String, Text>> out = new LinkedHashMap<>();
        if (given == null) {
            return out;
        }
        for (String language : languages) {
            if (language.equals(base) || !given.containsKey(language)) {
                continue;
            }
            Map<String, Text> texts = new LinkedHashMap<>();
            Map<String, Text> before = stored.getOrDefault(language, Map.of());
            given.get(language).forEach((field, text) -> {
                String value = blank(text);
                if (value == null || !FIELDS.contains(field) || sources.get(field) == null) {
                    return;
                }
                Text old = before.get(field);
                boolean kept = old != null && value.equals(old.text())
                    && (confirmed == null || !confirmed.contains(language + "." + field));
                texts.put(field, kept ? old : new Text(value, ContentLanguages.sourceHash(sources.get(field))));
            });
            if (!texts.isEmpty()) {
                out.put(language, texts);
            }
        }
        return out;
    }

    /** What a save refuses at once: languages not the platform's, given twice, and texts longer than their field. */
    public static List<Violation> refusals(List<String> languages, Map<String, Map<String, String>> given) {
        List<Violation> problems = new ArrayList<>();
        if (languages != null) {
            List<String> seen = new ArrayList<>();
            for (String language : languages) {
                if (!QbContent.LANGUAGES.contains(language) || seen.contains(language)) {
                    problems.add(new Violation("languages", PublishingCodes.LANGUAGES_INVALID, "Not a language to "
                        + "offer once: " + language, Map.of("language", String.valueOf(language))));
                }
                seen.add(language);
            }
        }
        if (given != null) {
            given.forEach((language, texts) -> texts.forEach((field, text) -> {
                int max = NAME.equals(field) ? PublishingLimits.NAME_LENGTH : PublishingLimits.REDEEM_DESCRIPTION_LENGTH;
                if (text != null && text.length() > max) {
                    problems.add(new Violation("translations." + language + "." + field,
                        PublishingCodes.TEXT_TOO_LONG, "The translation is " + text.length() + " characters, more "
                        + "than " + max, Map.of("length", text.length(), "max", max)));
                }
            }));
        }
        return problems;
    }

    /**
     * What submitting is refused for (a problem of the draft): a language its version does not have, and in each
     * offered language other than the base, a base text not translated or translated from another base text.
     */
    public static List<Violation> problems(List<String> offered, Map<String, Map<String, Text>> translations,
        Map<String, String> sources, String base, List<String> versionLanguages) {
        List<Violation> problems = new ArrayList<>();
        for (String language : offered) {
            if (!versionLanguages.contains(language)) {
                problems.add(new Violation("languages", PublishingCodes.LANGUAGE_NOT_IN_VERSION, "The version has no "
                    + language, Map.of("language", language)));
                continue;
            }
            if (language.equals(base)) {
                continue;
            }
            Map<String, Text> texts = translations.getOrDefault(language, Map.of());
            sources.forEach((field, source) -> {
                if (source == null) {
                    return;
                }
                Text text = texts.get(field);
                if (text == null || text.text() == null) {
                    problems.add(new Violation("translations." + language + "." + field,
                        PublishingCodes.TEXT_MISSING, "No " + field + " in " + language,
                        Map.of("language", language, "field", field)));
                } else if (!ContentLanguages.sourceHash(source).equals(text.hash())) {
                    problems.add(new Violation("translations." + language + "." + field,
                        PublishingCodes.TEXT_STALE, "The " + field + " in " + language + " translates an older text",
                        Map.of("language", language, "field", field)));
                }
            });
        }
        return problems;
    }

    /**
     * A card's text in a language: the card's translation into it when it has one, else its base text (the field of
     * the card itself).
     */
    public static String cardText(com.jabiz.runtime.EntityInstance card, Object language, String field) {
        Object base = card.get(field);
        if (language != null && card.get("translations") != null) {
            Map<String, Map<String, String>> texts = JSON.readValue(card.get("translations").toString(),
                new TypeReference<Map<String, Map<String, String>>>() {});
            String text = texts.getOrDefault(language.toString(), Map.of()).get(field);
            if (text != null) {
                return text;
            }
        }
        return base == null ? null : base.toString();
    }

    /**
     * A publication's or a card's text for a reader of {@code language} (Q73b §3.5, the inbox's and the mail's
     * publication names): its translation into that language, else into its main language, else the base text (the
     * field itself). Reads both the card's texts and the publication's texts with their hashes.
     */
    public static String textIn(com.jabiz.runtime.EntityInstance publication, String language, String field) {
        Object base = publication.get(field);
        Object stored = publication.get("translations");
        if (stored != null && !stored.toString().isBlank()) {
            Map<String, Map<String, Object>> texts = JSON.readValue(stored.toString(),
                new TypeReference<Map<String, Map<String, Object>>>() {});
            List<String> offered = ContentLanguages.parseList(publication.get("languages") == null ? null
                : publication.get("languages").toString());
            Object main = offered.isEmpty() ? publication.get("language") : offered.getFirst();
            for (Object candidate : java.util.Arrays.asList(language, main)) {
                if (candidate == null) {
                    continue;
                }
                Object text = texts.getOrDefault(candidate.toString(), Map.of()).get(field);
                if (text instanceof Map<?, ?> withHash) {
                    text = withHash.get("text");
                }
                if (text != null && !text.toString().isBlank()) {
                    return text.toString();
                }
            }
        }
        return base == null ? null : base.toString();
    }

    /** A blank text is none. */
    static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private PublicationLanguages() {}
}

package com.jabiz.quizbuks.content.ai;

import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.QuizEditing;
import com.jabiz.runtime.EntityInstance;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an AI translation of a quiz sends and keeps (Q73d-1, docs/quizbuks/plans/Q73-content-languages.md §5): the
 * segments of the draft's text in reading order (the title, the introduction, then each question's text and its
 * options' texts), and the candidates as stored on the job until the sponsor reviews them. Only texts: an option's
 * correctness, the points and the images are not read here at all. Pure Java.
 */
public final class AiTranslations {

    /** The stored candidates' document's version. */
    public static final int SCHEMA = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One element of the draft with its base text, in reading order. */
    record Source(String key, String text, boolean markdown) {}

    /**
     * The segments to translate: every element with a base text, or (unless {@code all}) only those the language has
     * no translation of, or one made from another base text (out of date).
     */
    public static List<AiTranslateJob.Segment> segments(EntityInstance quiz, List<EntityInstance> questions,
        List<EntityInstance> options, List<EntityInstance> translations, boolean all) {
        Map<String, EntityInstance> existing = new LinkedHashMap<>();
        translations.forEach(row -> existing.put(row.get("elementKey"), row));
        List<AiTranslateJob.Segment> segments = new ArrayList<>();
        for (Source source : sources(quiz, questions, options)) {
            String hash = ContentLanguages.sourceHash(source.text());
            EntityInstance row = existing.get(source.key());
            Object text = row == null ? null : row.get("text");
            boolean done = text != null && !text.toString().isBlank() && hash.equals(row.get("sourceHash"));
            if (all || !done) {
                segments.add(new AiTranslateJob.Segment(segments.size() + 1, source.key(), hash, source.text(),
                    source.markdown()));
            }
        }
        return segments;
    }

    /** The draft's texts in reading order; blank ones are not texts to translate. */
    static List<Source> sources(EntityInstance quiz, List<EntityInstance> questions, List<EntityInstance> options) {
        List<Source> sources = new ArrayList<>();
        add(sources, ContentLanguages.TITLE, quiz.get("title"), false);
        add(sources, ContentLanguages.INTRO, quiz.get("intro"), true);
        for (EntityInstance question : QuizEditing.ordered(questions)) {
            add(sources, ContentLanguages.stem(question.id()), question.get("stem"), true);
            String id = String.valueOf(question.id());
            options.stream().filter(option -> id.equals(String.valueOf((Object) option.get("questionId"))))
                .sorted(Comparator.comparingLong(option -> QuizEditing.number(option.get("seq"))))
                .forEach(option -> add(sources, ContentLanguages.optionText(option.id()), option.get("text"), false));
        }
        return sources;
    }

    private static void add(List<Source> sources, String key, Object text, boolean markdown) {
        if (text != null && !text.toString().isBlank()) {
            sources.add(new Source(key, text.toString(), markdown));
        }
    }

    /** The candidates kept and how many of the answer's were not usable. */
    public record Checked(List<AiTranslateJob.Translated> valid, int invalid) {}

    /**
     * The candidates as the completion stores them, checked again (the handler's result is not the last word): an
     * element key, a text within its element's length, one per element.
     */
    public static Checked check(List<AiTranslateJob.Translated> candidates) {
        List<AiTranslateJob.Translated> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int invalid = 0;
        for (AiTranslateJob.Translated candidate : candidates) {
            var element = candidate.key() == null ? java.util.Optional.<ContentLanguages.Element>empty()
                : ContentLanguages.parse(candidate.key());
            String text = candidate.text() == null ? null : candidate.text().strip();
            if (element.isEmpty() || !element.get().text() || text == null || text.isEmpty()
                || text.length() > element.get().maxLength() || candidate.sourceHash() == null
                || !seen.add(candidate.key())) {
                invalid++;
                continue;
            }
            valid.add(new AiTranslateJob.Translated(valid.size() + 1, candidate.key(), candidate.sourceHash(), text));
        }
        return new Checked(List.copyOf(valid), invalid);
    }

    /** The candidates' document of the job, numbered 1..n as the sponsor accepts them. */
    public static String toJson(String language, List<AiTranslateJob.Translated> candidates) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema", SCHEMA);
        document.put("language", language);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiTranslateJob.Translated candidate : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("no", candidate.no());
            item.put("key", candidate.key());
            item.put("sourceHash", candidate.sourceHash());
            item.put("text", candidate.text());
            items.add(item);
        }
        document.put("translations", items);
        return JSON.writeValueAsString(document);
    }

    /** The candidates of a stored document, by their numbers. */
    @SuppressWarnings("unchecked")
    public static List<AiTranslateJob.Translated> fromJson(Object stored) {
        List<AiTranslateJob.Translated> out = new ArrayList<>();
        if (stored == null) {
            return out;
        }
        Map<String, Object> document = JSON.readValue(stored.toString(), Map.class);
        for (Map<String, Object> item : (List<Map<String, Object>>) document.get("translations")) {
            out.add(new AiTranslateJob.Translated(((Number) item.get("no")).intValue(), (String) item.get("key"),
                (String) item.get("sourceHash"), (String) item.get("text")));
        }
        return out;
    }

    private AiTranslations() {}
}

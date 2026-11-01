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
        return check(candidates, key -> {
            var element = ContentLanguages.parse(key);
            return element.isPresent() && element.get().text() ? element.get().maxLength() : null;
        });
    }

    /**
     * As {@link #check(List)}, the keys and their lengths by {@code maxLength} (null for a key that is not one): a
     * publication's fields' (Q73d-2).
     */
    public static Checked check(List<AiTranslateJob.Translated> candidates,
        java.util.function.Function<String, Integer> maxLength) {
        List<AiTranslateJob.Translated> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int invalid = 0;
        for (AiTranslateJob.Translated candidate : candidates) {
            Integer max = candidate.key() == null ? null : maxLength.apply(candidate.key());
            String text = candidate.text() == null ? null : candidate.text().strip();
            if (max == null || text == null || text.isEmpty() || text.length() > max || candidate.sourceHash() == null
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

    // ---- a reviewer's check (Q73d-2) ------------------------------------------------------------------------------

    /**
     * The pairs a reviewer's check compares: the version's texts in {@code language} (its title, introduction,
     * questions' and options' texts, by the snapshot's keys) and the publication's own translated texts
     * ({@code publication.<field>}), each with its base text. Only texts: not the correct answers or the points.
     */
    public static List<AiCheckJob.Pair> checkPairs(com.jabiz.quizbuks.content.QuizSnapshot snapshot, String language,
        EntityInstance publication) {
        List<AiCheckJob.Pair> pairs = new ArrayList<>();
        var translated = snapshot.translations().get(language);
        if (translated != null) {
            Map<String, String> texts = translated.texts();
            pair(pairs, com.jabiz.quizbuks.content.QuizSnapshot.TITLE_KEY, snapshot.title(), texts);
            pair(pairs, com.jabiz.quizbuks.content.QuizSnapshot.INTRO_KEY, snapshot.intro(), texts);
            for (var question : snapshot.questions()) {
                pair(pairs, com.jabiz.quizbuks.content.QuizSnapshot.stemKey(question.no()), question.stem(), texts);
                for (var option : question.options()) {
                    pair(pairs, com.jabiz.quizbuks.content.QuizSnapshot.optionTextKey(question.no(), option.no()),
                        option.text(), texts);
                }
            }
        }
        var stored = com.jabiz.quizbuks.publishing.PublicationLanguages.parse(publication.get("translations"))
            .getOrDefault(language, Map.of());
        Map<String, String> sources = com.jabiz.quizbuks.publishing.PublicationLanguages.sources(
            publication.get("name"), publication.get("redeemDescription"), publication.get("digitalDescription"),
            com.jabiz.quizbuks.publishing.ReferralPolicy.fromJson(publication.get("referral")));
        sources.forEach((field, source) -> {
            var text = stored.get(field);
            if (source != null && text != null && text.text() != null && !text.text().isBlank()) {
                pairs.add(new AiCheckJob.Pair(pairs.size() + 1, "publication." + field, source, text.text()));
            }
        });
        return pairs;
    }

    private static void pair(List<AiCheckJob.Pair> pairs, String key, String source, Map<String, String> texts) {
        String translation = texts.get(key);
        if (source != null && !source.isBlank() && translation != null && !translation.isBlank()) {
            pairs.add(new AiCheckJob.Pair(pairs.size() + 1, key, source, translation));
        }
    }

    /** The verdicts' document kept on a check job: each pair's key and verdict. */
    public static String checkJson(String language, List<AiCheckJob.Finding> findings) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema", SCHEMA);
        document.put("language", language);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiCheckJob.Finding finding : findings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("no", finding.no());
            item.put("key", finding.key());
            item.put("consistent", finding.consistent());
            item.put("note", finding.note());
            items.add(item);
        }
        document.put("verdicts", items);
        return JSON.writeValueAsString(document);
    }

    private AiTranslations() {}
}

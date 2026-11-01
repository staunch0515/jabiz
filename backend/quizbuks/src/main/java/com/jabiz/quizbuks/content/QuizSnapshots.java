package com.jabiz.quizbuks.content;

import com.jabiz.approval.ContentHash;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts a {@link QuizSnapshot} to the map and the canonical JSON text a version stores, and back
 * (docs/quizbuks/plans/Q3-content.md, D-Q3-2). The text has its keys sorted, no white space and every key written
 * (absent values as {@code null}), so one content has one text. The hash is the platform's content hash of the map
 * ({@link ContentHash}, the one approvals are bound to): it does not depend on key order, and the stored text hashes
 * to the same value once read back.
 */
public final class QuizSnapshots {

    /** The field of {@code QbVersionFile} that holds a file of each policy. */
    public static final String IMAGE = "image";
    public static final String PDF = "pdf";
    public static final String AUDIO = "audio";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    public static Map<String, Object> toMap(QuizSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        // Q73a: only a snapshot with translations is of the new schema; one without stays as it was, text and hash.
        map.put("schema", snapshot.translations().isEmpty() ? QuizSnapshot.SCHEMA : QuizSnapshot.SCHEMA_LANGUAGES);
        map.put("title", snapshot.title());
        map.put("intro", snapshot.intro());
        map.put("cover", text(snapshot.cover()));
        map.put("timeLimitSec", snapshot.timeLimitSec());
        map.put("aiGenerated", snapshot.aiGenerated());
        // Only a survey's snapshot names its kind and its questions' selection (Q12): a scored quiz's text and hash
        // stay what they were.
        if (snapshot.survey()) {
            map.put("kind", QbContent.KIND_SURVEY);
        }
        map.put("materials", materials(snapshot.materials()));
        List<Object> questions = new ArrayList<>();
        for (QuizSnapshot.Question question : snapshot.questions()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("no", question.no());
            q.put("stem", question.stem());
            q.put("image", text(question.image()));
            q.put("points", question.points());
            if (snapshot.survey()) {
                q.put("selection", question.selection());
            }
            List<Object> options = new ArrayList<>();
            for (QuizSnapshot.Option option : question.options()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("no", option.no());
                o.put("text", option.text());
                o.put("image", text(option.image()));
                o.put("correct", option.correct());
                options.add(o);
            }
            q.put("options", options);
            questions.add(q);
        }
        map.put("questions", questions);
        if (!snapshot.translations().isEmpty()) {
            Map<String, Object> translations = new LinkedHashMap<>();
            snapshot.translations().forEach((language, t) -> {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("texts", new LinkedHashMap<>(t.texts()));
                Map<String, Object> images = new LinkedHashMap<>();
                t.images().forEach((key, image) -> images.put(key, text(image)));
                l.put("images", images);
                l.put("materials", materials(t.materials()));
                translations.put(language, l);
            });
            map.put("translations", translations);
        }
        return map;
    }

    private static List<Object> materials(List<QuizSnapshot.Material> list) {
        List<Object> materials = new ArrayList<>();
        for (QuizSnapshot.Material material : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("no", material.no());
            m.put("kind", material.kind());
            m.put("title", material.title());
            m.put("description", material.description());
            m.put("body", material.body());
            m.put("url", material.url());
            m.put("pdf", text(material.pdf()));
            m.put("audio", text(material.audio()));
            List<Object> images = new ArrayList<>();
            for (QuizSnapshot.Image image : material.images()) {
                Map<String, Object> i = new LinkedHashMap<>();
                i.put("no", image.no());
                i.put("image", text(image.image()));
                i.put("caption", image.caption());
                images.add(i);
            }
            m.put("images", images);
            // Only the video to watch says so (Q19): every other material's text and hash stay what they were.
            if (material.watchToAnswer()) {
                m.put("watchToAnswer", true);
            }
            materials.add(m);
        }
        return materials;
    }

    private static List<QuizSnapshot.Material> materialsOf(Object value) {
        List<QuizSnapshot.Material> materials = new ArrayList<>();
        for (Map<String, ?> m : maps(value)) {
            List<QuizSnapshot.Image> images = new ArrayList<>();
            for (Map<String, ?> i : maps(m.get("images"))) {
                images.add(new QuizSnapshot.Image(number(i.get("no")), uuid(i.get("image")), string(i.get("caption"))));
            }
            materials.add(new QuizSnapshot.Material(number(m.get("no")), string(m.get("kind")),
                string(m.get("title")), string(m.get("description")), string(m.get("body")), string(m.get("url")),
                uuid(m.get("pdf")), uuid(m.get("audio")), images, Boolean.TRUE.equals(m.get("watchToAnswer"))));
        }
        return materials;
    }

    public static QuizSnapshot fromMap(Map<String, ?> map) {
        Object schema = map.get("schema");
        if (!(schema instanceof Number n)
            || n.intValue() != QuizSnapshot.SCHEMA && n.intValue() != QuizSnapshot.SCHEMA_LANGUAGES) {
            throw new IllegalArgumentException("Not a quiz snapshot of schema " + QuizSnapshot.SCHEMA + " or "
                + QuizSnapshot.SCHEMA_LANGUAGES + ": " + schema);
        }
        Object kind = map.get("kind");
        if (kind != null && !QbContent.KIND_SURVEY.equals(kind)) {
            throw new IllegalArgumentException("Not a kind of quiz: " + kind);
        }
        boolean survey = kind != null;
        List<QuizSnapshot.Material> materials = materialsOf(map.get("materials"));
        List<QuizSnapshot.Question> questions = new ArrayList<>();
        for (Map<String, ?> q : maps(map.get("questions"))) {
            List<QuizSnapshot.Option> options = new ArrayList<>();
            for (Map<String, ?> o : maps(q.get("options"))) {
                options.add(new QuizSnapshot.Option(number(o.get("no")), string(o.get("text")), uuid(o.get("image")),
                    Boolean.TRUE.equals(o.get("correct"))));
            }
            questions.add(new QuizSnapshot.Question(number(q.get("no")), string(q.get("stem")), uuid(q.get("image")),
                number(q.get("points")), options, survey ? string(q.get("selection")) : null));
        }
        Map<String, QuizSnapshot.Language> translations = new LinkedHashMap<>();
        if (map.get("translations") instanceof Map<?, ?> given) {
            given.forEach((language, value) -> {
                if (!(value instanceof Map<?, ?> l)) {
                    throw new IllegalArgumentException("Not a translation: " + value);
                }
                Map<String, String> texts = new LinkedHashMap<>();
                if (l.get("texts") instanceof Map<?, ?> t) {
                    t.forEach((key, text) -> texts.put(String.valueOf(key), string(text)));
                }
                Map<String, UUID> images = new LinkedHashMap<>();
                if (l.get("images") instanceof Map<?, ?> i) {
                    i.forEach((key, image) -> images.put(String.valueOf(key), uuid(image)));
                }
                translations.put(String.valueOf(language),
                    new QuizSnapshot.Language(texts, images, materialsOf(l.get("materials"))));
            });
        }
        Object limit = map.get("timeLimitSec");
        return new QuizSnapshot(string(map.get("title")), string(map.get("intro")), uuid(map.get("cover")),
            limit == null ? null : number(limit), Boolean.TRUE.equals(map.get("aiGenerated")), materials, questions,
            survey, translations);
    }

    /**
     * The quiz as a taker reads it in one of its languages (Q73a §2.5): the same structure, numbers and scoring, with
     * the text of that language, its own images where it has them, and its own materials where it has them (each
     * with its {@code language}; the base's materials have none). The base's when the language is none of the
     * snapshot's translations: the caller checks that it is the version's base. The result has no translations.
     */
    public static QuizSnapshot in(QuizSnapshot snapshot, String language) {
        QuizSnapshot.Language t = snapshot.translations().get(language);
        if (t == null) {
            return new QuizSnapshot(snapshot.title(), snapshot.intro(), snapshot.cover(), snapshot.timeLimitSec(),
                snapshot.aiGenerated(), snapshot.materials(), snapshot.questions(), snapshot.survey());
        }
        Map<Integer, QuizSnapshot.Material> own = new LinkedHashMap<>();
        t.materials().forEach(material -> own.put(material.no(), material));
        List<QuizSnapshot.Material> materials = new ArrayList<>();
        for (QuizSnapshot.Material base : snapshot.materials()) {
            QuizSnapshot.Material m = own.get(base.no());
            materials.add(m == null ? base : new QuizSnapshot.Material(base.no(), base.kind(), m.title(),
                m.description(), m.body(), m.url(), m.pdf(), m.audio(), m.images(), base.watchToAnswer(), language));
        }
        List<QuizSnapshot.Question> questions = new ArrayList<>();
        for (QuizSnapshot.Question q : snapshot.questions()) {
            List<QuizSnapshot.Option> options = new ArrayList<>();
            for (QuizSnapshot.Option o : q.options()) {
                options.add(new QuizSnapshot.Option(o.no(),
                    t.texts().getOrDefault(QuizSnapshot.optionTextKey(q.no(), o.no()), o.text()),
                    t.images().getOrDefault(QuizSnapshot.optionImageKey(q.no(), o.no()), o.image()), o.correct()));
            }
            questions.add(new QuizSnapshot.Question(q.no(),
                t.texts().getOrDefault(QuizSnapshot.stemKey(q.no()), q.stem()),
                t.images().getOrDefault(QuizSnapshot.questionImageKey(q.no()), q.image()), q.points(), options,
                q.selection()));
        }
        return new QuizSnapshot(t.texts().getOrDefault(QuizSnapshot.TITLE_KEY, snapshot.title()),
            t.texts().getOrDefault(QuizSnapshot.INTRO_KEY, snapshot.intro()), snapshot.cover(),
            snapshot.timeLimitSec(), snapshot.aiGenerated(), materials, questions, snapshot.survey());
    }

    /** The canonical text: keys sorted, no white space, absent values written as {@code null}. */
    public static String toJson(QuizSnapshot snapshot) {
        return JSON.writeValueAsString(toMap(snapshot));
    }

    public static QuizSnapshot fromJson(String json) {
        return fromMap(JSON.readValue(json, MAP));
    }

    /** The hash of the content, as approvals bind to it: SHA-256 hex of the platform's canonical form. */
    public static String hash(QuizSnapshot snapshot) {
        return ContentHash.of(toMap(snapshot));
    }

    /** The hash of a stored text, recomputed from what it says. */
    public static String hashOfText(String json) {
        return ContentHash.of(JSON.readValue(json, MAP));
    }

    /** The size of the canonical text in UTF-8 bytes. */
    public static int size(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Every file the snapshot refers to, once each in order of appearance, with the field of {@code QbVersionFile}
     * its policy belongs in ({@link #IMAGE}, {@link #PDF}, {@link #AUDIO}).
     */
    public static Map<UUID, String> files(QuizSnapshot snapshot) {
        Map<UUID, String> files = new LinkedHashMap<>();
        add(files, snapshot.cover(), IMAGE);
        for (QuizSnapshot.Material material : snapshot.materials()) {
            add(files, material.pdf(), PDF);
            add(files, material.audio(), AUDIO);
            material.images().forEach(image -> add(files, image.image(), IMAGE));
        }
        for (QuizSnapshot.Question question : snapshot.questions()) {
            add(files, question.image(), IMAGE);
            question.options().forEach(option -> add(files, option.image(), IMAGE));
        }
        // Q73a: each other language's own images and materials.
        for (QuizSnapshot.Language language : snapshot.translations().values()) {
            language.images().values().forEach(image -> add(files, image, IMAGE));
            for (QuizSnapshot.Material material : language.materials()) {
                add(files, material.pdf(), PDF);
                add(files, material.audio(), AUDIO);
                material.images().forEach(image -> add(files, image.image(), IMAGE));
            }
        }
        return files;
    }

    private static void add(Map<UUID, String> files, UUID file, String field) {
        if (file != null) {
            files.putIfAbsent(file, field);
        }
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Object value) {
        return Values.uuid(value);
    }

    /** A whole number that must be there: absent or a fraction is not a snapshot of this schema. */
    private static int number(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("A number is missing");
        }
        return Values.intValue(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, ?>> maps(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Not a list: " + value);
        }
        return (List<Map<String, ?>>) list;
    }

    private QuizSnapshots() {}
}

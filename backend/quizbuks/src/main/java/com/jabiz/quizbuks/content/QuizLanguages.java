package com.jabiz.quizbuks.content;

import com.jabiz.runtime.EntityInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A draft in each of its languages (Q73a, docs/quizbuks/plans/Q73-content-languages.md §2.2–§2.3): from the rows of
 * the quiz, its base snapshot, and for each of its other languages what that language has (its text, its own images
 * and materials, the hash each translation was made from) and what it lacks (texts missing or out of date, materials
 * of its own that are not complete). A version keeps the complete languages only; a copy keeps them all.
 */
public final class QuizLanguages {

    /**
     * One other language of the draft.
     *
     * @param missing    keys of the base's texts it has no translation of
     * @param stale      keys of translations made from another base text than the present one
     * @param materials  numbers of the base materials whose material of this language is not complete
     */
    public record Status(String language, List<String> missing, List<String> stale, List<Integer> materials) {
        public Status {
            missing = List.copyOf(missing);
            stale = List.copyOf(stale);
            materials = List.copyOf(materials);
        }

        public boolean complete() {
            return missing.isEmpty() && stale.isEmpty() && materials.isEmpty();
        }
    }

    /**
     * The draft read in its languages.
     *
     * @param snapshot the version's content: the base with the complete languages' translations
     * @param all      every other language as the draft has it, complete or not (a copy keeps them)
     * @param hashes   by language and key in the snapshot, the base hash each translation was made from
     * @param statuses every other language's state, in the quiz's order
     */
    public record Draft(QuizSnapshot snapshot, Map<String, QuizSnapshot.Language> all,
        Map<String, Map<String, String>> hashes, List<Status> statuses) {

        /** The version's languages: the base first, then the complete ones. */
        public List<String> languages(String base) {
            List<String> languages = new ArrayList<>(List.of(base));
            languages.addAll(snapshot.translations().keySet());
            return languages;
        }
    }

    public static Draft of(EntityInstance quiz, List<EntityInstance> materials, List<EntityInstance> images,
        List<EntityInstance> questions, List<EntityInstance> options, List<EntityInstance> translations) {
        QuizSnapshot base = QuizEditing.snapshot(quiz, materials, images, questions, options);
        List<String> languages = ContentLanguages.parseList(quiz.get("translationLanguages"));
        if (languages.isEmpty()) {
            return new Draft(base, Map.of(), Map.of(), List.of());
        }
        // The draft's keys of each element and their keys in the snapshot, in the base's order.
        Map<String, String> keys = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        keys.put(ContentLanguages.TITLE, QuizSnapshot.TITLE_KEY);
        sources.put(ContentLanguages.TITLE, base.title());
        keys.put(ContentLanguages.INTRO, QuizSnapshot.INTRO_KEY);
        sources.put(ContentLanguages.INTRO, base.intro());
        Map<String, List<EntityInstance>> optionsOf = QuizEditing.groups(options, "questionId");
        List<EntityInstance> orderedQuestions = QuizEditing.ordered(questions);
        for (int q = 0; q < orderedQuestions.size(); q++) {
            EntityInstance question = orderedQuestions.get(q);
            int no = q + 1;
            keys.put(ContentLanguages.stem(question.id()), QuizSnapshot.stemKey(no));
            sources.put(ContentLanguages.stem(question.id()), question.get("stem"));
            keys.put(ContentLanguages.questionImage(question.id()), QuizSnapshot.questionImageKey(no));
            List<EntityInstance> its = QuizEditing.ordered(optionsOf.getOrDefault(QuizEditing.key(question.id()),
                List.of()));
            for (int o = 0; o < its.size(); o++) {
                EntityInstance option = its.get(o);
                keys.put(ContentLanguages.optionText(option.id()), QuizSnapshot.optionTextKey(no, o + 1));
                sources.put(ContentLanguages.optionText(option.id()), option.get("text"));
                keys.put(ContentLanguages.optionImage(option.id()), QuizSnapshot.optionImageKey(no, o + 1));
            }
        }
        Map<String, Integer> baseNumbers = new LinkedHashMap<>();
        List<EntityInstance> bases = bases(materials);
        for (int m = 0; m < bases.size(); m++) {
            baseNumbers.put(QuizEditing.key(bases.get(m).id()), m + 1);
        }
        Map<String, List<EntityInstance>> imagesOf = QuizEditing.groups(images, "materialId");

        Map<String, QuizSnapshot.Language> complete = new LinkedHashMap<>();
        Map<String, QuizSnapshot.Language> all = new LinkedHashMap<>();
        Map<String, Map<String, String>> hashes = new LinkedHashMap<>();
        List<Status> statuses = new ArrayList<>();
        for (String language : languages) {
            Map<String, EntityInstance> rows = new LinkedHashMap<>();
            translations.stream().filter(row -> language.equals(row.get("language")))
                .forEach(row -> rows.put(row.get("elementKey"), row));
            Map<String, String> texts = new LinkedHashMap<>();
            Map<String, UUID> ownImages = new LinkedHashMap<>();
            Map<String, String> made = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            keys.forEach((key, snapshotKey) -> {
                EntityInstance row = rows.get(key);
                boolean image = key.endsWith(".image");
                if (image) {
                    UUID file = row == null ? null : QuizEditing.uuid(row.get("image"));
                    if (file != null) {
                        ownImages.put(snapshotKey, file);
                    }
                    return;
                }
                String source = QuizEditing.text(sources.get(key));
                String text = row == null ? null : QuizEditing.text(row.get("text"));
                if (text != null) {
                    texts.put(snapshotKey, text);
                    made.put(snapshotKey, row.get("sourceHash"));
                }
                if (source == null) {
                    return;
                }
                if (text == null) {
                    missing.add(key);
                } else if (!ContentLanguages.sourceHash(source).equals(row.get("sourceHash"))) {
                    stale.add(key);
                }
            });
            List<QuizSnapshot.Material> own = new ArrayList<>();
            List<Integer> incomplete = new ArrayList<>();
            for (EntityInstance variant : variants(materials, language)) {
                Integer no = baseNumbers.get(QuizEditing.key(variant.get("variantOf")));
                if (no == null) {
                    continue;
                }
                QuizSnapshot.Material material = material(variant, no,
                    imagesOf.getOrDefault(QuizEditing.key(variant.id()), List.of()));
                own.add(material);
                if (!QuizCompleteness.material(material, "materials").isEmpty()) {
                    incomplete.add(no);
                }
            }
            own.sort(java.util.Comparator.comparingInt(QuizSnapshot.Material::no));
            QuizSnapshot.Language present = new QuizSnapshot.Language(texts, ownImages, own);
            Status status = new Status(language, missing, stale, incomplete);
            statuses.add(status);
            all.put(language, present);
            hashes.put(language, made);
            if (status.complete()) {
                complete.put(language, present);
            }
        }
        QuizSnapshot snapshot = new QuizSnapshot(base.title(), base.intro(), base.cover(), base.timeLimitSec(),
            base.aiGenerated(), base.materials(), base.questions(), base.survey(), complete);
        return new Draft(snapshot, all, hashes, statuses);
    }

    /** The base materials of the quiz (not those of its other languages). */
    public static List<EntityInstance> bases(List<EntityInstance> materials) {
        return QuizEditing.ordered(materials.stream().filter(m -> m.get("variantOf") == null).toList());
    }

    /** The materials of one of the quiz's other languages. */
    public static List<EntityInstance> variants(List<EntityInstance> materials, String language) {
        return materials.stream().filter(m -> m.get("variantOf") != null && language.equals(m.get("language")))
            .toList();
    }

    private static QuizSnapshot.Material material(EntityInstance row, int no, List<EntityInstance> images) {
        List<QuizSnapshot.Image> list = new ArrayList<>();
        for (EntityInstance image : QuizEditing.ordered(images)) {
            list.add(new QuizSnapshot.Image(list.size() + 1, QuizEditing.uuid(image.get("image")),
                image.get("caption")));
        }
        return new QuizSnapshot.Material(no, row.get("kind"), row.get("title"), row.get("description"),
            row.get("body"), row.get("url"), QuizEditing.uuid(row.get("pdf")), QuizEditing.uuid(row.get("audio")),
            list, false);
    }

    private QuizLanguages() {}
}

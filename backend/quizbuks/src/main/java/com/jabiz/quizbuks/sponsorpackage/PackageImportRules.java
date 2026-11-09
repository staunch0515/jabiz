package com.jabiz.quizbuks.sponsorpackage;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of a sponsor package's import record (F-C4, docs/quizbuks/plans/impl/F-C4.md §2), pure Java: the shapes
 * of a package's hash and of a step's key, the record of the steps done (a JSON object, step key to what the step made:
 * ids only), and what of the account an import made, for giving it up.
 */
public final class PackageImportRules {

    /** A SHA-256 in hex, as the console computes it over the package's bytes. */
    public static final int HASH_LENGTH = 64;
    public static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    /** A step's key: the console names its steps (`quiz:q1:v:q1v2:content`, `publication:3` …). */
    public static final int STEP_LENGTH = 200;
    public static final Pattern STEP = Pattern.compile("[A-Za-z0-9:._/-]{1,200}");
    /** The most steps one import has (a package of many large banks stays far below it). */
    public static final int MAX_STEPS = 100_000;
    /** The most questions a batch holds (the parameter's own ceiling is lower). */
    public static final int MAX_BATCH = 10_000;

    /** What a step made, in its result: a quiz, a version, a graph, a publication. */
    public static final String QUIZ_ID = "quizId";
    public static final String VERSION_ID = "versionId";
    public static final String TREE_ID = "treeId";
    public static final String PUBLICATION_ID = "publicationId";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PackageImportRules() {}

    /** The steps done, in the order done; nothing for a blank or unreadable record. */
    public static Map<String, Map<String, Object>> done(Object json) {
        if (json == null || String.valueOf(json).isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Map<String, Object>> read = JSON.readValue(String.valueOf(json),
                new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {});
            return read == null ? new LinkedHashMap<>() : new LinkedHashMap<>(read);
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The record of the steps done, as stored. */
    public static String json(Map<String, Map<String, Object>> done) {
        return JSON.writeValueAsString(done);
    }

    /** The record with one more step done (a step done before keeps its result). */
    public static Map<String, Map<String, Object>> with(Map<String, Map<String, Object>> done, String step,
        Map<String, Object> result) {
        Map<String, Map<String, Object>> next = new LinkedHashMap<>(done);
        Map<String, Object> kept = new LinkedHashMap<>();
        result.forEach((key, value) -> {
            if (value != null) {
                kept.put(key, value);
            }
        });
        next.putIfAbsent(step, kept);
        return next;
    }

    /** What the import made of one kind ({@link #QUIZ_ID}, {@link #TREE_ID}, {@link #PUBLICATION_ID}), each once. */
    public static List<String> made(Map<String, Map<String, Object>> done, String kind) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> step : done.entrySet()) {
            Object id = step.getValue() == null ? null : step.getValue().get(kind);
            // A quiz is made by its first step; later steps (versions, batches, other contents) name it again.
            if (id != null && !String.valueOf(id).isBlank() && madeBy(step.getKey(), kind)) {
                ids.add(String.valueOf(id));
            }
        }
        return new ArrayList<>(ids);
    }

    /** Whether a step of this key makes (rather than only names) a thing of the kind. */
    static boolean madeBy(String step, String kind) {
        return switch (kind) {
            case QUIZ_ID -> step.startsWith("quiz:");
            case TREE_ID -> step.startsWith("tree:");
            case PUBLICATION_ID -> step.startsWith("publication:");
            default -> false;
        };
    }

    /** Whether the record's steps are all done. */
    public static boolean finished(int stepsDone, int stepsTotal) {
        return stepsDone >= stepsTotal;
    }
}

package com.jabiz.quizbuks.sponsorpackage;

import com.jabiz.quizbuks.sponsorpackage.PackagePlan.Binding;
import com.jabiz.quizbuks.sponsorpackage.PackagePlan.Step;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.quizbuks.sponsorpackage.PackageJson.isObject;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.map;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.object;

/**
 * The input of each step of a package's import ({@code QB_PACKAGE_IMPORT_STEP}), as the sponsor page makes it
 * ({@code inputOfStep} in {@code profile/package.ts}; F-LAUNCH Q1). No I/O: the caller uploads the files a step names
 * ({@link QuizFileCut#usedFiles} of a content's or batch's document, the profile's images) and gives what they became;
 * the earlier steps' results give the ids a step names. The shared cases ({@code package-plan-cases.json}) compare
 * the two sides' inputs; a change to either changes the other.
 */
public final class PackageStepInputs {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** The latest time JavaScript's {@code Date} holds (ms either side of the epoch). */
    private static final long MAX_DATE_MS = 8_640_000_000_000_000L;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC);
    /** The rest of a time after a six-digit year. */
    private static final DateTimeFormatter AFTER_YEAR = DateTimeFormatter.ofPattern("-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC);

    private PackageStepInputs() {}

    /**
     * What a step's input takes besides the plan.
     *
     * @param files the profile's images or the content's files as uploaded (null: none, the key is left out)
     * @param mailPassword the profile's mail server password, when its plan asks for it
     */
    public record Uploads(Object files, String mailPassword) {
        public static final Uploads NONE = new Uploads(null, null);
    }

    /**
     * The step's input.
     *
     * @param results what the earlier steps gave, by step key: each step's {@code StepOutput.result} of
     *     {@code QB_PACKAGE_IMPORT_STEP} (an import gone on with gives the steps done so far in {@code ImportView.done}
     *     of {@code QB_PACKAGE_IMPORT_START}). The keys read: a {@link Step.Content}'s {@code quizId} (by the later
     *     contents, clears, batches and versions of its quiz, and its publications), a {@link Step.Version}'s
     *     {@code versionId} (by the publications on it), a {@link Step.TreeStep}'s {@code treeId} and {@code nodeIds}
     *     (the node ids by the package's node keys, by the publications bound to its nodes). An earlier step missing
     *     from it leaves the id null, as on the page; run the steps in the plan's order.
     * @param sharedVideos the package's shared videos this environment shares now (the others are chosen again)
     * @param startAt a publication's start
     */
    public static Map<String, Object> inputOf(Step step, PackagePlan.Document document,
        Map<String, Map<String, Object>> results, Set<String> sharedVideos, Instant startAt, Uploads uploads) {
        return switch (step) {
            case Step.Profile s -> {
                if (document.profile() == null) {
                    throw new IllegalStateException("No profile part was checked");
                }
                Map<String, Object> profile = new LinkedHashMap<>();
                profile.put("document", PackageJson.withoutData(document.profile()));
                putFiles(profile, uploads);
                String password = uploads.mailPassword();
                profile.put("mailPassword", password == null || password.isEmpty() ? null : password);
                yield map("profile", profile);
            }
            case Step.TreeStep s -> {
                List<Object> nodes = new ArrayList<>();
                s.tree().nodes().forEach(node -> nodes.add(map("key", node.key(), "name", node.name(), "description",
                    node.description(), "condition", node.condition())));
                yield map("tree", map("create", map("ownerKind", "SPONSOR", "name", s.tree().name(), "description",
                    s.tree().description()), "nodes", nodes));
            }
            case Step.Content s -> {
                Map<String, Object> quiz = new LinkedHashMap<>();
                quiz.put("document", PackageJson.withoutData(s.document()));
                putFiles(quiz, uploads);
                String into = s.into() == null ? null : id(results, s.into(), "quizId");
                if (into != null && !into.isEmpty()) {
                    quiz.put("intoQuizId", into);
                }
                yield map("quiz", quiz, "quizTopics", map("topics", s.topics(), "customTopics", s.customTopics()));
            }
            case Step.Clear s -> map("clear", map("quizId", id(results, s.quizStep(), "quizId"), "rows",
                QuizFileCut.CLEAR_ROWS));
            case Step.Batch s -> {
                Map<String, Object> questions = new LinkedHashMap<>();
                questions.put("quizId", id(results, s.quizStep(), "quizId"));
                questions.put("document", PackageJson.withoutData(s.document()));
                putFiles(questions, uploads);
                yield map("questions", questions);
            }
            case Step.Version s -> map("version", map("quizId", id(results, s.quizStep(), "quizId")));
            case Step.PublicationStep s -> map("publication", publication(s, results, sharedVideos, startAt));
        };
    }

    private static Map<String, Object> publication(Step.PublicationStep step, Map<String, Map<String, Object>> results,
        Set<String> sharedVideos, Instant startAt) {
        PackagePlan.Publication publication = step.publication();
        Map<String, Object> settings = publication.settings();
        String start = iso(startAt.toEpochMilli());
        Object days = settings.get("durationDays");
        String end = PackageJson.truthy(days) ? iso(startAt.toEpochMilli() + (long) (((Number) days).doubleValue()
            * DAY_MS)) : null;
        String versionId = id(results, step.versionStep(), "versionId");
        Map<String, Object> save = PublicationSettingsFile.saveInput(settings,
            versionId == null || versionId.isEmpty() ? null : versionId, start, end);
        switch (step.node()) {
            case Binding.ToMade made -> {
                String treeId = id(results, made.treeStep(), "treeId");
                Map<String, Object> made_ = results.get(made.treeStep());
                Object nodeIds = made_ == null ? null : made_.get("nodeIds");
                Object nodeId = isObject(nodeIds) ? object(nodeIds).get(made.nodeKey()) : null;
                if (treeId != null && !treeId.isEmpty() && PackageJson.truthy(nodeId)) {
                    save.put("treeId", treeId);
                    save.put("treeNodeId", nodeId);
                }
            }
            case Binding.ToOwn own -> {
                save.put("treeId", own.treeId());
                save.put("treeNodeId", own.nodeId());
            }
            case null -> { }
        }
        List<Object> conditions = PackageJson.items(settings.get("conditions"));
        PackagePlan.TopicChanges topics = publication.topics();
        String video = publication.sharedVideo();
        return map("save", save,
            "criteria", "INVITED".equals(settings.get("audience")) || conditions.isEmpty() ? null : conditions,
            "topics", topics != null && topics.any() ? map("add", topics.add(), "remove", topics.remove(),
                "customAdd", topics.customAdd(), "customRemove", topics.customRemove()) : null,
            "sharedVideoId", video != null && !video.isEmpty() && sharedVideos.contains(video) ? video : null);
    }

    /** The field of an earlier step's result as text (the page's {@code String(value)}), or null. */
    private static String id(Map<String, Map<String, Object>> results, String key, String field) {
        Map<String, Object> result = key == null ? null : results.get(key);
        Object value = result == null ? null : result.get(field);
        if (value == null) {
            return null;
        }
        return value instanceof String text ? text : value instanceof Number n ? PackageJson.number(n)
            : String.valueOf(value);
    }

    private static void putFiles(Map<String, Object> into, Uploads uploads) {
        if (uploads.files() != null) {
            into.put("files", uploads.files());
        }
    }

    /** A time as JavaScript's {@code Date.prototype.toISOString} writes it (six-digit years outside 0..9999). */
    static String iso(long epochMilli) {
        if (Math.abs(epochMilli) > MAX_DATE_MS) {
            throw new IllegalArgumentException("Invalid time value");
        }
        Instant instant = Instant.ofEpochMilli(epochMilli);
        int year = instant.atZone(ZoneOffset.UTC).getYear();
        if (year >= 0 && year <= 9999) {
            return ISO.format(instant);
        }
        return (year < 0 ? "-" : "+") + String.format("%06d", Math.abs(year)) + AFTER_YEAR.format(instant);
    }

}

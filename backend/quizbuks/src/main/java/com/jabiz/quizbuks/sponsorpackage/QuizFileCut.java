package com.jabiz.quizbuks.sponsorpackage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jabiz.quizbuks.sponsorpackage.PackageJson.bytesOf;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.copy;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.isObject;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.items;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.object;

/**
 * A quiz file cut into the requests of a package's steps (F-LAUNCH Q1): the Java of the sponsor page's
 * {@code content/bank.ts} ({@code planBatches}, {@code splitQuestions}) and {@code profile/package.ts}
 * ({@code firstBatch}, {@code partsOf}, {@code planContent}). The two are kept the same by the cases both sides run
 * ({@code quizbuks-web/sponsor/src/profile/package-plan-cases.json}); a change to either changes the other.
 */
public final class QuizFileCut {

    // The documents given are in JavaScript's key order (PackagePlan.read gives them so: PackageJson.jsOrdered).

    /** The rows one step that clears a content takes at most (the server's {@code ContentLimits.CLEAR_ROWS_TECH}). */
    public static final int CLEAR_ROWS = 2000;

    /**
     * A translation key of a question ({@code q12.stem}, {@code q12.o3.image}): its number and the rest. JavaScript's
     * {@code .} is spelled out: Java's also stops at U+0085.
     */
    private static final Pattern QUESTION_KEY = Pattern.compile("^q([0-9]+)\\.([^\n\r\u2028\u2029]+)$");

    private QuizFileCut() {}

    /** A content cut: its first step, the batches after it, and what no request can take (question numbers; null: the heading and materials). */
    public record Cut(Map<String, Object> first, List<Map<String, Object>> batches, List<Integer> oversized) {}

    /** The batches of a file's questions and the questions alone over the byte limit (numbers from 1). */
    public record Batches(List<Map<String, Object>> batches, List<Integer> tooLarge, long maxBytes) {}

    public static List<Object> questionsOf(Map<String, Object> file) {
        return items(file.get("questions"));
    }

    /** The quiz's title, or empty. */
    public static String titleOf(Map<String, Object> file) {
        return isObject(file.get("quiz")) && object(file.get("quiz")).get("title") instanceof String title ? title : "";
    }

    /**
     * The keys of the file's {@code files} its other parts name (any string equal to a key, at any depth), in the
     * order met: the files a content's request uploads (the page's {@code usedFiles}).
     */
    public static Set<String> usedFiles(Map<String, Object> document) {
        Map<String, Object> files = isObject(document.get("files")) ? object(document.get("files")) : Map.of();
        Set<String> used = new LinkedHashSet<>();
        document.forEach((key, value) -> {
            if (!"files".equals(key)) {
                walk(value, files, used);
            }
        });
        return used;
    }

    private static void walk(Object value, Map<String, Object> files, Set<String> used) {
        if (value instanceof String text) {
            if (files.containsKey(text)) {
                used.add(text);
            }
        } else if (value instanceof List<?> list) {
            list.forEach(item -> walk(item, files, used));
        } else if (isObject(value)) {
            object(value).values().forEach(item -> walk(item, files, used));
        }
    }

    /** The keys of a question's file fields (its image, its options' images). */
    private static List<String> fileKeysOf(Object question) {
        List<String> keys = new ArrayList<>();
        if (!isObject(question)) {
            return keys;
        }
        Map<String, Object> q = object(question);
        if (q.get("image") instanceof String image) {
            keys.add(image);
        }
        for (Object option : items(q.get("options"))) {
            if (isObject(option) && object(option).get("image") instanceof String image) {
                keys.add(image);
            }
        }
        return keys;
    }

    /** The questions {@code from + 1 .. from + count} of a translation's entries, renumbered from 1. */
    private static Map<String, Object> renumbered(Object entries, int from, int count) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!isObject(entries)) {
            return out;
        }
        object(entries).forEach((key, value) -> {
            Matcher match = QUESTION_KEY.matcher(key);
            if (!match.matches()) {
                return;
            }
            // As the page's Number(...): any length of digits reads (as the nearest double).
            double n = Double.parseDouble(match.group(1));
            if (n > from && n <= (double) from + count) {
                out.put("q" + PackageJson.number(n - from) + "." + match.group(2), value);
            }
        });
        return out;
    }

    /**
     * The batch of the questions {@code from .. to - 1} (from 0): the same format and heading without its cover, only
     * its questions numbered 1..k, each other language's texts, images and hashes of them renumbered, no materials,
     * no products, and only the files its questions use.
     */
    static Map<String, Object> batchOf(Map<String, Object> document, int from, int to) {
        List<Object> questions = questionsOf(document);
        Map<String, Object> files = isObject(document.get("files")) ? object(document.get("files")) : Map.of();
        Map<String, Object> batch = copy(document);
        if (isObject(document.get("quiz"))) {
            Map<String, Object> heading = copy(object(document.get("quiz")));
            heading.put("cover", null);
            batch.put("quiz", heading);
        }
        List<Object> own = new ArrayList<>(questions.subList(Math.min(from, questions.size()),
            Math.min(to, questions.size())));
        Set<String> used = new LinkedHashSet<>();
        own.forEach(question -> used.addAll(fileKeysOf(question)));
        batch.put("materials", new ArrayList<>());
        batch.put("questions", own);
        batch.remove("products");
        if (isObject(document.get("translations"))) {
            Map<String, Object> translations = new LinkedHashMap<>();
            object(document.get("translations")).forEach((language, t) -> {
                if (!isObject(t)) {
                    return;
                }
                Map<String, Object> images = renumbered(object(t).get("images"), from, own.size());
                images.values().forEach(key -> {
                    if (key instanceof String text) {
                        used.add(text);
                    }
                });
                Map<String, Object> translated = new LinkedHashMap<>();
                translated.put("texts", renumbered(object(t).get("texts"), from, own.size()));
                translated.put("images", images);
                translated.put("materials", new ArrayList<>());
                translated.put("hashes", renumbered(object(t).get("hashes"), from, own.size()));
                translations.put(language, translated);
            });
            batch.put("translations", translations);
        }
        Map<String, Object> batchFiles = new LinkedHashMap<>();
        for (String key : used) {
            if (files.containsKey(key)) {
                batchFiles.put(key, files.get(key));
            }
        }
        batch.put("files", batchFiles);
        return batch;
    }

    /**
     * The batches by both limits, {@code size} questions and {@code maxBytes} bytes (0: only the questions count),
     * from the question {@code start} (from 0) on: each takes as many questions as both allow, found by halving; a
     * question alone over the bytes is a batch of its own, listed in {@code tooLarge}.
     */
    public static Batches planBatches(Map<String, Object> document, int size, long maxBytes, int start) {
        List<Object> questions = questionsOf(document);
        int per = Math.max(1, size);
        long limit = Math.max(0, maxBytes);
        List<Map<String, Object>> batches = new ArrayList<>();
        List<Integer> tooLarge = new ArrayList<>();
        int from = Math.max(0, start);
        while (from < questions.size()) {
            int to = Math.min(from + per, questions.size());
            if (limit > 0 && bytesOf(batchOf(document, from, to)) > limit) {
                int low = from + 1;
                int high = to - 1;
                while (low <= high) {
                    int middle = (low + high) / 2;
                    if (bytesOf(batchOf(document, from, middle)) <= limit) {
                        low = middle + 1;
                    } else {
                        high = middle - 1;
                    }
                }
                to = Math.max(from + 1, low - 1);
                if (to == from + 1 && bytesOf(batchOf(document, from, to)) > limit) {
                    tooLarge.add(from + 1);
                }
            }
            batches.add(batchOf(document, from, to));
            from = to;
        }
        return new Batches(batches, tooLarge, limit);
    }

    /** The file's questions as batches of at most {@code size}. */
    public static List<Map<String, Object>> splitQuestions(Map<String, Object> document, int size) {
        return planBatches(document, size, 0, 0).batches();
    }

    /**
     * The first batch of a content: everything of the file but the questions after the first {@code size} (and their
     * translations and products), so that the materials, the heading and their translations come with it.
     */
    public static Map<String, Object> firstBatch(Map<String, Object> file, int size) {
        List<Object> questions = questionsOf(file);
        if (questions.size() <= size) {
            return file;
        }
        Map<String, Object> out = copy(file);
        if (isObject(file.get("translations"))) {
            Map<String, Object> translations = new LinkedHashMap<>();
            object(file.get("translations")).forEach((language, t) -> {
                if (!isObject(t)) {
                    translations.put(language, t);
                    return;
                }
                Map<String, Object> kept = copy(object(t));
                for (String part : List.of("texts", "images", "hashes")) {
                    if (kept.containsKey(part)) {
                        kept.put(part, keep(kept.get(part), size));
                    }
                }
                translations.put(language, kept);
            });
            out.put("translations", translations);
        }
        if (isObject(file.get("products"))) {
            Map<String, Object> products = copy(object(file.get("products")));
            List<Object> links = new ArrayList<>();
            for (Object link : items(object(file.get("products")).get("questions"))) {
                if (isObject(link) && object(link).get("no") instanceof Number no && no.doubleValue() <= size) {
                    links.add(link);
                }
            }
            products.put("questions", links);
            out.put("products", products);
        }
        out.put("questions", new ArrayList<>(questions.subList(0, size)));
        return out;
    }

    /** A translation's entries but those of the questions after the first {@code size}. */
    private static Object keep(Object entries, int size) {
        if (!isObject(entries)) {
            return entries;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        object(entries).forEach((key, value) -> {
            Matcher match = QUESTION_KEY.matcher(key);
            if (!match.matches() || Double.parseDouble(match.group(1)) <= size) {
                kept.put(key, value);
            }
        });
        return kept;
    }

    /**
     * The parts (rows) a quiz file makes: materials and their images, questions and options, every text, image and
     * material a translation has. The server deletes a content part by part: this says how many steps clear it.
     */
    public static int partsOf(Map<String, Object> file) {
        int parts = items(file.get("materials")).size();
        for (Object material : items(file.get("materials"))) {
            parts += isObject(material) ? items(object(material).get("images")).size() : 0;
        }
        for (Object question : questionsOf(file)) {
            parts += 1 + (isObject(question) ? items(object(question).get("options")).size() : 0);
        }
        if (isObject(file.get("translations"))) {
            for (Object t : object(file.get("translations")).values()) {
                if (isObject(t)) {
                    Map<String, Object> translation = object(t);
                    parts += size(translation.get("texts")) + size(translation.get("images"))
                        + items(translation.get("materials")).size();
                }
            }
        }
        return parts;
    }

    private static int size(Object value) {
        return isObject(value) ? object(value).size() : 0;
    }

    /**
     * The content of a file cut by the questions ({@code size}) and, when {@code maxBytes} is above 0, by the bytes a
     * request takes: the first step (heading, materials and their translations, the first questions) and the batches
     * of the rest, each within both.
     */
    public static Cut planContent(Map<String, Object> file, int size, long maxBytes) {
        List<Object> questions = questionsOf(file);
        if (maxBytes <= 0) {
            List<Map<String, Object>> all = splitQuestions(file, size);
            return new Cut(firstBatch(file, size), all.isEmpty() ? List.of() : List.copyOf(all.subList(1, all.size())),
                List.of());
        }
        int room = Math.min(size, questions.size());
        int low = 0;
        int high = room;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (bytesOf(firstBatch(file, middle)) <= maxBytes) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        Map<String, Object> first = firstBatch(file, low);
        List<Integer> oversized = new ArrayList<>();
        if (bytesOf(first) > maxBytes) {
            oversized.add(null);
        }
        Batches rest = planBatches(file, size, maxBytes, low);
        oversized.addAll(rest.tooLarge());
        return new Cut(first, rest.batches(), oversized);
    }
}

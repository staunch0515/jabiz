package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.sponsorpackage.PackageImportProcesses;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-F11a (docs/quizbuks/plans/impl/F-F11.md section 3.4): the shapes of the sample Sponsors' packages, for the tests only.
 * Quiz files of any size (the one {@link LargeBankIT} had, now shared), the cut of a file into import batches by the
 * question count and by the bytes of a request (as the page cuts it: {@code quizbuks-web/sponsor/src/content/bank.ts}),
 * and a sample package's steps in the order and under the keys the page plans them, so that a test imports a sample as
 * the real import does, step by step, and goes on after a stop.
 */
final class PackageFixtures {

    static final int OPTIONS = 6;

    private static final Pattern QUESTION_KEY = Pattern.compile("^q(\\d+)\\.(.+)$");
    private static final String[] WORDS = {"harbor", "lantern", "maple", "orbit", "pepper", "quartz", "river",
        "saddle", "timber", "umber", "velvet", "willow", "xenon", "yonder", "zephyr", "amber", "basalt", "cobalt",
        "dune", "ember", "fjord", "garnet", "hollow", "iris", "juniper", "kelp", "lichen", "mesa", "nectar", "onyx",
        "prism", "quill", "ridge", "sorrel", "tundra", "ultra", "vortex", "wren", "yarrow", "zinc"};

    private PackageFixtures() {}

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ---- quiz files ------------------------------------------------------------------------------------------------

    /** A quiz file of {@code count} one-point questions of {@link #OPTIONS} options, English with Chinese and Japanese. */
    static Map<String, Object> bank(int count) {
        return bank("Large bank", count, OPTIONS, 1L);
    }

    /**
     * A quiz file of {@code count} one-point questions of {@code options} options (the first right), base English, with
     * Chinese and Japanese for every text. Each question names random words ({@code seed}), so that no two texts repeat
     * and the file does not compress better than a real one.
     */
    static Map<String, Object> bank(String title, int count, int options, long seed) {
        Random random = new Random(seed);
        List<Object> questions = new ArrayList<>();
        Map<String, Object> zh = new LinkedHashMap<>();
        Map<String, Object> ja = new LinkedHashMap<>();
        zh.put("title", title + "（题库）");
        ja.put("title", title + "（問題集）");
        for (int q = 1; q <= count; q++) {
            String subject = WORDS[random.nextInt(WORDS.length)] + " " + WORDS[random.nextInt(WORDS.length)];
            List<Object> list = new ArrayList<>();
            for (int o = 1; o <= options; o++) {
                String word = WORDS[random.nextInt(WORDS.length)] + "-" + random.nextInt(10_000);
                list.add(map("text", "Option " + o + " of question " + q + ": " + word, "correct", o == 1));
                zh.put("q" + q + ".o" + o + ".text", "第 " + q + " 题的选项 " + o + "：" + word);
                ja.put("q" + q + ".o" + o + ".text", "問題 " + q + " の選択肢 " + o + "：" + word);
            }
            questions.add(map("stem", "Question " + q + ": which of these statements about the " + subject
                + " is the correct one, given what the material explains?", "points", 1, "options", list));
            zh.put("q" + q + ".stem", "第 " + q + " 题：根据资料的说明，关于" + subject + "，下列哪一种说法是正确的？");
            ja.put("q" + q + ".stem", "問題 " + q + "：資料の説明によると、" + subject + "について正しいのは次のうちどれですか？");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "quizbuks.quiz");
        document.put("formatVersion", 3);
        document.put("quiz", map("kind", "QUIZ", "title", title, "timeLimitSec", 3600, "language", "en"));
        document.put("materials", List.of());
        document.put("questions", questions);
        document.put("files", Map.of());
        document.put("translations", map("zh", map("texts", zh, "images", Map.of(), "materials", List.of()),
            "ja", map("texts", ja, "images", Map.of(), "materials", List.of())));
        return document;
    }

    /** The file with one more material, a link to a video (first, so numbered 1: the sample files have no other). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withVideo(Map<String, Object> document, String url) {
        Map<String, Object> copy = new LinkedHashMap<>(document);
        List<Object> materials = new ArrayList<>((List<Object>) document.get("materials"));
        materials.add(map("no", materials.size() + 1, "kind", "VIDEO_LINK", "title", "Film", "url", url));
        copy.put("materials", materials);
        return copy;
    }

    /**
     * The largest batch the limits let a Sponsor mean: {@code count} questions of a 1000-character stem and six
     * 300-character options, in three languages, every character a three-byte one (about 5 MB for 200 questions).
     */
    static Map<String, Object> worstBatch(int count) {
        List<Object> questions = new ArrayList<>();
        Map<String, Object> zh = new LinkedHashMap<>();
        Map<String, Object> ja = new LinkedHashMap<>();
        for (int q = 1; q <= count; q++) {
            List<Object> options = new ArrayList<>();
            for (int o = 1; o <= OPTIONS; o++) {
                options.add(map("text", cjk("甲", q * 10 + o, 300), "correct", o == 1));
                zh.put("q" + q + ".o" + o + ".text", cjk("乙", q * 10 + o, 300));
                ja.put("q" + q + ".o" + o + ".text", cjk("丙", q * 10 + o, 300));
            }
            questions.add(map("stem", cjk("题", q, 1000), "points", 1, "options", options));
            zh.put("q" + q + ".stem", cjk("问", q, 1000));
            ja.put("q" + q + ".stem", cjk("設", q, 1000));
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "quizbuks.quiz");
        document.put("formatVersion", 3);
        document.put("quiz", map("kind", "QUIZ", "title", "Worst batch", "timeLimitSec", 3600, "language", "en"));
        document.put("materials", List.of());
        document.put("questions", questions);
        document.put("files", Map.of());
        document.put("translations", map("zh", map("texts", zh, "images", Map.of(), "materials", List.of()),
            "ja", map("texts", ja, "images", Map.of(), "materials", List.of())));
        return document;
    }

    /** Exactly {@code length} characters, every one of three bytes in UTF-8, different for each {@code salt}. */
    private static String cjk(String lead, int salt, int length) {
        StringBuilder text = new StringBuilder(lead).append(salt);
        char next = (char) (0x4E00 + salt % 1000);
        while (text.length() < length) {
            text.append(next);
            next = (char) (0x4E00 + (next - 0x4E00 + 7) % 5000);
        }
        return text.substring(0, length);
    }

    /** The bytes of a request body carrying {@code value}, as the page counts it ({@code JSON.stringify}). */
    static int bytesOf(Object value) {
        return json(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
        .build();

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    // ---- cutting a file into batches --------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> questionsOf(Map<String, Object> document) {
        return (List<Object>) document.get("questions");
    }

    /** The question texts of a language's translation whose number is in {@code from + 1 .. from + count}, from 1. */
    private static Map<String, Object> renumbered(Object entries, int from, int count) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(entries instanceof Map<?, ?> map)) {
            return out;
        }
        map.forEach((key, value) -> {
            Matcher match = QUESTION_KEY.matcher(String.valueOf(key));
            if (match.matches()) {
                int n = Integer.parseInt(match.group(1));
                if (n > from && n <= from + count) {
                    out.put("q" + (n - from) + "." + match.group(2), value);
                }
            }
        });
        return out;
    }

    /**
     * The file's questions as batches of at most {@code size} questions, each a quiz file of its own as the page cuts
     * it: the same format and heading, its questions numbered 1..k with the other languages' texts renumbered to
     * match, no materials.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> splitQuestions(Map<String, Object> document, int size) {
        List<Object> questions = questionsOf(document);
        List<Map<String, Object>> batches = new ArrayList<>();
        for (int from = 0; from < questions.size(); from += size) {
            batches.add(batch(document, from, Math.min(size, questions.size() - from)));
        }
        return batches;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> batch(Map<String, Object> document, int from, int count) {
        Map<String, Object> batch = new LinkedHashMap<>(document);
        batch.put("materials", List.of());
        batch.put("questions", new ArrayList<>(questionsOf(document).subList(from, from + count)));
        batch.put("files", Map.of());
        batch.remove("products");
        Map<String, Object> translations = new LinkedHashMap<>();
        ((Map<String, Object>) document.getOrDefault("translations", Map.of())).forEach((language, value) -> {
            Map<String, Object> t = (Map<String, Object>) value;
            translations.put(language, map("texts", renumbered(t.get("texts"), from, count), "images",
                renumbered(t.get("images"), from, count), "materials", List.of(), "hashes",
                renumbered(t.get("hashes"), from, count)));
        });
        batch.put("translations", translations);
        return batch;
    }

    /**
     * As {@link #splitQuestions}, and no batch carries more than {@code bytes} bytes (counted over the file itself, the
     * request's envelope is a few hundred more). A question that alone passes it cannot be cut: the test fails there.
     */
    static List<Map<String, Object>> splitByBytes(Map<String, Object> document, int size, int bytes) {
        List<Object> questions = questionsOf(document);
        // The cost of each question: its own bytes and those of its texts in every language (a good estimate; the
        // batches are measured afterwards).
        int[] weight = new int[questions.size()];
        for (int q = 0; q < weight.length; q++) {
            weight[q] = bytesOf(questions.get(q));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> translations = (Map<String, Object>) document.getOrDefault("translations", Map.of());
        translations.values().forEach(value -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> texts = (Map<String, Object>) ((Map<String, Object>) value).get("texts");
            texts.forEach((key, text) -> {
                Matcher match = QUESTION_KEY.matcher(key);
                if (match.matches()) {
                    weight[Integer.parseInt(match.group(1)) - 1] += bytesOf(key) + bytesOf(text) + 1;
                }
            });
        });
        int envelope = bytesOf(batch(document, 0, 0)) + 64;
        List<Map<String, Object>> batches = new ArrayList<>();
        int from = 0;
        while (from < questions.size()) {
            int count = 0;
            int used = envelope;
            while (from + count < questions.size() && count < size && used + weight[from + count] <= bytes) {
                used += weight[from + count];
                count++;
            }
            if (count == 0) {
                throw new AssertionError("Question " + (from + 1) + " alone is over " + bytes + " bytes");
            }
            Map<String, Object> batch = batch(document, from, count);
            if (bytesOf(batch) > bytes) {
                throw new AssertionError("A batch of " + count + " questions from " + (from + 1) + " is "
                    + bytesOf(batch) + " bytes, over " + bytes);
            }
            batches.add(batch);
            from += count;
        }
        return batches;
    }

    /** The first batch of a content, as the page cuts it: the whole file when it has no more than {@code size} questions. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> firstBatch(Map<String, Object> document, int size) {
        List<Object> questions = questionsOf(document);
        if (questions.size() <= size) {
            return document;
        }
        Map<String, Object> first = new LinkedHashMap<>(document);
        first.put("questions", new ArrayList<>(questions.subList(0, size)));
        Map<String, Object> translations = new LinkedHashMap<>();
        ((Map<String, Object>) document.getOrDefault("translations", Map.of())).forEach((language, value) -> {
            Map<String, Object> t = new LinkedHashMap<>((Map<String, Object>) value);
            for (String part : List.of("texts", "images", "hashes")) {
                if (t.get(part) instanceof Map<?, ?> entries) {
                    Map<String, Object> kept = new LinkedHashMap<>();
                    entries.forEach((key, entry) -> {
                        Matcher match = QUESTION_KEY.matcher(String.valueOf(key));
                        if (!match.matches() || Integer.parseInt(match.group(1)) <= size) {
                            kept.put(String.valueOf(key), entry);
                        }
                    });
                    t.put(part, kept);
                }
            }
            translations.put(language, t);
        });
        first.put("translations", translations);
        return first;
    }

    // ---- the sample Sponsors ---------------------------------------------------------------------------------------

    /** What a sample publication is about, which decides the settings it is saved with. */
    enum Kind {
        /** A coffee at the counter: the reward method {@code OFFLINE_REDEEM}. */
        REDEEM,
        /** A shared code or link: the reward method {@code DIGITAL_PRIZE}. */
        DIGITAL,
        /** Bound to a node of the Sponsor's own knowledge graph. */
        NODE
    }

    /**
     * One quiz of a sample: its contents in the order they are imported (the saved versions first, then the current
     * draft, as the page plans them).
     *
     * @param versions the versions' files, oldest first, each with the key the package names it by
     * @param current  the current content
     */
    record SampleQuiz(String key, Map<String, Map<String, Object>> versions, Map<String, Object> current) {

        int questions() {
            return questionsOf(current).size();
        }
    }

    /** One publication of a sample, on one version of one quiz. */
    record SamplePublication(String name, String quiz, String version, Kind kind, Integer pickCount) {}

    /** A sample Sponsor's package: its quizzes, its publications and (the first sample only) a graph. */
    record Sample(String company, List<SampleQuiz> quizzes, List<SamplePublication> publications, boolean graph) {
    }

    /**
     * Sample {@code index} (0 to 4): three quizzes and six publications. The first sample is the safety-knowledge
     * Sponsor, whose first quiz has {@code bigBank} questions of six options in three languages, in two versions and
     * the current content, and whose first publication draws {@code pickCount} of them and is bound to a node of its
     * graph. The others' quizzes have 20 to 60 questions in one version and the current content; the second and third
     * samples' second quiz carries a link to YouTube, and the fourth's and fifth's last two publications give a digital
     * prize (all the others a coffee at the counter).
     */
    static Sample sample(int index, int bigBank, int pickCount) {
        List<SampleQuiz> quizzes = new ArrayList<>();
        for (int q = 1; q <= 3; q++) {
            String title = "Sample " + (index + 1) + " quiz " + q;
            long seed = index * 10L + q;
            int count = index == 0 && q == 1 ? bigBank : 20 + (int) ((seed * 7) % 41);
            Map<String, Object> current = bank(title, count, index == 0 && q == 1 ? OPTIONS : 4, seed);
            Map<String, Map<String, Object>> versions = new LinkedHashMap<>();
            if (index == 0 && q == 1) {
                versions.put("v1", bank(title, Math.max(1, bigBank - 100), OPTIONS, seed + 100));
                versions.put("v2", bank(title, Math.max(1, bigBank - 10), OPTIONS, seed + 200));
            } else {
                versions.put("v1", bank(title, count, 4, seed + 100));
            }
            if ((index == 1 || index == 2) && q == 2) {
                current = withVideo(current, "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
                versions.replaceAll((key, file) -> withVideo(file, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
            }
            quizzes.add(new SampleQuiz("q" + q, versions, current));
        }
        List<SamplePublication> publications = new ArrayList<>();
        for (int p = 1; p <= 6; p++) {
            SampleQuiz quiz = quizzes.get((p - 1) / 2);
            // The last version of the quiz: the big bank's publication goes on its larger one.
            String version = quiz.versions().keySet().stream().reduce((a, b) -> b).orElseThrow();
            Kind kind = index == 0 && p == 1 ? Kind.NODE
                : index >= 3 && p >= 5 ? Kind.DIGITAL : Kind.REDEEM;
            Integer pick = index == 0 && p == 1 ? pickCount : null;
            publications.add(new SamplePublication("Sample " + (index + 1) + " publication " + p, quiz.key(), version,
                kind, pick));
        }
        return new Sample("Sample " + (index + 1) + " (sample)", quizzes, publications, index == 0);
    }

    // ---- running a package ------------------------------------------------------------------------------------------

    /** What a test gives the import to call the platform with: a process by name, as a Sponsor's token. */
    @FunctionalInterface
    interface Calls {
        /** Runs the process as the Sponsor, which must succeed; {@code label} says what for, in the failure. */
        Map<String, Object> run(String process, Map<String, Object> body, String label);
    }

    /** One step planned: its key and, given what the steps before made, its input. */
    record Step(String key, Function<Map<String, Map<String, Object>>, Map<String, Object>> input) {}

    /** The time one step took, for the report. */
    record Timed(String key, long millis) {}

    /**
     * The sample's steps as the page plans them: the graph, then for each quiz its contents (the first creates the
     * quiz, the next replace the content of that quiz and save the version before), a version step for each saved
     * version, and last the publications.
     */
    static List<Step> plan(Sample sample, int batchSize, Instant startAt, Function<SamplePublication, Map<String, Object>>
        settings) {
        List<Step> steps = new ArrayList<>();
        if (sample.graph()) {
            steps.add(new Step("tree:1", results -> map("tree", map("create", map("ownerKind", "SPONSOR", "name",
                map("en", "Safety")), "nodes", List.of(
                    TreeItSupport.node(null, "src-basics", "Basics", TreeItSupport.all(TreeItSupport.passed())),
                    TreeItSupport.node(null, "src-advanced", "Advanced", TreeItSupport.all(
                        TreeItSupport.lit("src-basics", true), TreeItSupport.passed())))))));
        }
        for (SampleQuiz quiz : sample.quizzes()) {
            String first = null;
            List<Map.Entry<String, Map<String, Object>>> contents = new ArrayList<>(quiz.versions().entrySet());
            contents.add(new java.util.AbstractMap.SimpleEntry<>(null, quiz.current()));
            for (int c = 0; c < contents.size(); c++) {
                String versionKey = contents.get(c).getKey();
                Map<String, Object> file = contents.get(c).getValue();
                String base = "quiz:" + quiz.key() + ":" + (versionKey == null ? "c" : "v:" + versionKey);
                String contentKey = base + ":content";
                if (c == 0) {
                    first = contentKey;
                }
                String quizStep = first;
                boolean into = c > 0;
                if (into) {
                    // F-F11a: the content replaced is deleted in steps of CLEAR_ROWS parts, ahead of the step that
                    // brings the next (what a count that is short leaves, that step deletes).
                    int clears = (int) Math.ceil(partsOf(contents.get(c - 1).getValue()) / (double) CLEAR_ROWS);
                    for (int k = 1; k <= clears; k++) {
                        steps.add(new Step(base + ":clear:" + k, results -> map("clear", map("quizId",
                            results.get(quizStep).get("quizId"), "rows", CLEAR_ROWS))));
                    }
                }
                steps.add(new Step(contentKey, results -> {
                    Map<String, Object> quizInput = map("document", firstBatch(file, batchSize), "files", map());
                    if (into) {
                        quizInput.put("intoQuizId", results.get(quizStep).get("quizId"));
                    }
                    return map("quiz", quizInput, "quizTopics", map("topics", List.of(), "customTopics", List.of()));
                }));
                List<Map<String, Object>> rest = splitQuestions(file, batchSize);
                for (int b = 1; b < rest.size(); b++) {
                    Map<String, Object> batch = rest.get(b);
                    steps.add(new Step(base + ":batch:" + (b + 1), results -> map("questions", map("quizId",
                        results.get(quizStep).get("quizId"), "document", batch, "files", map()))));
                }
                if (versionKey != null) {
                    steps.add(new Step(base + ":version", results -> map("version", map("quizId",
                        results.get(quizStep).get("quizId")))));
                }
            }
        }
        int number = 0;
        for (SamplePublication publication : sample.publications()) {
            number++;
            SampleQuiz quiz = sample.quizzes().stream().filter(q -> q.key().equals(publication.quiz())).findFirst()
                .orElseThrow();
            String versionStep = "quiz:" + quiz.key() + ":v:" + publication.version() + ":version";
            steps.add(new Step("publication:" + number, results -> {
                Map<String, Object> save = new LinkedHashMap<>(settings.apply(publication));
                save.put("versionId", results.get(versionStep).get("versionId"));
                save.put("startAt", startAt.toString());
                save.put("endAt", startAt.plus(Duration.ofDays(30)).toString());
                if (publication.kind() == Kind.NODE) {
                    Map<String, Object> tree = results.get("tree:1");
                    @SuppressWarnings("unchecked")
                    Map<String, String> nodeIds = (Map<String, String>) tree.get("nodeIds");
                    save.put("treeId", tree.get("treeId"));
                    save.put("treeNodeId", nodeIds.get("src-basics"));
                }
                return map("publication", map("save", save));
            }));
        }
        return steps;
    }

    // ---- the settings and the run ----------------------------------------------------------------------------------

    /** The settings of a sample publication but for its version and times: what the page's draft of the package holds. */
    static Map<String, Object> settings(Sample sample, SamplePublication publication) {
        SampleQuiz quiz = sample.quizzes().stream().filter(q -> q.key().equals(publication.quiz())).findFirst()
            .orElseThrow();
        int questions = questionsOf(quiz.versions().get(publication.version())).size();
        int fullScore = publication.pickCount() == null ? questions : publication.pickCount();
        int pass = Math.max(1, fullScore / 2);
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("name", publication.name());
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 50);
        save.put("budgetCap", 10_000);
        save.put("passScore", pass);
        // A tier needs a reward above 0 (QB_TIER_NO_REWARD); the samples' prizes are in kind and cost no Kudos.
        save.put("tiers", List.of(map("fromScore", 0, "toScore", pass - 1, "reward", 0),
            map("fromScore", pass, "toScore", fullScore, "reward", 10)));
        if (publication.pickCount() != null) {
            save.put("pickCount", publication.pickCount());
        }
        switch (publication.kind()) {
            case DIGITAL -> {
                save.put("rewardMethod", RewardMethod.DIGITAL_PRIZE);
                save.put("digitalDelivery", DigitalDelivery.SHARED);
                save.put("digitalDescription", "A month of Premium (sample)");
                save.put("digitalContent", "https://example.com/premium?code=SAMPLE-ONLY");
            }
            case REDEEM, NODE -> {
                save.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
                save.put("redeemDescription", "A coffee at our counter (sample)");
            }
        }
        return save;
    }

    /** The plan of a sample as the page plans it, its publications starting at {@code startAt}. */
    static List<Step> plan(Sample sample, int batchSize, Instant startAt) {
        return plan(sample, batchSize, startAt, publication -> settings(sample, publication));
    }

    /** What an import of one package made and how long its steps took. */
    record Imported(Object importId, Map<String, Map<String, Object>> results, long millis, long slowestMillis,
        String slowestStep) {}

    /** Starts (or goes on with) the import of the package {@code hash}, planned with {@code steps} steps. */
    static Map<String, Object> start(Calls calls, String hash, int steps, int batchSize, int batchBytes) {
        return calls.run(PackageImportProcesses.START, map("packageHash", hash, "formatVersion", 2, "mode", "FULL",
            "batchSize", batchSize, "batchBytes", batchBytes, "stepsTotal", steps), "start");
    }

    /**
     * Runs the plan's steps {@code from} to {@code to} (exclusive) as the page runs them: a step recorded before is
     * skipped with the result it left. The times of the steps are summed.
     */
    static Imported runSteps(Calls calls, List<Step> plan, int from, int to, Map<String, Map<String, Object>> done,
        Object importId) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>(done);
        long total = 0;
        long slowest = 0;
        String slowestStep = "";
        for (int i = from; i < Math.min(to, plan.size()); i++) {
            Step step = plan.get(i);
            if (results.containsKey(step.key())) {
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>(step.input().apply(results));
            input.put("importId", importId);
            input.put("step", step.key());
            long started = System.nanoTime();
            Map<String, Object> out = calls.run(PackageImportProcesses.STEP, input, step.key());
            long took = Duration.ofNanos(System.nanoTime() - started).toMillis();
            total += took;
            System.out.printf("F-F11 step %s: %d ms%n", step.key(), took);
            if (took > slowest) {
                slowest = took;
                slowestStep = step.key();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) out.get("result");
            results.put(step.key(), result);
        }
        return new Imported(importId, results, total, slowest, slowestStep);
    }

    /** The rows a step of the page clears: the most the process takes. */
    static final int CLEAR_ROWS = com.jabiz.quizbuks.content.ContentLimits.CLEAR_ROWS_TECH;

    /**
     * The parts (rows) a quiz file makes: its materials, questions, options and every text, image and material it
     * translates. The page counts them so, to plan the steps that delete a content before the next replaces it.
     */
    @SuppressWarnings("unchecked")
    static int partsOf(Map<String, Object> document) {
        int parts = ((List<Object>) document.getOrDefault("materials", List.of())).size();
        for (Object question : questionsOf(document)) {
            parts += 1 + ((List<Object>) ((Map<String, Object>) question).getOrDefault("options", List.of())).size();
        }
        for (Object value : ((Map<String, Object>) document.getOrDefault("translations", Map.of())).values()) {
            Map<String, Object> t = (Map<String, Object>) value;
            parts += ((Map<String, Object>) t.getOrDefault("texts", Map.of())).size()
                + ((Map<String, Object>) t.getOrDefault("images", Map.of())).size()
                + ((List<Object>) t.getOrDefault("materials", List.of())).size();
        }
        return parts;
    }

    /** The whole import of a sample, started and run to its end. */
    static Imported importSample(Calls calls, Sample sample, String hash, int batchSize, int batchBytes,
        Instant startAt) {
        List<Step> plan = plan(sample, batchSize, startAt);
        Map<String, Object> started = start(calls, hash, plan.size(), batchSize, batchBytes);
        return runSteps(calls, plan, 0, plan.size(), Map.of(), started.get("importId"));
    }
}

package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.it.PackageFixtures.map;

/**
 * Writes the inputs of the package plan's shared cases (F-LAUNCH Q1): {@code quizbuks-web/sponsor/src/profile/
 * package-plan-cases.json}, the packages and contexts that {@code PackagePlanCasesTest} (Java) and
 * {@code package.plan.cases.test.ts} (the page) both plan. Made from {@link PackageFixtures}' samples and banks, and
 * a few hand-made packages for what they do not reach (the problems a package can have, a file no request can take,
 * numbers and escapes as JSON writes them).
 *
 * <p>Runs only when asked: {@code PACKAGE_PLAN_CASES_WRITE=1 ./gradlew :quizbuks:test --tests '*PackagePlanCasesWriterTest'}.
 * After it, the expected plans are written by the page's code ({@code PACKAGE_PLAN_CASES_UPDATE=1 pnpm vitest run
 * package.plan.cases} in {@code quizbuks-web/sponsor}), which is the reference; then both tests compare against them.
 */
@EnabledIfEnvironmentVariable(named = "PACKAGE_PLAN_CASES_WRITE", matches = "1")
class PackagePlanCasesWriterTest {

    static final Path CASES = Path.of("../../quizbuks-web/sponsor/src/profile/package-plan-cases.json");

    @Test
    void writesTheCases() throws Exception {
        List<Object> cases = new ArrayList<>();
        // The two large samples are written once and named by the cases that plan them.
        Map<String, Object> packages = new LinkedHashMap<>();
        packages.put("sample1", sample(0, 120));
        packages.put("sample2", sample(1, 40));
        cases.add(testCase("one map, one quiz of two versions and a current content, one bound publication",
            small(), context("FULL", 2, 0, 1000, fresh(1))));
        cases.add(testCase("the account has a map it submitted: bound by node key, else by name; same titles",
            small(), mapWith(context("FULL", 2, 0, 1000, map("submittedPublication", false, "submittedTree", true,
                "activeTrees", 1, "maxTrees", 1)), "quizTitles", List.of("  Coffee  ", "Other"), "ownNodes",
                List.of(map("treeId", "own-tree", "nodeId", "own-n9", "name", map("en", "Basics"))))));
        cases.add(testCase("maps over the limit, a quiz over the bank's limit, an orphan and a video not shared",
            crowded(), context("FULL", 3, 0, 4, fresh(1))));
        cases.add(testCase("only the profile part", small(), context("PROFILE", 2, 0, 1000, fresh(1))));
        cases.add(testCase("version 1: no maps, nodes, topics or shared videos", versionOne(),
            context("FULL", 2, 0, 1000, fresh(1))));
        cases.add(testCase("not a package", map("format", "quizbuks.quiz", "version", 2), context("FULL", 2, 0, 1000,
            fresh(1))));
        cases.add(testCase("a newer version", map("format", "quizbuks.sponsor-package", "version", 3, "quizzes",
            List.of(), "publications", List.of()), context("FULL", 2, 0, 1000, fresh(1))));
        cases.add(testCase("damaged places", damaged(), context("FULL", 2, 0, 1000, fresh(1))));
        cases.add(testCase("sample 1 (a large bank in two versions and the current content, a map), by questions",
            "@sample1", context("FULL", 25, 0, 2000, fresh(1))));
        cases.add(testCase("sample 1, cut by bytes too", "@sample1", context("FULL", 50, 24_000, 2000, fresh(1))));
        cases.add(testCase("sample 2 (videos), by questions", "@sample2", context("FULL", 15, 0, 2000, fresh(1))));
        cases.add(testCase("a file no request takes: the heading and materials, and one question, over the bytes",
            oversized(), context("FULL", 10, 9_000, 1000, fresh(1))));
        cases.add(testCase("numbers and escapes as JSON writes them, cut by bytes", escapes(),
            context("FULL", 4, 1_500, 1000, fresh(1))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("about", "F-LAUNCH Q1: packages the page's planImport and the server's PackagePlan both plan. Written by "
            + "PackagePlanCasesWriterTest; the expected plans are in package-plan-expected.json.");
        out.put("packages", packages);
        out.put("cases", cases);
        JsonMapper json = JsonMapper.builder().build();
        Files.writeString(CASES, escapeLoneSurrogates(json.writeValueAsString(out)) + "\n");
    }

    /** A lone surrogate (in a case's strings) written as its escape: UTF-8 cannot carry it, JSON's escape can. */
    private static String escapeLoneSurrogates(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                out.append(c).append(text.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---- packages ---------------------------------------------------------------------------------------------------

    /** A case; a package given as {@code "@<name>"} is one of the file's {@code packages}. */
    private static Map<String, Object> testCase(String name, Object pkg, Map<String, Object> context) {
        return pkg instanceof String ref && ref.startsWith("@")
            ? map("name", name, "packageRef", ref.substring(1), "context", context)
            : map("name", name, "package", pkg, "context", context);
    }

    private static Map<String, Object> context(String mode, int batchSize, long batchBytes, int maxQuestions,
        Map<String, Object> state) {
        return map("mode", mode, "batchSize", batchSize, "batchBytes", batchBytes, "maxQuestions", maxQuestions,
            "state", state, "quizTitles", List.of(), "ownNodes", List.of(), "sharedVideos", List.of());
    }

    private static Map<String, Object> mapWith(Map<String, Object> map, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> fresh(int maxTrees) {
        return map("submittedPublication", false, "submittedTree", false, "activeTrees", 0, "maxTrees", maxTrees);
    }

    private static Map<String, Object> pkg(int version, Object profile, List<Object> trees, List<Object> quizzes,
        List<Object> publications) {
        Map<String, Object> out = map("format", "quizbuks.sponsor-package", "version", version, "exportedAt",
            "2026-11-18T00:00:00Z", "profile", profile, "trees", trees, "quizzes", quizzes, "publications",
            publications);
        if (version < 2) {
            out.remove("trees");
        }
        return out;
    }

    /** A plain quiz file: {@code count} two-option questions. */
    private static Map<String, Object> quizFile(String title, int count) {
        List<Object> questions = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            questions.add(map("stem", "Q" + i + "?", "image", null, "points", 1, "selection", null, "options", List.of(
                map("text", "Yes", "image", null, "correct", true), map("text", "No", "image", null, "correct", false))));
        }
        return map("format", "quizbuks.quiz", "formatVersion", 3, "exportedAt", "2026-11-18T00:00:00Z",
            "quiz", map("kind", "QUIZ", "title", title, "intro", null, "timeLimitSec", null, "cover", null,
                "language", "en"), "materials", List.of(), "questions", questions, "files", map());
    }

    static Map<String, Object> settings(String name, Object... more) {
        Map<String, Object> out = map("format", "quizbuks.publication-settings", "version", 1, "name", name,
            "fullScore", 3, "durationDays", 7, "passScore", 2, "budgetCap", 1000, "maxWinners", 10, "tiers",
            List.of(map("fromScore", 0, "toScore", 3, "reward", 10)), "rewardMethod", "PLATFORM", "audience", "PUBLIC",
            "regions", List.of("GLOBAL"), "languages", List.of(), "translations", map(), "conditions", List.of());
        return mapWith(out, more);
    }

    private static Map<String, Object> coffeeTree() {
        return map("key", "t-src", "name", map("en", "Coffee"), "description", null, "nodes", List.of(
            map("key", "n1", "name", map("en", "Basics"), "description", null, "condition",
                map("op", "ALL", "items", List.of(map("passed", true)))),
            map("key", "n2", "name", map("en", "Advanced"), "description", null, "condition",
                map("op", "ALL", "items", List.of(map("node", "n1", "beforeAnswer", true), map("passed", true))))));
    }

    /** The page's own test package: one map, quiz q1 (v1 of 3 questions, v2 of 1, current of 2), one publication. */
    private static Map<String, Object> small() {
        return pkg(2, map("format", "quizbuks-profile", "version", 1), List.of(coffeeTree()), List.of(
            map("key", "q1", "versions", List.of(
                map("key", "q1v1", "label", "v1", "file", quizFile("Coffee", 3), "topics", List.of("coffee"),
                    "customTopics", List.of()),
                map("key", "q1v2", "label", "v2", "file", quizFile("Coffee", 1), "topics", List.of(),
                    "customTopics", List.of("beans"))),
                "current", quizFile("Coffee", 2), "topics", List.of("tea"), "customTopics", List.of())),
            List.of(map("quiz", "q1", "version", "q1v2", "settings", settings("Morning quiz"),
                "node", map("tree", "t-src", "node", "n2", "name", map("en", "Advanced")),
                "topics", map("add", List.of("coffee"), "remove", List.of(), "customAdd", List.of(), "customRemove",
                    List.of()), "sharedVideo", null),
                map("quiz", "q1", "version", "q1v1", "settings", settings("Unbound by key", "audience", "INVITED"),
                    "node", map("tree", "t-gone", "node", "own-n9", "name", null)),
                map("quiz", null, "version", null, "settings", settings("By name"),
                    "node", map("tree", "t-gone", "node", "nx", "name", map("en", " Basics ", "ja", "基礎")))));
    }

    /** Two maps (one over the limit), a quiz over the bank's limit, a publication of it, a video not shared. */
    private static Map<String, Object> crowded() {
        Map<String, Object> second = new LinkedHashMap<>(coffeeTree());
        second.put("key", "t-two");
        second.put("name", map("zh", "", "en", "Tea"));
        return pkg(2, null, List.of(coffeeTree(), second, map("key", "t-three", "name", map(), "nodes", List.of())),
            List.of(
                map("key", "big", "versions", List.of(), "current", quizFile("Big", 5)),
                map("key", "ok", "versions", List.of(map("key", "okv1", "file", quizFile("Fine", 4))), "current",
                    null)),
            List.of(map("quiz", "big", "version", null, "settings", settings("On the big one")),
                map("quiz", "ok", "version", "okv1", "settings", settings("Shared", "rewardMethod", "SPONSOR_CASH"),
                    "sharedVideo", "video-1", "node", map("tree", "t-two", "node", "n1", "name", null)),
                map("quiz", "ok", "version", "okv9", "settings", settings("Wrong version"))));
    }

    private static Map<String, Object> versionOne() {
        return pkg(1, null, List.of(), List.of(map("key", "q1", "versions", List.of(map("key", "q1v1", "file",
            quizFile("Old", 3), "topics", List.of("ignored"))), "current", quizFile("Old", 3), "topics",
            List.of("ignored")), map("key", "q2", "versions", List.of(), "current", quizFile("Second", 1))),
            List.of(map("quiz", "q1", "version", "q1v1", "settings", settings("Old one"), "node",
                map("tree", "t", "node", "n"), "sharedVideo", "v")));
    }

    private static Map<String, Object> damaged() {
        Map<String, Object> out = pkg(2, "a profile", List.of(), List.of(), List.of());
        out.put("trees", List.of(map("key", "t1", "name", map("en", "T"), "nodes", List.of(map("key", "n1"),
            map("key", "n2", "name", map(), "condition", map()))), "not a tree"));
        out.put("quizzes", List.of(map("key", "q1", "versions", List.of(map("key", "v1"))),
            map("key", 2, "versions", List.of()), map("key", "q3", "versions", List.of(), "current", "text")));
        out.put("publications", List.of(map("settings", map("format", "other")), 7, map("settings",
            settings("Fine"))));
        return out;
    }

    /** A sample of {@link PackageFixtures} as a package (its settings files made from its publications). */
    private static Map<String, Object> sample(int index, int bigBank) {
        PackageFixtures.Sample sample = PackageFixtures.sample(index, bigBank, 10);
        List<Object> quizzes = new ArrayList<>();
        for (PackageFixtures.SampleQuiz quiz : sample.quizzes()) {
            List<Object> versions = new ArrayList<>();
            quiz.versions().forEach((key, file) -> versions.add(map("key", key, "label", key, "file", file)));
            quizzes.add(map("key", quiz.key(), "versions", versions, "current", quiz.current()));
        }
        List<Object> publications = new ArrayList<>();
        for (PackageFixtures.SamplePublication publication : sample.publications()) {
            Map<String, Object> p = map("quiz", publication.quiz(), "version", publication.version(), "settings",
                settings(publication.name(), "pickCount", publication.pickCount(), "rewardMethod",
                    publication.kind() == PackageFixtures.Kind.DIGITAL ? "DIGITAL_PRIZE" : "OFFLINE_REDEEM",
                    "redeemDescription", "A coffee at the counter"));
            if (publication.kind() == PackageFixtures.Kind.NODE) {
                p.put("node", map("tree", "graph", "node", "src-basics", "name", map("en", "Basics")));
            }
            publications.add(p);
        }
        List<Object> trees = sample.graph() ? List.of(map("key", "graph", "name", map("en", "Safety"), "nodes",
            List.of(map("key", "src-basics", "name", map("en", "Basics"), "condition", map("op", "ALL", "items",
                List.of(map("passed", true))))))) : List.of();
        return pkg(2, null, trees, quizzes, publications);
    }

    /** Large materials (in three languages) and one question too long for any request of 9,000 bytes. */
    private static Map<String, Object> oversized() {
        Map<String, Object> file = quizFile("Heavy", 12);
        List<Object> materials = new ArrayList<>();
        for (int m = 1; m <= 3; m++) {
            materials.add(map("no", m, "kind", "ARTICLE", "title", "Article " + m, "body", "記".repeat(1200),
                "images", List.of("img-" + m)));
        }
        file.put("materials", materials);
        @SuppressWarnings("unchecked")
        List<Object> questions = (List<Object>) file.get("questions");
        @SuppressWarnings("unchecked")
        Map<String, Object> seventh = new LinkedHashMap<>((Map<String, Object>) questions.get(6));
        seventh.put("stem", "長".repeat(4000));
        seventh.put("image", "q7-image");
        questions.set(6, seventh);
        file.put("files", map("img-1", map("type", "image/png", "data", "AAAA"), "q7-image", map("type",
            "image/png", "data", "BBBB"), "unused", map("type", "image/png")));
        file.put("translations", map("ja", map("texts", map("title", "重い", "q3.stem", "三", "q11.stem", "十一"),
            "images", map("q7.image", "q7-image"), "materials", List.of(map("no", 1, "body", "日".repeat(300))),
            "hashes", map("q3.stem", "h3"))));
        file.put("products", map("quiz", List.of("p1"), "questions", List.of(map("no", 2, "product", "p2"),
            map("no", 11, "product", "p3"), map("no", "x"))));
        return pkg(2, null, List.of(), List.of(map("key", "heavy", "versions", List.of(), "current", file)),
            List.of());
    }

    /** Numbers and strings whose JSON differs between writers (the request's bytes count them as JavaScript does). */
    private static Map<String, Object> escapes() {
        Map<String, Object> file = quizFile("Escapes \"quoted\" \\ back\tslash", 9);
        @SuppressWarnings("unchecked")
        Map<String, Object> quiz = (Map<String, Object>) file.get("quiz");
        quiz.put("intro", "line\nbreak \u0001 control, été, 😀 and a lone \ud800 surrogate");
        quiz.put("weights", List.of(1.0, 2.50, 0.1, 1e21, 1.5e-7, 123456789.125, -0.0, 3));
        @SuppressWarnings("unchecked")
        List<Object> questions = (List<Object>) file.get("questions");
        for (int i = 0; i < questions.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> q = new LinkedHashMap<>((Map<String, Object>) questions.get(i));
            q.put("stem", "Question " + (i + 1) + " — «" + "ü".repeat(40 * (i + 1)) + "»   ok");
            q.put("points", i % 2 == 0 ? 1.0 : 2);
            questions.set(i, q);
        }
        return pkg(2, null, List.of(), List.of(map("key", "esc", "versions", List.of(map("key", "e1", "label",
            "v1", "file", file)), "current", null)), List.of(map("quiz", "esc", "version", "e1", "settings",
            settings("Escapes", "fullScore", 13.0, "tiers", List.of(map("fromScore", 0.0, "toScore", 13, "reward",
                1.5), "bad"), "languages", List.of("en", "ja", "xx"), "translations", map("ja", map("name", "名",
                "other", "x"), "en", map()), "conditions", List.of(map("key", "age", "min", 20, "max", 30.5, "options",
                List.of("a", 1)), map("noKey", true)), "referral", map("quota", 5, "method", "BAD", "refereeRules",
                List.of("NEW_TO_PLATFORM", "NOT_REGULAR", "X"))))));
    }
}

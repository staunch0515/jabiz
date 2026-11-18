package com.jabiz.quizbuks.sponsorpackage;

import com.jabiz.quizbuks.sponsorpackage.PackagePlan.Binding;
import com.jabiz.quizbuks.sponsorpackage.PackagePlan.Step;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.jabiz.quizbuks.sponsorpackage.PackageJson.array;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.object;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-LAUNCH Q1 (plan test item 16): {@link PackagePlan} plans every shared case as the sponsor page's {@code planImport}
 * does. The cases ({@code package-plan-cases.json}) and the page's plans of them ({@code package-plan-expected.json},
 * the reference, written by {@code package.plan.cases.test.ts}) are in {@code quizbuks-web/sponsor/src/profile}; the
 * summary is made here as there: a document is its question count, its bytes in a request and a digest of its JSON
 * with the keys sorted, every other part is written out.
 */
class PackagePlanCasesTest {

    private static final Path DIR = Path.of("../../quizbuks-web/sponsor/src/profile");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestFactory
    Stream<DynamicTest> plansEveryCaseAsThePage() throws Exception {
        Map<String, Object> file = object(JSON.readValue(Files.readString(DIR.resolve("package-plan-cases.json")),
            Object.class));
        List<Object> expected = array(object(JSON.readValue(
            Files.readString(DIR.resolve("package-plan-expected.json")), Object.class)).get("cases"));
        List<Object> cases = array(file.get("cases"));
        assertThat(expected).hasSize(cases.size());
        Map<String, Object> packages = object(file.get("packages"));
        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> planCase = object(cases.get(i));
            Map<String, Object> want = object(expected.get(i));
            String name = (String) planCase.get("name");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                assertThat(want.get("name")).isEqualTo(name);
                Object summary = summaryOf(planCase, packages);
                assertThat(PackageJson.canonical(summary)).isEqualTo(PackageJson.canonical(want.get("summary")));
            }));
        }
        return tests.stream();
    }

    @Test
    void readsTheCasesTheWriterWrote() throws Exception {
        // The inputs keep what JSON writers differ in: the request's bytes must count them as the page does.
        String cases = Files.readString(DIR.resolve("package-plan-cases.json"));
        assertThat(cases).contains("1.0E21", "-0.0", "\\ud800", "\\u0001");
    }

    // ---- the summary (as package.plan.cases.test.ts makes it) -------------------------------------------------------

    static Object summaryOf(Map<String, Object> planCase, Map<String, Object> packages) {
        Object value = planCase.containsKey("packageRef") ? packages.get((String) planCase.get("packageRef"))
            : planCase.get("package");
        PackagePlan.Read read = PackagePlan.read(value);
        if (read.problem() != null) {
            return map("problem", switch (read.problem()) {
                case NOT_PACKAGE -> "notPackage";
                case NEWER_VERSION -> "newerVersion";
                case SHAPE -> "shape";
            }, "places", read.places() == null ? List.of() : read.places());
        }
        PackagePlan.Context context = contextOf(object(planCase.get("context")));
        PackagePlan.Planned planned = PackagePlan.plan(read.document(), context);
        return map("plan", planSummary(planned, inputsOf(planned, read.document(), context.sharedVideos())));
    }

    /** A publication's start in the cases (the page takes the next hour). */
    private static final Instant START_AT = Instant.parse("2026-12-01T00:00:00Z");

    /** Each step's input, with what the earlier steps would have given: a publication's written out, the others' a digest. */
    private static List<Object> inputsOf(PackagePlan.Planned planned, PackagePlan.Document document,
        Set<String> sharedVideos) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        List<Object> inputs = new ArrayList<>();
        for (Step step : planned.steps()) {
            Map<String, Object> input = PackageStepInputs.inputOf(step, document, results, sharedVideos, START_AT,
                uploadsOf(step));
            results.put(step.key(), resultOf(step));
            inputs.add(step instanceof Step.PublicationStep ? input : PackageJson.digest(input));
        }
        return inputs;
    }

    /** What a step would have given the later ones: ids named after it. */
    private static Map<String, Object> resultOf(Step step) {
        return switch (step) {
            case Step.TreeStep s -> {
                Map<String, Object> nodeIds = new LinkedHashMap<>();
                s.tree().nodes().forEach(n -> nodeIds.put(n.key(), "node:" + s.key() + "/" + n.key()));
                yield map("treeId", "tree:" + s.key(), "nodeIds", nodeIds);
            }
            case Step.Content s -> map("quizId", "quiz:" + s.key());
            case Step.Version s -> map("versionId", "version:" + s.key());
            default -> map("done", step.key());
        };
    }

    /** The uploads a step would have: the files' keys it uses, a token for the profile's images. */
    private static PackageStepInputs.Uploads uploadsOf(Step step) {
        return switch (step) {
            case Step.Content s -> new PackageStepInputs.Uploads(new ArrayList<>(QuizFileCut.usedFiles(s.document())),
                null);
            case Step.Batch s -> new PackageStepInputs.Uploads(new ArrayList<>(QuizFileCut.usedFiles(s.document())),
                null);
            case Step.Profile s -> new PackageStepInputs.Uploads("profile-images", null);
            default -> PackageStepInputs.Uploads.NONE;
        };
    }

    private static PackagePlan.Context contextOf(Map<String, Object> c) {
        Map<String, Object> s = object(c.get("state"));
        List<PackagePlan.OwnNode> ownNodes = new ArrayList<>();
        for (Object n : array(c.get("ownNodes"))) {
            Map<String, Object> node = object(n);
            ownNodes.add(new PackagePlan.OwnNode((String) node.get("treeId"), (String) node.get("nodeId"),
                object(node.get("name"))));
        }
        List<String> titles = new ArrayList<>();
        array(c.get("quizTitles")).forEach(t -> titles.add((String) t));
        LinkedHashSet<String> videos = new LinkedHashSet<>();
        array(c.get("sharedVideos")).forEach(v -> videos.add((String) v));
        return new PackagePlan.Context(PackagePlan.Mode.valueOf((String) c.get("mode")),
            ((Number) c.get("batchSize")).intValue(), ((Number) c.get("batchBytes")).longValue(),
            ((Number) c.get("maxQuestions")).intValue(),
            new PackagePlan.AccountState((Boolean) s.get("submittedPublication"), (Boolean) s.get("submittedTree"),
                ((Number) s.get("activeTrees")).intValue(), ((Number) s.get("maxTrees")).intValue()),
            titles, ownNodes, videos);
    }

    private static Object planSummary(PackagePlan.Planned p, List<Object> inputs) {
        List<Object> oversized = new ArrayList<>();
        p.oversized().forEach(o -> oversized.add(map("title", o.title(), "question", o.question())));
        List<Object> steps = new ArrayList<>();
        for (int i = 0; i < p.steps().size(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) stepSummary(p.steps().get(i));
            summary.put("input", inputs.get(i));
            steps.add(summary);
        }
        PackagePlan.Makes m = p.makes();
        return map("mode", p.mode().name(), "batchSize", p.batchSize(), "batchBytes", p.batchBytes(),
            "makes", map("profile", m.profile(), "trees", m.trees(), "quizzes", m.quizzes(), "versions", m.versions(),
                "publications", m.publications()),
            "sameTitles", p.sameTitles(), "treesSkipped", p.treesSkipped(), "treesKept", p.treesKept(),
            "unbound", p.unbound(), "videosAgain", p.videosAgain(), "tooLarge", p.tooLarge(),
            "oversized", oversized, "orphans", p.orphans(), "steps", steps);
    }

    private static Object documentSummary(Map<String, Object> document) {
        return map("questions", QuizFileCut.questionsOf(document).size(), "bytes", PackageJson.bytesOf(document),
            "digest", PackageJson.digest(document));
    }

    private static Object stepSummary(Step step) {
        return switch (step) {
            case Step.Profile s -> map("kind", s.kind(), "key", s.key());
            case Step.TreeStep s -> {
                List<Object> nodes = new ArrayList<>();
                s.tree().nodes().forEach(n -> nodes.add(map("key", n.key(), "name", n.name(), "description",
                    n.description(), "condition", n.condition())));
                yield map("kind", s.kind(), "key", s.key(), "treeKey", s.tree().key(), "tree", PackageJson.digest(
                    map("key", s.tree().key(), "name", s.tree().name(), "description", s.tree().description(),
                        "nodes", nodes)));
            }
            case Step.Content s -> map("kind", s.kind(), "key", s.key(), "quiz", s.quiz(), "label", s.label(),
                "title", s.title(), "check", s.check(), "into", s.into(), "topics", s.topics(), "customTopics",
                s.customTopics(), "clears", s.clears(), "document", documentSummary(s.document()));
            case Step.Clear s -> map("kind", s.kind(), "key", s.key(), "quiz", s.quiz(), "label", s.label(),
                "title", s.title(), "clear", s.clear(), "clears", s.clears(), "quizStep", s.quizStep(), "content",
                s.content());
            case Step.Batch s -> map("kind", s.kind(), "key", s.key(), "quiz", s.quiz(), "label", s.label(),
                "title", s.title(), "check", s.check(), "batch", s.batch(), "batches", s.batches(), "quizStep",
                s.quizStep(), "clears", s.clears(), "document", documentSummary(s.document()));
            case Step.Version s -> map("kind", s.kind(), "key", s.key(), "quiz", s.quiz(), "label", s.label(),
                "title", s.title(), "versionKey", s.versionKey(), "quizStep", s.quizStep());
            case Step.PublicationStep s -> {
                PackagePlan.Publication p = s.publication();
                Object node = p.node() == null ? null
                    : map("tree", p.node().tree(), "node", p.node().node(), "name", p.node().name());
                Object topics = p.topics() == null ? null : map("add", p.topics().add(), "remove", p.topics().remove(),
                    "customAdd", p.topics().customAdd(), "customRemove", p.topics().customRemove());
                yield map("kind", s.kind(), "key", s.key(), "index", s.index(), "name", s.name(), "quizStep",
                    s.quizStep(), "versionStep", s.versionStep(), "node", bindingOf(s.node()), "publication",
                    PackageJson.digest(map("quiz", p.quiz(), "version", p.version(), "settings", p.settings(),
                        "node", node, "topics", topics, "sharedVideo", p.sharedVideo())));
            }
        };
    }

    private static Object bindingOf(Binding binding) {
        return switch (binding) {
            case null -> null;
            case Binding.ToMade made -> map("treeStep", made.treeStep(), "nodeKey", made.nodeKey());
            case Binding.ToOwn own -> map("treeId", own.treeId(), "nodeId", own.nodeId());
        };
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}

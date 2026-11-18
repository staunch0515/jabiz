package com.jabiz.quizbuks.sponsorpackage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.quizbuks.sponsorpackage.PackageJson.isInteger;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.isObject;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.jsTrim;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.object;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.truthy;

/**
 * The sponsor package's import plan in Java (F-LAUNCH Q1, docs/quizbuks/plans/impl/F-LAUNCH.md §1.2, §12.2): what the
 * sponsor page's {@code profile/package.ts} does in {@code readPackage} and {@code planImport}, for the server to run a
 * package without a browser (the first launch's content). Pure and synchronous: it reads JSON values (as Jackson reads
 * untyped JSON) and gives the steps, each with the key the import record knows it by ({@code QB_PACKAGE_IMPORT_STEP})
 * and, for a content, the quiz file cut for its request.
 *
 * <p>The two are kept the same by the cases both sides run ({@code quizbuks-web/sponsor/src/profile/
 * package-plan-cases.json}, {@code PackagePlanCasesTest} and {@code package.plan.cases.test.ts}): a change of the
 * package's format or of the plan is made in both, each naming the other.
 */
public final class PackagePlan {

    public static final String FORMAT = "quizbuks.sponsor-package";
    /** The newest version read (version 1 has no maps, nodes, topics or shared videos). */
    public static final int VERSION = 2;

    private PackagePlan() {}

    // ---- the package ------------------------------------------------------------------------------------------------

    /** Texts by language, as written. */
    public record Node(String key, Map<String, Object> name, Map<String, Object> description,
        Map<String, Object> condition) {}

    public record Tree(String key, Map<String, Object> name, Map<String, Object> description, List<Node> nodes) {}

    /** {@code topics} / {@code customTopics} null: not in the package (version 1, or not a list). */
    public record QuizVersion(String key, String label, Map<String, Object> file, List<String> topics,
        List<String> customTopics) {}

    /** {@code current} null: the current content is the last version's. */
    public record Quiz(String key, List<QuizVersion> versions, Map<String, Object> current, List<String> topics,
        List<String> customTopics) {}

    public record NodeRef(String tree, String node, Map<String, Object> name) {}

    public record TopicChanges(List<String> add, List<String> remove, List<String> customAdd,
        List<String> customRemove) {
        boolean any() {
            return add.size() + remove.size() + customAdd.size() + customRemove.size() > 0;
        }
    }

    /** {@code settings}: as {@link PublicationSettingsFile#parse} reads them. */
    public record Publication(String quiz, String version, Map<String, Object> settings, NodeRef node,
        TopicChanges topics, String sharedVideo) {
        public String name() {
            return settings.get("name") instanceof String name ? name : "";
        }
    }

    public record Document(int version, String exportedAt, Map<String, Object> profile, List<Tree> trees,
        List<Quiz> quizzes, List<Publication> publications) {}

    /** Why a value is not a package this reads; {@code SHAPE} comes with the places found wrong. */
    public enum Problem { NOT_PACKAGE, NEWER_VERSION, SHAPE }

    /** The package read, or why not. */
    public record Read(Document document, Problem problem, List<String> places) {}

    // ---- reading ----------------------------------------------------------------------------------------------------

    /**
     * Reads a package of version 1 or 2: only the shapes are checked (the quiz files and the profile part are checked
     * by the server's own processes before anything is imported). A version 1 package reads as one without maps,
     * nodes, topics or shared videos.
     */
    public static Read read(Object value) {
        if (!isObject(value) || !FORMAT.equals(object(value).get("format"))
            || !isInteger(object(value).get("version")) || ((Number) object(value).get("version")).doubleValue() < 1) {
            return new Read(null, Problem.NOT_PACKAGE, List.of());
        }
        Map<String, Object> v = object(value);
        double versionNumber = ((Number) v.get("version")).doubleValue();
        if (versionNumber > VERSION) {
            return new Read(null, Problem.NEWER_VERSION, List.of());
        }
        int version = (int) versionNumber;
        List<String> places = new ArrayList<>();
        boolean v2 = version >= 2;
        Map<String, Object> profile = null;
        if (v.get("profile") != null) {
            if (isObject(v.get("profile"))) {
                profile = object(v.get("profile"));
            } else {
                places.add("profile");
            }
        }
        List<Tree> trees = new ArrayList<>();
        if (v2 && v.get("trees") != null) {
            if (!(v.get("trees") instanceof List<?> list)) {
                places.add("trees");
            } else {
                for (int i = 0; i < list.size(); i++) {
                    Object tree = list.get(i);
                    if (!isObject(tree) || !(object(tree).get("key") instanceof String key)
                        || !isObject(object(tree).get("name")) || !(object(tree).get("nodes") instanceof List<?> nodes)) {
                        places.add("trees[" + i + "]");
                        continue;
                    }
                    List<Node> read = new ArrayList<>();
                    for (int j = 0; j < nodes.size(); j++) {
                        Object node = nodes.get(j);
                        if (!isObject(node) || !(object(node).get("key") instanceof String nodeKey)
                            || !isObject(object(node).get("name")) || !isObject(object(node).get("condition"))) {
                            places.add("trees[" + i + "].nodes[" + j + "]");
                            continue;
                        }
                        Map<String, Object> n = object(node);
                        read.add(new Node(nodeKey, object(n.get("name")), objectOrNull(n.get("description")),
                            object(n.get("condition"))));
                    }
                    Map<String, Object> t = object(tree);
                    trees.add(new Tree(key, object(t.get("name")), objectOrNull(t.get("description")), read));
                }
            }
        }
        List<Quiz> quizzes = new ArrayList<>();
        if (!(v.get("quizzes") instanceof List<?> list)) {
            places.add("quizzes");
        } else {
            for (int i = 0; i < list.size(); i++) {
                Object quiz = list.get(i);
                if (!isObject(quiz) || !(object(quiz).get("key") instanceof String key)
                    || !(object(quiz).get("versions") instanceof List<?> versions)
                    || !(object(quiz).get("current") == null || isObject(object(quiz).get("current")))) {
                    places.add("quizzes[" + i + "]");
                    continue;
                }
                Map<String, Object> q = object(quiz);
                List<QuizVersion> read = new ArrayList<>();
                for (int j = 0; j < versions.size(); j++) {
                    Object version1 = versions.get(j);
                    if (!isObject(version1) || !(object(version1).get("key") instanceof String versionKey)
                        || !isObject(object(version1).get("file"))) {
                        places.add("quizzes[" + i + "].versions[" + j + "]");
                        continue;
                    }
                    Map<String, Object> ver = object(version1);
                    read.add(new QuizVersion(versionKey, ver.get("label") instanceof String label ? label : versionKey,
                        object(ver.get("file")), v2 ? strings(ver.get("topics")) : null,
                        v2 ? strings(ver.get("customTopics")) : null));
                }
                if (read.isEmpty() && q.get("current") == null) {
                    places.add("quizzes[" + i + "].current");
                }
                quizzes.add(new Quiz(key, read, objectOrNull(q.get("current")), v2 ? strings(q.get("topics")) : null,
                    v2 ? strings(q.get("customTopics")) : null));
            }
        }
        List<Publication> publications = new ArrayList<>();
        if (!(v.get("publications") instanceof List<?> list)) {
            places.add("publications");
        } else {
            for (int i = 0; i < list.size(); i++) {
                Object publication = list.get(i);
                if (!isObject(publication)) {
                    places.add("publications[" + i + "]");
                    continue;
                }
                Map<String, Object> p = object(publication);
                PublicationSettingsFile.Read settings = PublicationSettingsFile.parse(p.get("settings"));
                if (settings.problem() != null) {
                    places.add("publications[" + i + "].settings");
                    continue;
                }
                NodeRef node = null;
                if (v2 && isObject(p.get("node")) && object(p.get("node")).get("tree") instanceof String tree
                    && object(p.get("node")).get("node") instanceof String nodeKey) {
                    node = new NodeRef(tree, nodeKey, objectOrNull(object(p.get("node")).get("name")));
                }
                TopicChanges topics = null;
                if (v2 && isObject(p.get("topics"))) {
                    Map<String, Object> t = object(p.get("topics"));
                    topics = new TopicChanges(orEmpty(strings(t.get("add"))), orEmpty(strings(t.get("remove"))),
                        orEmpty(strings(t.get("customAdd"))), orEmpty(strings(t.get("customRemove"))));
                }
                publications.add(new Publication(p.get("quiz") instanceof String quiz ? quiz : null,
                    p.get("version") instanceof String version1 ? version1 : null, settings.settings(), node, topics,
                    v2 && p.get("sharedVideo") instanceof String video ? video : null));
            }
        }
        if (!places.isEmpty()) {
            return new Read(null, Problem.SHAPE, List.copyOf(places));
        }
        return new Read(new Document(version, v.get("exportedAt") instanceof String at ? at : "", profile, trees,
            quizzes, publications), null, List.of());
    }

    private static Map<String, Object> objectOrNull(Object value) {
        return isObject(value) ? object(value) : null;
    }

    /** The strings of a list (others left out), or null when the value is not a list. */
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        list.forEach(item -> {
            if (item instanceof String text) {
                out.add(text);
            }
        });
        return out;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    // ---- planning ---------------------------------------------------------------------------------------------------

    /** The import's way: the whole package, or only its profile part (an account that submitted a publication). */
    public enum Mode { FULL, PROFILE }

    /** What the import needs to know of the account ({@code qb.sponsor.package-state}). */
    public record AccountState(boolean submittedPublication, boolean submittedTree, int activeTrees, int maxTrees) {
        /** A new account: nothing submitted, no map, room for {@code maxTrees}. */
        public static AccountState fresh(int maxTrees) {
            return new AccountState(false, false, 0, maxTrees);
        }
    }

    /** A node in effect of one of the account's own maps (bound to by node key, else by name). */
    public record OwnNode(String treeId, String nodeId, Map<String, Object> name) {}

    /**
     * @param batchSize the questions one request takes ({@code qb.content.import.batch-size})
     * @param batchBytes the bytes one request takes at most; 0: only the questions are counted
     * @param maxQuestions the bank's limit of questions
     * @param quizTitles the titles of the account's quizzes (same titles are only pointed out)
     * @param ownNodes the nodes in effect of the account's maps, when it has a map it submitted
     * @param sharedVideos the shared videos of this environment's catalog the package names that are shared now
     */
    public record Context(Mode mode, int batchSize, long batchBytes, int maxQuestions, AccountState state,
        List<String> quizTitles, List<OwnNode> ownNodes, Set<String> sharedVideos) {}

    /** Where a publication is bound: a node of a map this import makes, or a node in effect of the account's own. */
    public sealed interface Binding {
        record ToMade(String treeStep, String nodeKey) implements Binding {}

        record ToOwn(String treeId, String nodeId) implements Binding {}
    }

    /** One step of the import, in the order run; {@code key} is the import record's. */
    public sealed interface Step {
        String key();

        String kind();

        record Profile(String key) implements Step {
            public String kind() {
                return "profile";
            }
        }

        record TreeStep(String key, Tree tree) implements Step {
            public String kind() {
                return "tree";
            }
        }

        /**
         * A content: the first quiz's creates it ({@code into} null), a later one replaces it ({@code into} the first
         * content's step).
         *
         * @param check the content's check id ({@code <quiz key>:<version key|current>})
         * @param clears the steps that clear the content this one replaces, before it (0 for the first content)
         */
        record Content(String key, String quiz, String label, String title, String check, Map<String, Object> document,
            String into, List<String> topics, List<String> customTopics, int clears) implements Step {
            public String kind() {
                return "content";
            }
        }

        /** @param content the step that brings the content these clear the way for */
        record Clear(String key, String quiz, String label, String title, int clear, int clears, String quizStep,
            String content) implements Step {
            public String kind() {
                return "clear";
            }
        }

        /** The questions after a content's first step (numbered from 2, as the server keys them). */
        record Batch(String key, String quiz, String label, String title, String check, int batch, int batches,
            Map<String, Object> document, String quizStep, int clears) implements Step {
            public String kind() {
                return "batch";
            }
        }

        record Version(String key, String quiz, String label, String title, String versionKey, String quizStep)
            implements Step {
            public String kind() {
                return "version";
            }
        }

        record PublicationStep(String key, int index, String name, Publication publication, String quizStep,
            String versionStep, Binding node) implements Step {
            public String kind() {
                return "publication";
            }
        }
    }

    public record Makes(boolean profile, int trees, int quizzes, int versions, int publications) {}

    /** A content no request can take within the byte limit: a question (its number) or the heading and materials (null). */
    public record Oversized(String title, Integer question) {}

    /**
     * The steps and what the check page says of them.
     *
     * @param sameTitles quizzes of the package whose title the account has already
     * @param treesSkipped maps not imported (over the account's limit)
     * @param treesKept maps not imported because the account has one submitted
     * @param unbound publications bound in the package that the import leaves unbound, by name
     * @param videosAgain publications whose shared video this environment does not share: chosen again
     * @param tooLarge quizzes over the bank's limit (not importable), by title
     * @param oversized what no request can take: the import does not start until the file is mended
     * @param orphans publications naming a quiz or version the package lacks (not imported)
     */
    public record Planned(Mode mode, int batchSize, long batchBytes, List<Step> steps, Makes makes,
        List<String> sameTitles, List<String> treesSkipped, boolean treesKept, List<String> unbound,
        List<String> videosAgain, List<String> tooLarge, List<Oversized> oversized, List<String> orphans) {}

    /**
     * The steps of the import, in order: the profile; the maps (only when the account has none it submitted, within
     * its limit); each quiz's versions oldest first (the content, the clearing of the content it replaces before it,
     * further batches of a large bank, the version), then its current content; the publications last, each on the
     * version made of its own, bound to the node of the map made or, when the account has a map it submitted, to its
     * node of the same key (else of the same name).
     */
    public static Planned plan(Document document, Context context) {
        List<Step> steps = new ArrayList<>();
        int size = Math.max(1, context.batchSize());
        long bytes = Math.max(0, context.batchBytes());
        boolean profile = document.profile() != null;
        int trees = 0;
        int quizzes = 0;
        int versions = 0;
        int publications = 0;
        List<String> sameTitles = new ArrayList<>();
        List<String> treesSkipped = new ArrayList<>();
        boolean treesKept = false;
        List<String> unbound = new ArrayList<>();
        List<String> videosAgain = new ArrayList<>();
        List<String> tooLarge = new ArrayList<>();
        List<Oversized> oversized = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        if (profile) {
            steps.add(new Step.Profile("profile"));
        }
        if (context.mode() == Mode.PROFILE) {
            return new Planned(context.mode(), size, bytes, List.copyOf(steps), new Makes(profile, 0, 0, 0, 0),
                sameTitles, treesSkipped, false, unbound, videosAgain, tooLarge, oversized, orphans);
        }

        Map<String, String> treeSteps = new HashMap<>();
        if (context.state().submittedTree()) {
            treesKept = !document.trees().isEmpty();
        } else {
            int room = Math.max(0, context.state().maxTrees() - context.state().activeTrees());
            for (int i = 0; i < document.trees().size(); i++) {
                Tree tree = document.trees().get(i);
                if (room <= 0) {
                    treesSkipped.add(firstName(tree.name(), tree.key()));
                    continue;
                }
                room--;
                String key = "tree:" + (i + 1);
                treeSteps.put(tree.key(), key);
                steps.add(new Step.TreeStep(key, tree));
                trees++;
            }
        }

        Set<String> titles = new HashSet<>();
        context.quizTitles().forEach(title -> titles.add(jsTrim(title)));
        Map<String, String> firstSteps = new HashMap<>();
        Map<String, String> versionSteps = new HashMap<>();
        for (int i = 0; i < document.quizzes().size(); i++) {
            Quiz quiz = document.quizzes().get(i);
            List<Contents> contents = new ArrayList<>();
            for (QuizVersion v : quiz.versions()) {
                contents.add(new Contents(v.label(), v.file(), v.key(), orEmpty(v.topics()), orEmpty(v.customTopics())));
            }
            if (quiz.current() != null) {
                contents.add(new Contents("", quiz.current(), null, orEmpty(quiz.topics()),
                    orEmpty(quiz.customTopics())));
            }
            String title = QuizFileCut.titleOf(contents.isEmpty() ? Map.of() : contents.getLast().file());
            if (contents.stream().anyMatch(c -> QuizFileCut.questionsOf(c.file()).size() > context.maxQuestions())) {
                tooLarge.add(title);
                continue;
            }
            if (titles.contains(jsTrim(title))) {
                sameTitles.add(title);
            }
            String quizKey = "q" + (i + 1);
            quizzes++;
            for (int c = 0; c < contents.size(); c++) {
                Contents content = contents.get(c);
                String part = content.versionKey() != null ? "v:" + content.versionKey() : "c";
                String base = "quiz:" + quizKey + ":" + part;
                String check = quiz.key() + ":" + (content.versionKey() != null ? content.versionKey() : "current");
                String contentKey = base + ":content";
                if (c == 0) {
                    firstSteps.put(quiz.key(), contentKey);
                }
                String first = firstSteps.get(quiz.key());
                int clears = c == 0 ? 0
                    : (int) Math.ceil(QuizFileCut.partsOf(contents.get(c - 1).file()) / (double) QuizFileCut.CLEAR_ROWS);
                for (int k = 1; k <= clears; k++) {
                    steps.add(new Step.Clear(base + ":clear:" + k, quiz.key(), content.label(), title, k, clears, first,
                        contentKey));
                }
                QuizFileCut.Cut cut = QuizFileCut.planContent(content.file(), size, bytes);
                cut.oversized().forEach(question -> oversized.add(new Oversized(title, question)));
                steps.add(new Step.Content(contentKey, quiz.key(), content.label(), title, check, cut.first(),
                    c == 0 ? null : first, content.topics(), content.customTopics(), clears));
                for (int b = 0; b < cut.batches().size(); b++) {
                    steps.add(new Step.Batch(base + ":batch:" + (b + 2), quiz.key(), content.label(), title, check,
                        b + 2, cut.batches().size() + 1, cut.batches().get(b), first, clears));
                }
                if (content.versionKey() != null) {
                    String versionKey = base + ":version";
                    versionSteps.put(content.versionKey(), versionKey);
                    steps.add(new Step.Version(versionKey, quiz.key(), content.label(), title, content.versionKey(),
                        first));
                    versions++;
                }
            }
        }

        for (int i = 0; i < document.publications().size(); i++) {
            Publication publication = document.publications().get(i);
            String name = publication.name();
            String quizStep = publication.quiz() != null ? firstSteps.get(publication.quiz()) : null;
            String versionStep = publication.version() != null ? versionSteps.get(publication.version()) : null;
            if ((publication.quiz() != null && quizStep == null)
                || (publication.version() != null && versionStep == null)) {
                orphans.add(name);
                continue;
            }
            Binding node = null;
            if (publication.node() != null) {
                NodeRef ref = publication.node();
                String treeStep = treeSteps.get(ref.tree());
                Tree tree = document.trees().stream().filter(t -> t.key().equals(ref.tree())).findFirst().orElse(null);
                if (treeStep != null && tree != null && tree.nodes().stream().anyMatch(n -> n.key().equals(ref.node()))) {
                    node = new Binding.ToMade(treeStep, ref.node());
                } else if (context.state().submittedTree()) {
                    OwnNode byKey = context.ownNodes().stream().filter(n -> n.nodeId().equals(ref.node())).findFirst()
                        .orElse(null);
                    List<OwnNode> byName = context.ownNodes().stream().filter(n -> sameName(n.name(), ref.name()))
                        .toList();
                    OwnNode found = byKey != null ? byKey : byName.size() == 1 ? byName.getFirst() : null;
                    if (found != null) {
                        node = new Binding.ToOwn(found.treeId(), found.nodeId());
                    }
                }
                if (node == null) {
                    unbound.add(name);
                }
            }
            if (publication.sharedVideo() != null && !context.sharedVideos().contains(publication.sharedVideo())) {
                videosAgain.add(name);
            }
            steps.add(new Step.PublicationStep("publication:" + (i + 1), i, name, publication, quizStep, versionStep,
                node));
            publications++;
        }
        return new Planned(context.mode(), size, bytes, List.copyOf(steps),
            new Makes(profile, trees, quizzes, versions, publications), List.copyOf(sameTitles),
            List.copyOf(treesSkipped), treesKept, List.copyOf(unbound), List.copyOf(videosAgain), List.copyOf(tooLarge),
            List.copyOf(oversized), List.copyOf(orphans));
    }

    private record Contents(String label, Map<String, Object> file, String versionKey, List<String> topics,
        List<String> customTopics) {}

    /** The first name a texts object gives ({@code Object.values(name).find(Boolean)}), else {@code otherwise}. */
    private static String firstName(Map<String, Object> name, String otherwise) {
        for (Object value : name.values()) {
            if (truthy(value)) {
                return String.valueOf(value);
            }
        }
        return otherwise;
    }

    /** Whether two names say the same in a language both have. */
    private static boolean sameName(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            if (truthy(entry.getValue()) && entry.getValue() instanceof String text
                && b.get(entry.getKey()) instanceof String other && jsTrim(text).equals(jsTrim(other))) {
                return true;
            }
        }
        return false;
    }

    /** The keys of the steps, in order (for logs and reports). */
    public static List<String> keys(Planned planned) {
        List<String> keys = new ArrayList<>();
        planned.steps().forEach(step -> keys.add(step.key()));
        return keys;
    }

    static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}

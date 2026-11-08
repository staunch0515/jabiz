package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.tree.TreeProcesses;

import java.time.Duration;
import java.time.Instant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Knowledge maps for the tests (F-S2): nodes and conditions as the processes take them, saving and the review. */
abstract class TreeItSupport extends PlayItSupport {

    static Map<String, Object> name(String text) {
        return Map.of("en", text);
    }

    static Map<String, Object> node(String nodeId, String key, String name, Map<String, Object> condition) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", nodeId);
        node.put("key", key);
        node.put("name", name(name));
        node.put("condition", condition);
        return node;
    }

    static Map<String, Object> passed() {
        return Map.of("passed", true);
    }

    static Map<String, Object> all(Object... items) {
        return Map.of("op", "ALL", "items", List.of(items));
    }

    static Map<String, Object> lit(String node, boolean beforeAnswer) {
        return beforeAnswer ? Map.of("node", node, "beforeAnswer", true) : Map.of("node", node);
    }

    Map<String, Object> save(String token, Map<String, Object> tree, List<Map<String, Object>> nodes) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("treeId", tree.get("treeId"));
        input.put("baseRevision", tree.get("revision"));
        input.put("name", name("Coffee"));
        input.put("nodes", nodes);
        return run(TreeProcesses.SAVE, token, input);
    }

    Map<String, Object> submit(String token, Map<String, Object> tree) {
        return run(TreeProcesses.SUBMIT, token, body("treeId", tree.get("treeId"), "baseRevision",
            tree.get("revision")));
    }

    void decide(String token, Object requestId, String decision, String reason) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requestId", requestId);
        input.put("decision", decision);
        input.put("reason", reason);
        run("APPROVAL_DECIDE", token, input);
        deliverEvents();
    }

    /** A Sponsor's map in effect: basics (a pass), advanced (basics lit before answering, a pass), expert (both). */
    record Map3(String treeId, String basics, String advanced, String expert) {}

    Map3 map(String sponsor) {
        String token = sponsorToken(sponsor);
        Map<String, Object> tree = run(TreeProcesses.CREATE, token, body("ownerKind", "SPONSOR", "name",
            name("Coffee")));
        Map<String, Object> saved = save(token, tree, List.of(
            node(null, "basics", "Basics", all(passed())),
            node(null, "advanced", "Advanced", all(lit("basics", true), passed())),
            node(null, "expert", "Expert", all(lit("basics", false), lit("advanced", false)))));
        assertThat((List<?>) saved.get("problems")).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, String> ids = (Map<String, String>) saved.get("nodeIds");
        Map<String, Object> submitted = submit(token, saved);
        decide(platformOperatorMember(), submitted.get("approvalRequestId"), "APPROVE", null);
        return new Map3(String.valueOf(tree.get("treeId")), ids.get("basics"), ids.get("advanced"),
            ids.get("expert"));
    }

    Map<String, Object> bound(String versionId, String name, String treeId, String nodeId) {
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("treeId", treeId);
        save.put("treeNodeId", nodeId);
        return save;
    }

    String liveBound(String sponsor, String name, String treeId, String nodeId) {
        return liveBound(sponsor, name, treeId, nodeId, Map.of());
    }

    String liveBound(String sponsor, String name, String treeId, String nodeId, Map<String, Object> overrides) {
        String token = sponsorToken(sponsor);
        Map<String, Object> save = bound(version(token, name + " quiz"), name, treeId, nodeId);
        save.putAll(overrides);
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, save);
        assertThat((List<?>) saved.get("problems")).as("problems").isEmpty();
        String publicationId = (String) saved.get("publicationId");
        assertThat(submit(token, publicationId).get("status")).isIn("LIVE", "SCHEDULED");
        return publicationId;
    }

    void advance(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    static long outboxEvents(String type, String key, String value) {
        return ((Number) query("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = ? "
            + "AND payload::jsonb ->> ? = ?", type, key, value).getFirst().get("n")).longValue();
    }
}

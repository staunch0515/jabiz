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

    /** F-S2c: a platform map Takers light, its one node (a pass), and a live publication bound to it. */
    record Gate(String treeId, String nodeId, String publicationId) {
        String param() {
            return treeId + "/" + nodeId;
        }
    }

    /** A token of headquarters that writes and reviews the platform's maps. */
    String mapWriter() {
        return com.jabiz.runtime.test.TestTokens.bearer(tokens, verifiedUser(unique("fs2-content")),
            com.jabiz.quizbuks.QbPermissions.TREE_EDIT, com.jabiz.quizbuks.QbPermissions.TREE_PLATFORM_WRITE,
            com.jabiz.quizbuks.QbPermissions.TREES_READ, "approval.decide");
    }

    /**
     * A gate node as production makes one: a platform map Takers light, reviewed by another administrator, with a
     * free publication of an operator's own Sponsor bound to its node (the only Sponsor who may bind a platform map).
     */
    Gate gate() {
        return gate(null);
    }

    /** F-S5a: as {@link #gate()}, its node issuing a certificate named {@code certificateNames} (none when null). */
    Gate gate(Map<String, Object> certificateNames) {
        String writer = mapWriter();
        Map<String, Object> tree = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM", "subjectKind",
            "TAKER", "name", name("Gates")));
        Map<String, Object> gate = node(null, "gate", "Fair dealing", all(passed()));
        if (certificateNames != null) {
            gate.put("certificateNames", certificateNames);
        }
        Map<String, Object> saved = save(writer, tree, List.of(gate));
        assertThat((List<?>) saved.get("problems")).isEmpty();
        @SuppressWarnings("unchecked")
        String nodeId = ((Map<String, String>) saved.get("nodeIds")).get("gate");
        decide(mapWriter(), submit(writer, saved).get("approvalRequestId"), "APPROVE", null);
        String treeId = String.valueOf(tree.get("treeId"));
        NewOperator operator = officialOperator("FS2c gate");
        String publicationId = liveBound(operator.leadId(), unique("Gate"), treeId, nodeId,
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        return new Gate(treeId, nodeId, publicationId);
    }

    /** The users light the gate's node: each passes its publication, and the lighting's events are delivered. */
    void light(Gate gate, String... userIds) {
        for (String userId : userIds) {
            // A Sponsor's account answers in the app as a Taker: it needs the profile the app asks for.
            Map<String, Object> profile = profileOf(userId);
            if (profile == null || profile.get("country_code") == null) {
                profile(userId, "JP");
            }
            assertThat(play(userId, gate.publicationId(), true, true)).containsEntry("passed", true);
        }
        deliverEvents();
    }

    /** Names the gate's node in the controlled parameter {@code key} (four eyes: proposed and published). */
    void nameGate(String key, Gate gate) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", gate.param(), "valueKind",
                com.jabiz.quizbuks.setup.QbParams.TREE_GATE_KIND), "reason", "F-S2c gate"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private final Map<String, Gate> gates = new LinkedHashMap<>();

    /** Opens the gate {@code key} to the users: its node (made and named the first time) lit by each of them. */
    void unlock(String key, String... userIds) {
        Gate gate = gates.computeIfAbsent(key, k -> {
            Gate made = gate();
            nameGate(k, made);
            return made;
        });
        light(gate, userIds);
    }

    /** F-S4a: a platform map Sponsors light, its copy by a new operator in effect, the operator and its two nodes. */
    record Copy(String templateId, String treeId, String start, String next, NewOperator operator) {}

    @SuppressWarnings("unchecked")
    Copy copy() {
        return copy((java.math.BigDecimal) null);
    }

    /** F-S4b: as {@link #copy()}, the template's first node granting {@code grant} (the limit published first). */
    Copy copy(java.math.BigDecimal grant) {
        Map<String, Object> start = node(null, "start", "Getting started", all(passed()));
        if (grant != null) {
            start.put("grantAmount", grant);
        }
        return copy(List.of(start, node(null, "next", "Running a quiz", all(lit("start", true), passed()))));
    }

    /** F-S4b: as {@link #copy()}, with the template's nodes as given (keyed {@code start} and {@code next}). */
    Copy copy(List<Map<String, Object>> nodes) {
        String writer = mapWriter();
        Map<String, Object> template = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM",
            "subjectKind", "SPONSOR", "name", name("Sponsor skills")));
        Map<String, Object> saved = save(writer, template, nodes);
        assertThat((List<?>) saved.get("problems")).as("problems").isEmpty();
        Map<String, String> ids = (Map<String, String>) saved.get("nodeIds");
        decide(mapWriter(), submit(writer, saved).get("approvalRequestId"), "APPROVE", null);
        NewOperator operator = officialOperator("FS4a challenges");
        String lead = memberSession(operator.leadId(), operator.operatorId());
        Map<String, Object> copy = run(TreeProcesses.COPY, lead, body("templateTreeId", template.get("treeId")));
        String other = verifiedUser(unique("fs4a-member"));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.MEMBER_ADD, operatorsAdmin(), body("operatorId", operator.operatorId(), "userId", other,
            "lead", false));
        decide(memberSession(other, operator.operatorId()), submit(lead, copy).get("approvalRequestId"), "APPROVE",
            null);
        return new Copy(String.valueOf(template.get("treeId")), String.valueOf(copy.get("treeId")), ids.get("start"),
            ids.get("next"), operator);
    }

    /** A challenge's publication: every tier 0. */
    Map<String, Object> challenge(String token, String treeId, String nodeId, String name) {
        Map<String, Object> save = bound(version(token, name + " quiz"), name, treeId, nodeId);
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0)));
        return save;
    }

    /** A Sponsor of the operator: onboarded, then moved into it. */
    String sponsorOf(NewOperator operator, String name) {
        String sponsor = newSponsor(unique(name));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId", operator.operatorId(),
            "reason", "F-S4a"));
        return sponsor;
    }

    void advance(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    static long outboxEvents(String type, String key, String value) {
        return ((Number) query("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = ? "
            + "AND payload::jsonb ->> ? = ?", type, key, value).getFirst().get("n")).longValue();
    }
}

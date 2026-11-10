package com.jabiz.quizbuks.tree;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.approval.ContentHash;
import com.jabiz.event.DomainEvent;
import com.jabiz.runtime.EntityInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the review of a knowledge graph's version looks at and is bound to (F-S2): the one fact is whose graph it is
 * (a Sponsor's goes to their operator, an operator's copy to another member of it, the platform's template to another
 * administrator: one rule each); the content is the version's name and every node's name, description and condition,
 * by node id. An approval counts only while the stored version hashes the same.
 */
public final class TreeApprovals {

    /** The approval subject; its name in each language is the message {@code approval.subject.qb.tree}. */
    public static final String SUBJECT = "qb.tree";
    /** The rules {@code QB_SETUP} proposes, by the graph's owner. */
    public static final String SPONSOR_RULE = "qb.tree.review";
    public static final String OPERATOR_RULE = "qb.tree.copy.review";
    public static final String PLATFORM_RULE = "qb.tree.platform.review";

    private TreeApprovals() {}

    static ApprovalSubject subject() {
        return ApprovalSubject.define(SUBJECT, s -> s.entity(QbTrees.VERSION).text("ownerKind"));
    }

    static Map<String, Object> facts(String ownerKind) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("ownerKind", ownerKind);
        return facts;
    }

    /** The version's content as stored: its name and description, and its nodes (with their grants and certificates) in order by id. */
    static Map<String, Object> content(EntityInstance version, List<EntityInstance> defs) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("versionNo", String.valueOf((Object) version.get("versionNo")));
        content.put("name", text(version.get("name")));
        content.put("description", text(version.get("description")));
        List<Map<String, Object>> nodes = new ArrayList<>();
        defs.stream().sorted(Comparator.comparing(def -> String.valueOf((Object) def.get("nodeId")))).forEach(def -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeId", String.valueOf((Object) def.get("nodeId")));
            node.put("name", text(def.get("name")));
            node.put("description", text(def.get("description")));
            node.put("condition", String.valueOf((Object) def.get("condition")));
            node.put("seq", String.valueOf((Object) def.get("seq")));
            // F-S4b: the grant is reviewed with the version; written only when set, so a version without grants
            // hashes as before.
            java.math.BigDecimal grant = TreeProcesses.grantOf(def);
            if (grant != null) {
                node.put("grantAmount", grant.toPlainString());
            }
            // F-S5a: likewise the certificate (reviewed with the version: it names who vouches for what).
            Map<String, String> certificate = TreeProcesses.certificateOf(def);
            if (certificate != null) {
                node.put("certificateNames", new java.util.TreeMap<>(certificate).toString());
            }
            nodes.add(node);
        });
        content.put("nodes", nodes);
        return content;
    }

    /** A multilingual text as one string, its languages in order, so that it hashes the same however it was read. */
    private static String text(Object value) {
        Map<String, String> map = TreeProcesses.i18n(value);
        if (map == null) {
            return null;
        }
        return new java.util.TreeMap<>(map).toString();
    }

    static String hash(Map<String, ?> content) {
        return ContentHash.of(content);
    }

    /** The version as people know it in the task's title: its name in some language and its number. */
    static String reference(EntityInstance version) {
        Map<String, String> name = TreeProcesses.i18n(version.get("name"));
        String shown = name == null ? "" : name.values().stream().filter(t -> t != null && !t.isBlank()).findFirst()
            .orElse("");
        return shown + " v" + version.get("versionNo");
    }

    static TreeProcesses.ApprovalResultInput resultInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        return new TreeProcesses.ApprovalResultInput(text(payload, "subject"), text(payload, "entityId"),
            text(payload, "status"), text(payload, "requestId"), text(payload, "preparerId"),
            text(payload, "contentHash"), text(payload, "reason"));
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

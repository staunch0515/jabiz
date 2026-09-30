package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItApprovalFixtures;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.test.TestTokens;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Processes, rules and payments of the approval and task tests, through the HTTP API. */
abstract class ApprovalItSupport extends SecurityItSupport {

    static final String PROPOSER = "it-proposer";

    String as(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    String preparer() {
        return as("it-preparer", ItApprovalFixtures.PREPARE);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> run(String process, String authorization, Map<String, Object> input) {
        var exchange = post("/api/processes/" + process + "/latest", authorization, input).expectBody(MAP)
            .returnResult();
        assertThat(exchange.getStatus().value()).as("%s: %s", process, exchange.getResponseBody()).isEqualTo(200);
        return (Map<String, Object>) exchange.getResponseBody().get("output");
    }

    Map<String, Object> refused(String process, String authorization, Map<String, Object> input, int status) {
        return post("/api/processes/" + process + "/latest", authorization, input)
            .expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
    }

    /** Proposes and publishes a change (two people); returns the target's id. */
    String control(String entity, String targetId, Map<String, Object> values, Instant effectiveTime) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("targetEntity", entity);
        input.put("targetId", targetId);
        input.put("values", values);
        input.put("effectiveTime", effectiveTime == null ? null : effectiveTime.toString());
        input.put("reason", "test");
        String changeId = (String) run("CONTROL_CHANGE_PROPOSE", as(PROPOSER, ApprovalPermissions.CONTROL_PROPOSE),
            input).get("changeId");
        return (String) run("CONTROL_CHANGE_PUBLISH", as("it-publisher", ApprovalPermissions.CONTROL_PUBLISH),
            Map.of("changeId", changeId)).get("targetId");
    }

    /** A rule for payments of {@code channel} from {@code amount} on. */
    String rule(String channel, int amount, List<Map<String, Object>> levels) {
        return control(ApprovalEntities.RULE, null, Map.of(
            "ruleCode", unique("R"), "subject", ItApprovalFixtures.SUBJECT, "priority", 10, "enabled", true,
            "condition", Map.of("all", List.of(
                Map.of("fact", "channel", "op", "eq", "value", channel),
                Map.of("fact", "amount", "op", "gte", "value", amount))),
            "levels", levels), null);
    }

    Map<String, Object> pay(String authorization, String paymentId, String channel, Object amount, String memo) {
        Map<String, Object> input = new HashMap<>();
        input.put("paymentId", paymentId);
        input.put("channel", channel);
        input.put("amount", amount);
        input.put("memo", memo);
        return run("IT_PAY", authorization, input);
    }

    Map<String, Object> decide(String authorization, String requestId, String decision, String reason) {
        Map<String, Object> input = new HashMap<>();
        input.put("requestId", requestId);
        input.put("decision", decision);
        input.put("reason", reason);
        return run("APPROVAL_DECIDE", authorization, input);
    }

    static String status(String requestId) {
        return (String) query("SELECT status FROM sys_approval_request_version WHERE request_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", requestId).getFirst().get("status");
    }

    static List<Map<String, Object>> events(String type, String requestId) {
        return query("SELECT payload->>'approverId' AS approver, payload->>'preparerId' AS preparer,"
            + " payload->>'reason' AS reason FROM sys_outbox_event WHERE event_type = ?"
            + " AND payload->>'requestId' = ?", type, requestId);
    }
}

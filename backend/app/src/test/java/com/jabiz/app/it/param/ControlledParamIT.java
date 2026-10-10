package com.jabiz.app.it.param;

import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.context.RequestContext;
import com.jabiz.param.ControlledParams;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.param.ParamService;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business parameters that change only with four eyes (decision D40) through the HTTP API: no single person changes
 * a controlled parameter on any path, nor lifts the control; a controlled change one person proposes and another
 * publishes creates, changes, schedules and cancels its values, checked against the parameter's kind. Every test
 * uses a controlled key of its own: parameters are temporal and cannot be cleaned up.
 */
@SpringBootTest
class ControlledParamIT extends SecurityItSupport {

    private static final String DIRECT = "it.controlled.direct";
    private static final String NOW = "it.controlled.now";
    private static final String SCHEDULED = "it.controlled.scheduled";
    private static final String SAME = "it.controlled.same";
    private static final String INVALID = "it.controlled.invalid";
    private static final String LIFT = "it.controlled.lift";
    private static final String LATE = "it.controlled.late";
    private static final String NEVER_CREATED = "it.controlled.never-created";
    private static final String CANCEL = "it.controlled.cancel";
    private static final String DESCRIBED = "it.controlled.described";
    private static final String RACE = "it.controlled.race";
    private static final String RELAYED = "it.controlled.relayed";
    private static final String SMUGGLED = "it.controlled.smuggled";

    private static final Map<String, Object> RATE = Map.of("type", "numeric", "precision", 5, "scale", 4);
    private static final RequestContext READER = new RequestContext("it-reader", null, Locale.ENGLISH, "it-param",
        Set.of(), Set.of("*"));

    @TestConfiguration
    static class Controlled {

        @Bean
        ControlledParams itControlledParams() {
            return ControlledParams.of(DIRECT, NOW, SCHEDULED, SAME, INVALID, LIFT, LATE, NEVER_CREATED, CANCEL,
                DESCRIBED, RACE, RELAYED, SMUGGLED);
        }

        record RelayInput(String changeId) {}

        /**
         * Publishes a change as a sub-process, as a business process could, and writes a controlled parameter
         * itself in the same go: only the publication's own write may pass.
         */
        @Bean
        ProcessDefinition<RelayInput, Map<String, Object>, ProcessContext> controlRelayProcess() {
            @SuppressWarnings("unchecked")
            Class<Map<String, Object>> output = (Class<Map<String, Object>>) (Class<?>) Map.class;
            return ProcessDefinition.define("IT_CONTROL_RELAY", 1, RelayInput.class, output, ProcessContext.class,
                pb -> pb
                    .permissions("it.control-relay")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> Map.of())
                    .step("Publish", CallProcess.<ProcessContext>of(ControlChanges.PUBLISH, 1,
                        ctx -> new ControlChanges.ChangeInput(java.util.UUID.fromString(
                            ctx.get("in", RelayInput.class).changeId())), "published"))
                    .compute("Smuggle", (metadata, ctx) -> ctx.changes().insert(ParamEntities.ENTITY, Map.of(
                        "paramKey", SMUGGLED, "valueKind", RATE, "value", "0.9000"))));
        }
    }

    @Autowired
    ParamService params;

    @Autowired
    JwtService jwt;

    @Test
    void noSinglePersonChangesAControlledParameterDirectly() {
        String paramId = create(DIRECT, "0.1");
        String writer = writer();

        assertThat(ruleCode(refused("PARAM_SET", writer, Map.of("key", DIRECT, "value", "0.2"))))
            .isEqualTo("PARAM_CONTROLLED");
        assertThat(ruleCode(refused("PARAM_SCHEDULE", writer, Map.of("key", DIRECT, "value", "0.2",
            "effectiveTime", clock.instant().plus(Duration.ofDays(1)).toString())))).isEqualTo("PARAM_CONTROLLED");
        // A value scheduled by a controlled change is cancelled by a controlled change too.
        Instant later = clock.instant().plus(Duration.ofDays(2));
        control(Map.of("paramKey", DIRECT, "value", "0.3"), later, false);
        assertThat(ruleCode(refused("PARAM_CANCEL_SCHEDULED", writer, Map.of("key", DIRECT,
            "effectiveTime", later.toString())))).isEqualTo("PARAM_CONTROLLED");
        // Nor can it be created by one person before anybody did.
        assertThat(ruleCode(refused("PARAM_CREATE", writer, Map.of("key", NEVER_CREATED, "valueKind", RATE,
            "value", "0.1")))).isEqualTo("PARAM_CONTROLLED");

        // The dataset API and the generic entity processes take the same way to the versions.
        Map<String, Object> update = new HashMap<>(Map.of("action", "UPDATE", "id", paramId, "version", 1,
            "attributes", Map.of("value", "0.2000")));
        assertThat(ruleCode(post("/api/datasets/" + ParamEntities.DATASET + "/commit", writer,
            Map.of("changes", List.of(update))).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("PARAM_CONTROLLED");
        assertThat(ruleCode(refused("UPDATE_ENTITY", writer, Map.of("entityType", ParamEntities.ENTITY,
            "id", paramId, "version", 1, "attributes", Map.of("value", "0.2000"))))).isEqualTo("PARAM_CONTROLLED");

        assertThat(value(DIRECT, clock.instant())).isEqualByComparingTo("0.1");
        assertThat(value(DIRECT, later)).isEqualByComparingTo("0.3");
        assertThat(query("SELECT count(*) AS n FROM sys_param_version WHERE param_key = ?", NEVER_CREATED)
            .getFirst().get("n")).isEqualTo(0L);
    }

    @Test
    void aPublishedChangeTakesEffectAtOnceWithItsHistoryAndAudit() {
        String paramId = create(NOW, "0.1");
        Instant before = clock.instant();
        clock.advance(Duration.ofHours(1));

        Map<String, Object> proposed = propose(Map.of("paramKey", NOW, "value", new BigDecimal("0.125"),
            "description", "Raised"), null, false);
        assertThat(proposed).containsEntry("status", "PROPOSED").containsEntry("targetId", paramId);
        // Proposed is not in effect.
        assertThat(value(NOW, clock.instant())).isEqualByComparingTo("0.1");

        Map<String, Object> published = run("CONTROL_CHANGE_PUBLISH", publisher(),
            Map.of("changeId", proposed.get("changeId")));
        assertThat(published).containsEntry("status", "PUBLISHED").containsEntry("targetId", paramId);
        assertThat(value(NOW, clock.instant())).isEqualByComparingTo("0.125");
        assertThat(value(NOW, before)).isEqualByComparingTo("0.1");

        // Stored canonical, as a version of its own written by the publishing operation, and audited.
        List<Map<String, Object>> versions = query("SELECT v.param_value, v.description, p.process_name, p.actor_id"
            + " FROM sys_param_version v JOIN op_process p ON p.process_seq_id = v.process_seq_id"
            + " WHERE v.param_key = ? ORDER BY v.version_no", NOW);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1)).containsEntry("param_value", "0.1250").containsEntry("description", "Raised")
            .containsEntry("process_name", "CONTROL_CHANGE_PUBLISH").containsEntry("actor_id", "it-publisher");
        assertThat(query("SELECT action FROM sys_audit_record WHERE entity_type = ? AND entity_id = ?"
            + " ORDER BY record_no", ParamEntities.ENTITY, paramId)).extracting(row -> row.get("action"))
            .containsExactly("INSERT", "UPDATE");
    }

    @Test
    void aScheduledChangeTakesEffectLaterAndIsCancelledWithFourEyesToo() {
        create(SCHEDULED, "0.1");
        Instant switchover = clock.instant().plus(Duration.ofDays(1));
        control(Map.of("paramKey", SCHEDULED, "value", "0.15"), switchover, false);

        assertThat(value(SCHEDULED, clock.instant())).isEqualByComparingTo("0.1");
        assertThat(value(SCHEDULED, switchover.minusMillis(1))).isEqualByComparingTo("0.1");
        assertThat(value(SCHEDULED, switchover)).isEqualByComparingTo("0.15");

        control(Map.of("paramKey", SCHEDULED), switchover, true);
        assertThat(value(SCHEDULED, switchover)).isEqualByComparingTo("0.1");
        // Nothing is scheduled then any more: a cancellation is refused as soon as it is proposed.
        assertThat(ruleCode(refused("CONTROL_CHANGE_PROPOSE", proposer(), proposal(Map.of("paramKey", SCHEDULED),
            switchover, true)))).isEqualTo("NOT_SCHEDULED");
        assertThat(ruleCode(refused("CONTROL_CHANGE_PROPOSE", proposer(), proposal(Map.of("paramKey", SCHEDULED),
            switchover.plusSeconds(1), true)))).isEqualTo("NOT_SCHEDULED");

        clock.advance(Duration.ofDays(2));
        assertThat(value(SCHEDULED, clock.instant())).isEqualByComparingTo("0.1");
    }

    @Test
    void aCancellationIsCheckedAgainWhenPublished() {
        create(CANCEL, "0.1");
        Instant later = clock.instant().plus(Duration.ofDays(1));
        control(Map.of("paramKey", CANCEL, "value", "0.2"), later, false);
        String first = (String) propose(Map.of("paramKey", CANCEL), later, true).get("changeId");
        String second = (String) propose(Map.of("paramKey", CANCEL), later, true).get("changeId");
        run("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", first));
        assertThat(ruleCode(refused("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", second))))
            .isEqualTo("NOT_SCHEDULED");
        assertThat(value(CANCEL, later)).isEqualByComparingTo("0.1");
    }

    @Test
    void theDescriptionIsCheckedWhenProposedAsWhenWritten() {
        create(DESCRIBED, "0.1");
        assertThat(violations(refused("CONTROL_CHANGE_PROPOSE", proposer(), proposal(Map.of("paramKey", DESCRIBED,
            "description", "x".repeat(501)), null, false)))).extracting(v -> v.get("ruleCode"), v -> v.get("field"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("TOO_LONG", "description"));
        assertThat(violations(refused("CONTROL_CHANGE_PROPOSE", proposer(), proposal(Map.of("paramKey", DESCRIBED,
            "description", Map.of("no", "text")), null, false)))).extracting(v -> v.get("field"))
            .containsExactly("description");
        // A creation too: the description of a new parameter is checked before anybody approves it.
        assertThat(violations(refused("CONTROL_CHANGE_PROPOSE", proposer(), proposal(Map.of("paramKey",
            NEVER_CREATED, "valueKind", RATE, "value", "0.1", "description", "x".repeat(501)), null, false))))
            .extracting(v -> v.get("ruleCode")).containsExactly("TOO_LONG");
        control(Map.of("paramKey", DESCRIBED, "description", "x".repeat(500)), null, false);
    }

    @Test
    void aCreationThatSomebodyElseMadeFirstIsNotPublishedAsAChange() {
        Map<String, Object> values = Map.of("paramKey", RACE, "valueKind", RATE, "value", "0.1");
        String first = (String) propose(values, null, false).get("changeId");
        String second = (String) propose(Map.of("paramKey", RACE, "valueKind", RATE, "value", "0.3"), null, false)
            .get("changeId");
        run("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", first));
        assertThat(ruleCode(refused("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", second))))
            .isEqualTo("CONTROL_TARGET_CHANGED");
        assertThat(value(RACE, clock.instant())).isEqualByComparingTo("0.1");
        assertThat(query("SELECT count(*) AS n FROM sys_param_version WHERE param_key = ?", RACE).getFirst()
            .get("n")).isEqualTo(1L);
    }

    @Test
    void onlyThePublicationsOwnWritePassesNotOthersInTheSameGo() {
        create(RELAYED, "0.1");
        String changeId = (String) propose(Map.of("paramKey", RELAYED, "value", "0.2"), null, false)
            .get("changeId");
        // The publication as a sub-process is fine by itself, but the caller's own write of a controlled key is
        // refused, and with it everything it did.
        assertThat(ruleCode(refused("IT_CONTROL_RELAY", as("it-relay", "*"), Map.of("changeId", changeId))))
            .isEqualTo("PARAM_CONTROLLED");
        assertThat(value(RELAYED, clock.instant())).isEqualByComparingTo("0.1");
        assertThat(query("SELECT count(*) AS n FROM sys_param_version WHERE param_key = ?", SMUGGLED).getFirst()
            .get("n")).isEqualTo(0L);
        run("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", changeId));
        assertThat(value(RELAYED, clock.instant())).isEqualByComparingTo("0.2");
    }

    @Test
    void theProposerCannotPublish() {
        create(SAME, "0.1");
        String both = as("it-both", ApprovalPermissions.CONTROL_PROPOSE, ApprovalPermissions.CONTROL_PUBLISH);
        String changeId = (String) run("CONTROL_CHANGE_PROPOSE", both, proposal(Map.of("paramKey", SAME,
            "value", "0.2"), null, false)).get("changeId");
        assertThat(ruleCode(refused("CONTROL_CHANGE_PUBLISH", both, Map.of("changeId", changeId))))
            .isEqualTo("CONTROL_SAME_PERSON");
        assertThat(value(SAME, clock.instant())).isEqualByComparingTo("0.1");
    }

    @Test
    void proposalsAreCheckedAgainstTheParameterAtOnce() {
        create(INVALID, "0.1");
        String proposer = proposer();
        assertThat(violations(refused("CONTROL_CHANGE_PROPOSE", proposer, proposal(Map.of("paramKey", INVALID,
            "value", "12.5"), null, false)))).extracting(v -> v.get("ruleCode")).containsExactly("PARAM_VALUE_INVALID");
        assertThat(violations(refused("CONTROL_CHANGE_PROPOSE", proposer, proposal(Map.of("paramKey", INVALID,
            "value", "0.2", "valueKind", Map.of("type", "text"), "owner", "me"), null, false))))
            .extracting(v -> v.get("ruleCode")).containsOnly("CONTROL_CHANGE_INVALID").hasSize(2);
        assertThat(ruleCode(refused("CONTROL_CHANGE_PROPOSE", proposer, proposal(Map.of("paramKey", INVALID,
            "value", "0.2"), clock.instant().minusSeconds(1), false)))).isEqualTo("EFFECTIVE_TIME_NOT_FUTURE");
        // Keys nobody declared controlled are changed the ordinary way.
        String free = unique("it.free");
        run("PARAM_CREATE", writer(), Map.of("key", free, "valueKind", RATE, "value", "0.1"));
        assertThat(ruleCode(refused("CONTROL_CHANGE_PROPOSE", proposer, proposal(Map.of("paramKey", free,
            "value", "0.2"), null, false)))).isEqualTo("CONTROL_CHANGE_INVALID");
        // A parameter that does not exist yet is created with its kind.
        assertThat(ruleCode(refused("CONTROL_CHANGE_PROPOSE", proposer, proposal(Map.of("paramKey", NEVER_CREATED,
            "value", "0.2"), null, false)))).isEqualTo("CONTROL_CHANGE_INVALID");
    }

    @Test
    void aChangeWhoseTimeHasPassedIsNotPublished() {
        create(LATE, "0.1");
        String changeId = (String) propose(Map.of("paramKey", LATE, "value", "0.2"),
            clock.instant().plus(Duration.ofHours(1)), false).get("changeId");
        clock.advance(Duration.ofHours(2));
        assertThat(ruleCode(refused("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", changeId))))
            .isEqualTo("EFFECTIVE_TIME_NOT_FUTURE");
        assertThat(value(LATE, clock.instant())).isEqualByComparingTo("0.1");
    }

    @Test
    void theControlCannotBeLiftedByOnePerson() {
        String paramId = create(LIFT, "0.1");
        String writer = writer();
        // Deleting the parameter to create it again, uncontrolled, is refused; so is the generic deletion.
        Map<String, Object> delete = new HashMap<>(Map.of("action", "DELETE", "id", paramId, "version", 1));
        assertThat(ruleCode(post("/api/datasets/" + ParamEntities.DATASET + "/commit", writer,
            Map.of("changes", List.of(delete))).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("PARAM_CONTROLLED");
        assertThat(ruleCode(refused("DELETE_ENTITY", writer, Map.of("entityType", ParamEntities.ENTITY,
            "id", paramId, "version", 1)))).isEqualTo("PARAM_CONTROLLED");
        // The key cannot be renamed away, and whether it is controlled is no field anybody can write.
        post("/api/datasets/" + ParamEntities.DATASET + "/commit", writer, Map.of("changes", List.of(
            new HashMap<>(Map.of("action", "UPDATE", "id", paramId, "version", 1,
                "attributes", Map.of("paramKey", unique("it.free"))))))).expectStatus().is4xxClientError();
        post("/api/datasets/" + ParamEntities.DATASET + "/commit", writer, Map.of("changes", List.of(
            new HashMap<>(Map.of("action", "UPDATE", "id", paramId, "version", 1,
                "attributes", Map.of("controlled", false)))))).expectStatus().is4xxClientError();
        // A controlled change cannot lift it either: there is nothing to change but the value and description.
        assertThat(ruleCode(refused("CONTROL_CHANGE_PROPOSE", proposer(), proposal(Map.of("paramKey", LIFT,
            "controlled", false), null, false)))).isEqualTo("CONTROL_CHANGE_INVALID");

        assertThat(ruleCode(refused("PARAM_SET", writer, Map.of("key", LIFT, "value", "0.2"))))
            .isEqualTo("PARAM_CONTROLLED");
        assertThat(query("SELECT count(*) AS n FROM sys_param_version WHERE param_id = ?::uuid", paramId)
            .getFirst().get("n")).isEqualTo(1L);
    }

    /** Creates a controlled parameter of kind numeric(5,4) with four eyes; returns its id. */
    private String create(String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", key);
        values.put("valueKind", RATE);
        values.put("value", value);
        values.put("description", "test");
        return control(values, null, false);
    }

    /** Proposes and publishes a change (two people); returns the parameter's id. */
    private String control(Map<String, Object> values, Instant effectiveTime, boolean delete) {
        String changeId = (String) propose(values, effectiveTime, delete).get("changeId");
        return (String) run("CONTROL_CHANGE_PUBLISH", publisher(), Map.of("changeId", changeId)).get("targetId");
    }

    private Map<String, Object> propose(Map<String, Object> values, Instant effectiveTime, boolean delete) {
        return run("CONTROL_CHANGE_PROPOSE", proposer(), proposal(values, effectiveTime, delete));
    }

    private static Map<String, Object> proposal(Map<String, Object> values, Instant effectiveTime, boolean delete) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("targetEntity", ParamEntities.ENTITY);
        input.put("values", values);
        input.put("delete", delete);
        input.put("effectiveTime", effectiveTime == null ? null : effectiveTime.toString());
        input.put("reason", "test");
        return input;
    }

    private String proposer() {
        return as("it-proposer", ApprovalPermissions.CONTROL_PROPOSE);
    }

    private String publisher() {
        return as("it-publisher", ApprovalPermissions.CONTROL_PUBLISH);
    }

    /** Somebody who may do anything, but alone. */
    private String writer() {
        return as("it-writer", "*");
    }

    private String as(String actor, String... permissions) {
        return TestTokens.bearer(jwt, actor, permissions);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String process, String authorization, Map<String, Object> input) {
        var exchange = post("/api/processes/" + process + "/latest", authorization, input).expectBody(MAP)
            .returnResult();
        assertThat(exchange.getStatus().value()).as("%s: %s", process, exchange.getResponseBody()).isEqualTo(200);
        return (Map<String, Object>) exchange.getResponseBody().get("output");
    }

    private Map<String, Object> refused(String process, String authorization, Map<String, Object> input) {
        var exchange = post("/api/processes/" + process + "/latest", authorization, input).expectBody(MAP)
            .returnResult();
        assertThat(exchange.getStatus().value()).as("%s: %s", process, exchange.getResponseBody()).isEqualTo(422);
        return exchange.getResponseBody();
    }

    private BigDecimal value(String key, Instant asOf) {
        return (BigDecimal) asRequest(READER, params.get(key, asOf)).block();
    }
}

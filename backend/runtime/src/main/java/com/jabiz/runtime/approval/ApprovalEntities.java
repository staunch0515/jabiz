package com.jabiz.runtime.approval;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.runtime.security.SecurityEntities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Approvals, approver limits, segregation-of-duties rules and their controlled changes as platform entities
 * (docs/design/18-numbering-approvals-tasks.md sections 3 and 4; decision D23). All are written by the platform
 * processes only ({@code processOnlyWrites}):
 * <ul>
 *   <li>rules, limits and SoD rules (temporal, can be scheduled) change only by a {@code SysControlChange} one
 *       person proposes and another publishes;</li>
 *   <li>requests (temporal) by the step {@link RequireApproval} and the process {@code APPROVAL_DECIDE};</li>
 *   <li>decisions and evaluations are append-only records.</li>
 * </ul>
 */
@Configuration
public class ApprovalEntities {

    public static final String RULE = "SysApprovalRule";
    public static final String LIMIT = "SysApprovalLimit";
    public static final String SOD_RULE = "SysSodRule";
    public static final String CONTROL_CHANGE = "SysControlChange";
    public static final String REQUEST = "SysApprovalRequest";
    public static final String DECISION = "ApprovalDecision";
    public static final String EVALUATION = "ApprovalEvaluation";

    public static final String RULE_DATASET = "urn:jabiz:dataset:platform:SysApprovalRule";
    public static final String LIMIT_DATASET = "urn:jabiz:dataset:platform:SysApprovalLimit";
    public static final String SOD_RULE_DATASET = "urn:jabiz:dataset:platform:SysSodRule";
    public static final String CONTROL_CHANGE_DATASET = "urn:jabiz:dataset:platform:SysControlChange";
    public static final String REQUEST_DATASET = "urn:jabiz:dataset:platform:SysApprovalRequest";
    public static final String DECISION_DATASET = "urn:jabiz:dataset:platform:ApprovalDecision";
    public static final String EVALUATION_DATASET = "urn:jabiz:dataset:platform:ApprovalEvaluation";

    static final String REQUEST_STATUSES = "urn:jabiz:dict:approval:request-status";
    static final String CHANGE_STATUSES = "urn:jabiz:dict:approval:change-status";
    static final String CHANGE_ACTIONS = "urn:jabiz:dict:approval:change-action";
    static final String DECISIONS = "urn:jabiz:dict:approval:decision";
    static final String OUTCOMES = "urn:jabiz:dict:approval:outcome";
    static final String CONTROL_TARGETS_DICTIONARY = "urn:jabiz:dict:approval:control-target";

    /** Status of a request. SUPERSEDED: the content changed, a new request was made. */
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String SUPERSEDED = "SUPERSEDED";

    /** Status of a controlled change. */
    public static final String PROPOSED = "PROPOSED";
    public static final String PUBLISHED = "PUBLISHED";

    /** Changes are only ever a few rows; most rows of one query. */
    static final int MAX_ROWS = 1000;

    public static final EntityDefinition APPROVAL_RULE = EntityDefinition.define(RULE, eb -> {
        eb.physicalTable("sys_approval_rule_version");
        eb.primaryKey("ruleId");
        eb.field("ruleId", f -> f.physicalColumn("rule_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:approval-rule"));
        eb.field("ruleCode", f -> f.physicalColumn("rule_code").immutable(true).required(true).asText(100));
        eb.field("subject", f -> f.physicalColumn("subject").immutable(true).required(true).asText(100));
        eb.field("condition", f -> f.physicalColumn("condition").required(true).asText(20_000, true));
        eb.field("levels", f -> f.physicalColumn("levels").required(true).asText(4000, true));
        eb.field("priority", f -> f.physicalColumn("priority").required(true).asNumeric(9, 0));
        eb.field("enabled", f -> f.physicalColumn("enabled").required(true).asBool());
        eb.field("description", f -> f.physicalColumn("description").asText(500));
        eb.unique("uk_sys_approval_rule_code", "ruleCode");
        eb.temporal(t -> t.allowScheduled(true));
        eb.display("ruleCode");
        eb.listView("default", lv -> lv
            .columns("ruleCode", "subject", "priority", "enabled", "description")
            .filters("ruleCode", "subject", "enabled")
            .sorts("subject", "priority", "ruleCode")
            .defaultSort("priority", true));
    });

    public static final EntityDefinition APPROVAL_LIMIT = EntityDefinition.define(LIMIT, eb -> {
        eb.physicalTable("sys_approval_limit_version");
        eb.primaryKey("limitId");
        eb.field("limitId", f -> f.physicalColumn("limit_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:approval-limit"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asReference(SecurityEntities.USER));
        eb.field("subject", f -> f.physicalColumn("subject").immutable(true).required(true).asText(100));
        eb.field("maxValue", f -> f.physicalColumn("max_value").required(true).asNumeric(19, 4));
        eb.unique("uk_sys_approval_limit", "userId", "subject");
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns("userId", "subject", "maxValue")
            .filters("userId", "subject")
            .sorts("subject"));
    });

    public static final EntityDefinition SOD = EntityDefinition.define(SOD_RULE, eb -> {
        eb.physicalTable("sys_sod_rule_version");
        eb.primaryKey("ruleId");
        eb.field("ruleId", f -> f.physicalColumn("rule_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:sod-rule"));
        eb.field("ruleCode", f -> f.physicalColumn("rule_code").immutable(true).required(true).asText(100));
        eb.field("leftPermissions", f -> f.physicalColumn("left_permissions").required(true).asText(4000, true));
        eb.field("rightPermissions", f -> f.physicalColumn("right_permissions").required(true).asText(4000, true));
        eb.field("enabled", f -> f.physicalColumn("enabled").required(true).asBool());
        eb.field("description", f -> f.physicalColumn("description").asText(500));
        eb.unique("uk_sys_sod_rule_code", "ruleCode");
        eb.temporal(t -> t.allowScheduled(true));
        eb.display("ruleCode");
        eb.listView("default", lv -> lv
            .columns("ruleCode", "leftPermissions", "rightPermissions", "enabled", "description")
            .filters("ruleCode", "enabled")
            .sorts("ruleCode")
            .defaultSort("ruleCode", true));
    });

    public static final EntityDefinition CONTROL = EntityDefinition.define(CONTROL_CHANGE, eb -> {
        eb.physicalTable("sys_control_change_version");
        eb.primaryKey("changeId");
        eb.field("changeId", f -> f.physicalColumn("change_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:control-change"));
        eb.field("targetEntity", f -> f.physicalColumn("target_entity").immutable(true).required(true)
            .asCode(CONTROL_TARGETS_DICTIONARY, RULE, LIMIT, SOD_RULE));
        eb.field("targetId", f -> f.physicalColumn("target_id").immutable(true).asText(36));
        eb.field("changeAction", f -> f.physicalColumn("change_action").immutable(true).required(true)
            .asCode(CHANGE_ACTIONS, ControlChanges.UPSERT, ControlChanges.DELETE));
        eb.field("changeValues", f -> f.physicalColumn("change_values").immutable(true).asText(30_000, true));
        eb.field("effectiveTime", f -> f.physicalColumn("effective_time").immutable(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(500));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(CHANGE_STATUSES, PROPOSED, PUBLISHED, WITHDRAWN));
        eb.field("proposedBy", f -> f.physicalColumn("proposed_by").immutable(true).required(true).asText(100));
        eb.field("publishedBy", f -> f.physicalColumn("published_by").asText(100));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("targetEntity", "changeAction", "status", "reason", "proposedBy", "publishedBy",
                "effectiveTime")
            .filters("targetEntity", "status", "proposedBy")
            .sorts("status"));
    });

    public static final EntityDefinition APPROVAL_REQUEST = EntityDefinition.define(REQUEST, eb -> {
        eb.physicalTable("sys_approval_request_version");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:approval-request"));
        eb.field("subject", f -> f.physicalColumn("subject").immutable(true).required(true).asText(100));
        eb.field("entityId", f -> f.physicalColumn("entity_id").immutable(true).required(true).asText(100));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(REQUEST_STATUSES, PENDING, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED));
        eb.field("preparerId", f -> f.physicalColumn("preparer_id").immutable(true).required(true).asText(100));
        eb.field("ruleId", f -> f.physicalColumn("rule_id").immutable(true).required(true).asText(36));
        eb.field("ruleVersionNo", f -> f.physicalColumn("rule_version_no").immutable(true).required(true)
            .asNumeric(9, 0));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").immutable(true).required(true).asText(64));
        eb.field("levels", f -> f.physicalColumn("levels").immutable(true).required(true).asText(4000, true));
        eb.field("currentLevel", f -> f.physicalColumn("current_level").required(true).asNumeric(9, 0));
        eb.field("facts", f -> f.physicalColumn("facts").immutable(true).required(true).asText(20_000, true));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("subject", "entityId", "status", "currentLevel", "preparerId")
            .filters("subject", "entityId", "status", "preparerId")
            .sorts("subject", "status"));
    });

    public static final EntityDefinition APPROVAL_DECISION = EntityDefinition.define(DECISION, eb -> {
        eb.physicalTable("sys_approval_decision");
        eb.primaryKey("decisionId");
        eb.field("decisionId", f -> f.physicalColumn("decision_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:approval-decision"));
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true)
            .asReference(REQUEST));
        eb.field("levelNo", f -> f.physicalColumn("level_no").immutable(true).required(true).asNumeric(9, 0));
        eb.field("approverId", f -> f.physicalColumn("approver_id").immutable(true).required(true).asText(100));
        eb.field("decision", f -> f.physicalColumn("decision").immutable(true).required(true)
            .asCode(DECISIONS, ApprovalProcesses.APPROVE, ApprovalProcesses.REJECT));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).asText(500));
        eb.field("decidedTime", f -> f.physicalColumn("decided_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.listView("default", lv -> lv
            .columns("requestId", "levelNo", "approverId", "decision", "reason", "decidedTime")
            .filters("requestId", "approverId", "decision")
            .sorts("decidedTime", "levelNo")
            .defaultSort("decidedTime", false));
    });

    public static final EntityDefinition APPROVAL_EVALUATION = EntityDefinition.define(EVALUATION, eb -> {
        eb.physicalTable("sys_approval_evaluation");
        eb.primaryKey("evaluationId");
        eb.field("evaluationId", f -> f.physicalColumn("evaluation_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:platform:approval-evaluation"));
        eb.field("subject", f -> f.physicalColumn("subject").immutable(true).required(true).asText(100));
        eb.field("entityId", f -> f.physicalColumn("entity_id").immutable(true).required(true).asText(100));
        eb.field("outcome", f -> f.physicalColumn("outcome").immutable(true).required(true)
            .asCode(OUTCOMES, ApprovalOutcome.Status.codes()));
        eb.field("matchedRule", f -> f.physicalColumn("matched_rule").immutable(true).asText(100));
        eb.field("ruleVersions", f -> f.physicalColumn("rule_versions").immutable(true).required(true)
            .asText(20_000, true));
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).asText(36));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").immutable(true).required(true).asText(64));
        eb.field("facts", f -> f.physicalColumn("facts").immutable(true).required(true).asText(20_000, true));
        eb.field("businessTime", f -> f.physicalColumn("business_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("evaluatedTime", f -> f.physicalColumn("evaluated_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.listView("default", lv -> lv
            .columns("subject", "entityId", "outcome", "matchedRule", "requestId", "evaluatedTime")
            .filters("subject", "entityId", "outcome", "requestId")
            .sorts("evaluatedTime")
            .defaultSort("evaluatedTime", false));
    });

    @Bean
    EntityDefinition sysApprovalRuleEntity() {
        return APPROVAL_RULE;
    }

    @Bean
    EntityDefinition sysApprovalLimitEntity() {
        return APPROVAL_LIMIT;
    }

    @Bean
    EntityDefinition sysSodRuleEntity() {
        return SOD;
    }

    @Bean
    EntityDefinition sysControlChangeEntity() {
        return CONTROL;
    }

    @Bean
    EntityDefinition sysApprovalRequestEntity() {
        return APPROVAL_REQUEST;
    }

    @Bean
    EntityDefinition approvalDecisionEntity() {
        return APPROVAL_DECISION;
    }

    @Bean
    EntityDefinition approvalEvaluationEntity() {
        return APPROVAL_EVALUATION;
    }

    @Bean
    DatasetDefinition sysApprovalRuleDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        // Rules are read as of a case's business time (RequireApproval).
        return dataset(RULE_DATASET, RULE, ApprovalPermissions.READ, ApprovalPermissions.CONTROL_PUBLISH, true,
            poolRef);
    }

    @Bean
    DatasetDefinition sysApprovalLimitDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(LIMIT_DATASET, LIMIT, ApprovalPermissions.READ, ApprovalPermissions.CONTROL_PUBLISH, true,
            poolRef);
    }

    @Bean
    DatasetDefinition sysSodRuleDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(SOD_RULE_DATASET, SOD_RULE, ApprovalPermissions.SOD_READ, ApprovalPermissions.CONTROL_PUBLISH,
            true, poolRef);
    }

    @Bean
    DatasetDefinition sysControlChangeDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CONTROL_CHANGE_DATASET, CONTROL_CHANGE, ApprovalPermissions.READ,
            ApprovalPermissions.CONTROL_PROPOSE, false, poolRef);
    }

    @Bean
    DatasetDefinition sysApprovalRequestDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(REQUEST_DATASET, REQUEST, ApprovalPermissions.READ, ApprovalPermissions.DECIDE, false, poolRef);
    }

    @Bean
    DatasetDefinition approvalDecisionDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(DECISION_DATASET, DECISION, ApprovalPermissions.READ, ApprovalPermissions.DECIDE, false,
            poolRef);
    }

    @Bean
    DatasetDefinition approvalEvaluationDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(EVALUATION_DATASET, EVALUATION, ApprovalPermissions.READ, ApprovalPermissions.DECIDE, false,
            poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String read, String write, boolean timeTravel,
        String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(read, write)
            .policy(p -> {
                p.processOnlyWrites().maxQueryBatchSize(MAX_ROWS);
                if (timeTravel) {
                    p.allowTimeTravel(true);
                }
            })
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

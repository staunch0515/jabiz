package com.jabiz.runtime.security.protect;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.security.SecurityPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * A protected change waiting for, or done after, a second administrator's approval (decision D48; docs/design/10
 * section 16): a temporal platform entity written by its processes only. What the change does (kind, user, role,
 * permission, data period, identity, the new password's hash) never changes after the request; only the status
 * moves on. The new password's hash is sensitive like the user's own.
 */
@Configuration
public class ProtectedChangeEntities {

    public static final String CHANGE = "SecProtectedChange";
    public static final String CHANGE_DATASET = "urn:jabiz:dataset:platform:SecProtectedChange";

    /** The approval subject of every protected change; the platform fixes its levels, no rule is read. */
    public static final String SUBJECT = "jabiz.security.protected-change";

    /** Kinds of protected changes. */
    public static final String USER_ROLE = "USER_ROLE";
    public static final String ROLE_PERMISSION = "ROLE_PERMISSION";
    public static final String PASSWORD = "PASSWORD";
    public static final String MFA_RESET = "MFA_RESET";
    public static final String IDENTITY_LINK = "IDENTITY_LINK";
    /** A new address for a holder of a protected permission (review M-b): the address is a way back into the account. */
    public static final String EMAIL = "EMAIL";
    public static final List<String> KINDS = List.of(USER_ROLE, ROLE_PERMISSION, PASSWORD, MFA_RESET, IDENTITY_LINK,
        EMAIL);

    /** Statuses. */
    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String FAILED = "FAILED";

    public static final EntityDefinition SEC_PROTECTED_CHANGE = EntityDefinition.define(CHANGE, eb -> {
        eb.physicalTable("sec_protected_change_version");
        eb.primaryKey("changeId");
        eb.field("changeId", f -> f.physicalColumn("change_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:protected-change"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).asText(20));
        // Ids, not references: a change on record must not keep its user or role from being deleted.
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).asText(36));
        eb.field("roleId", f -> f.physicalColumn("role_id").immutable(true).asText(36));
        eb.field("permission", f -> f.physicalColumn("permission").immutable(true).asText(200));
        eb.field("dataFrom", f -> f.physicalColumn("data_from").immutable(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("dataTo", f -> f.physicalColumn("data_to").immutable(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("effectiveTime", f -> f.physicalColumn("effective_time").immutable(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("provider", f -> f.physicalColumn("provider").immutable(true).asText(40));
        eb.field("subject", f -> f.physicalColumn("subject").immutable(true).asText(255));
        eb.field("email", f -> f.physicalColumn("email").immutable(true).asText(320));
        eb.field("passwordHash", f -> f.physicalColumn("password_hash").immutable(true).asText(100).sensitive());
        // The second factor the reset removes: another enrolment made since is not removed by this approval.
        eb.field("mfaId", f -> f.physicalColumn("mfa_id").immutable(true).asText(36));
        // What the approval covers (decision D48, review H1): the protected permissions gained (or, for a credential,
        // held) and the users gaining them, as when it was requested; applying more is refused.
        eb.field("gained", f -> f.physicalColumn("gained").immutable(true).asText(2000));
        eb.field("affectedUsers", f -> f.physicalColumn("affected_users").immutable(true).asText(8000));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(500));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(100));
        eb.field("status", f -> f.physicalColumn("status").required(true).asText(20).processOnly());
        eb.field("decidedBy", f -> f.physicalColumn("decided_by").asText(100).processOnly());
        eb.field("resultId", f -> f.physicalColumn("result_id").asText(36).processOnly());
        eb.field("failure", f -> f.physicalColumn("failure").asText(200).processOnly());
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("kind", "status", "userId", "roleId", "permission", "requestedBy", "decidedBy", "reason",
                "effectStartTime")
            .filters("kind", "status", "userId", "roleId", "requestedBy")
            .sorts("status", "kind"));
    });

    @Bean
    EntityDefinition secProtectedChangeEntity() {
        return SEC_PROTECTED_CHANGE;
    }

    @Bean
    DatasetDefinition secProtectedChangeDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(CHANGE_DATASET, d -> d
            .targetEntityType(CHANGE)
            .asDefault()
            .permissions(ApprovalPermissions.READ, SecurityPermissions.USER_ROLE_WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(1000))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    ApprovalSubject protectedChangeApprovalSubject() {
        return ApprovalSubject.define(SUBJECT, s -> s.entity(CHANGE).text("kind").text("user").text("role")
            .text("gained").text("affected").text("credentials").text("address").text("history").text("newAddress")
            .text("permission"));
    }
}

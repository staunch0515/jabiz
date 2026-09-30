package com.jabiz.runtime.task;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tasks and notifications as platform entities (docs/design/18-numbering-approvals-tasks.md section 5): a
 * {@code SysTask} (temporal) is created and closed by processes only ({@link CreateTask}, {@link CloseTasks}, the
 * approval steps); a {@code Notification} (append-only) records one message about a task to one recipient.
 */
@Configuration
public class TaskEntities {

    public static final String TASK = "SysTask";
    public static final String NOTIFICATION = "Notification";
    public static final String TASK_DATASET = "urn:jabiz:dataset:platform:SysTask";
    public static final String NOTIFICATION_DATASET = "urn:jabiz:dataset:platform:Notification";

    static final String STATUSES = "urn:jabiz:dict:task:status";

    /** Status of a task. */
    public static final String OPEN = "OPEN";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    static final int MAX_ROWS = 1000;

    public static final EntityDefinition SYS_TASK = EntityDefinition.define(TASK, eb -> {
        eb.physicalTable("sys_task_version");
        eb.primaryKey("taskId");
        eb.field("taskId", f -> f.physicalColumn("task_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:task"));
        eb.field("taskType", f -> f.physicalColumn("task_type").immutable(true).required(true).asText(100));
        eb.field("titleKey", f -> f.physicalColumn("title_key").immutable(true).required(true).asText(200));
        eb.field("titleParams", f -> f.physicalColumn("title_params").immutable(true).required(true)
            .asText(4000, true));
        eb.field("assigneeUserId", f -> f.physicalColumn("assignee_user_id").immutable(true).asText(100));
        eb.field("assigneePermission", f -> f.physicalColumn("assignee_permission").immutable(true).asText(200));
        eb.field("subjectEntity", f -> f.physicalColumn("subject_entity").immutable(true).asText(100));
        eb.field("subjectId", f -> f.physicalColumn("subject_id").immutable(true).asText(100));
        eb.field("link", f -> f.physicalColumn("link").immutable(true).asText(500));
        eb.field("status", f -> f.physicalColumn("status").required(true).asCode(STATUSES, OPEN, DONE, CANCELLED));
        eb.field("dueTime", f -> f.physicalColumn("due_time").immutable(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("sourceKey", f -> f.physicalColumn("source_key").immutable(true).asText(200));
        eb.field("closedBy", f -> f.physicalColumn("closed_by").asText(100));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("taskType", "titleKey", "assigneeUserId", "assigneePermission", "status", "dueTime",
                "subjectEntity", "subjectId")
            .filters("taskType", "status", "assigneeUserId", "assigneePermission", "sourceKey")
            .sorts("dueTime", "status"));
    });

    public static final EntityDefinition NOTIFICATION_ENTITY = EntityDefinition.define(NOTIFICATION, eb -> {
        eb.physicalTable("sys_notification");
        eb.primaryKey("notificationId");
        eb.field("notificationId", f -> f.physicalColumn("notification_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:platform:notification"));
        eb.field("taskId", f -> f.physicalColumn("task_id").immutable(true).required(true).asReference(TASK));
        eb.field("channel", f -> f.physicalColumn("channel").immutable(true).required(true).asText(20));
        eb.field("recipientId", f -> f.physicalColumn("recipient_id").immutable(true).required(true).asText(100));
        eb.field("address", f -> f.physicalColumn("address").immutable(true).required(true).asText(320));
        eb.field("subject", f -> f.physicalColumn("subject").immutable(true).required(true).asText(300));
        eb.field("body", f -> f.physicalColumn("body").immutable(true).required(true).asText(4000, true));
        eb.field("createdTime", f -> f.physicalColumn("created_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.listView("default", lv -> lv
            .columns("taskId", "recipientId", "address", "subject", "createdTime")
            .filters("taskId", "recipientId")
            .sorts("createdTime")
            .defaultSort("createdTime", false));
    });

    @Bean
    EntityDefinition sysTaskEntity() {
        return SYS_TASK;
    }

    @Bean
    EntityDefinition notificationEntity() {
        return NOTIFICATION_ENTITY;
    }

    @Bean
    DatasetDefinition sysTaskDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(TASK_DATASET, TASK, poolRef);
    }

    @Bean
    DatasetDefinition notificationDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(NOTIFICATION_DATASET, NOTIFICATION, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(TaskPermissions.READ, TaskPermissions.WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

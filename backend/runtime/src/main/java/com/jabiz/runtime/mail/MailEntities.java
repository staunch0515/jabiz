package com.jabiz.runtime.mail;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.mail.MailCategory;
import com.jabiz.runtime.security.SecurityEntities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * Template mail as platform entities (docs/design/18-numbering-approvals-tasks.md section 5.6): a {@code MailMessage}
 * (append-only) is one mail a process decided to send, written by {@code SendMail} through the change set (audited
 * like other entity writes); a {@code MailAttempt} (append-only) is one try to send it, a delivery log the platform
 * inserts itself and that is not entity data (no audit record): its dataset is read-only. The latest attempt of a
 * message is its state.
 */
@Configuration
public class MailEntities {

    public static final String MESSAGE = "MailMessage";
    public static final String ATTEMPT = "MailAttempt";
    public static final String MESSAGE_DATASET = "urn:jabiz:dataset:platform:MailMessage";
    public static final String ATTEMPT_DATASET = "urn:jabiz:dataset:platform:MailAttempt";

    static final String CATEGORIES = "urn:jabiz:dict:mail:category";
    static final String OUTCOMES = "urn:jabiz:dict:mail:outcome";

    /** Outcome of an attempt. */
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    static final int MAX_ROWS = 1000;

    public static final EntityDefinition MESSAGE_ENTITY = EntityDefinition.define(MESSAGE, eb -> {
        eb.physicalTable("sys_mail_message");
        eb.primaryKey("messageId");
        eb.field("messageId", f -> f.physicalColumn("message_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:mail-message"));
        eb.field("template", f -> f.physicalColumn("template").immutable(true).required(true).asText(100));
        eb.field("category", f -> f.physicalColumn("category").immutable(true).required(true)
            .asCode(CATEGORIES, Arrays.stream(MailCategory.values()).map(Enum::name).toArray(String[]::new)));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).asReference(SecurityEntities.USER));
        eb.field("address", f -> f.physicalColumn("address").immutable(true).required(true).asText(320));
        eb.field("locale", f -> f.physicalColumn("locale").immutable(true).required(true).asText(10));
        // JSON of the parameters, sensitive ones masked; like the title parameters of tasks.
        eb.field("params", f -> f.physicalColumn("params").immutable(true).required(true).asText(null, true));
        eb.field("createdTime", f -> f.physicalColumn("created_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.listView("default", lv -> lv
            .columns("template", "category", "address", "locale", "createdTime")
            .filters("template", "category", "userId", "address", "createdTime")
            .sorts("createdTime")
            .defaultSort("createdTime", false));
    });

    public static final EntityDefinition ATTEMPT_ENTITY = EntityDefinition.define(ATTEMPT, eb -> {
        eb.physicalTable("sys_mail_attempt");
        eb.primaryKey("attemptId");
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:mail-attempt"));
        eb.field("messageId", f -> f.physicalColumn("message_id").immutable(true).required(true)
            .asReference(MESSAGE));
        eb.field("attemptNo", f -> f.physicalColumn("attempt_no").immutable(true).required(true).asNumeric(9, 0));
        eb.field("outcome", f -> f.physicalColumn("outcome").immutable(true).required(true)
            .asCode(OUTCOMES, SENT, FAILED, SKIPPED));
        eb.field("detail", f -> f.physicalColumn("detail").immutable(true).asText(2000, true));
        eb.field("attemptedTime", f -> f.physicalColumn("attempted_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.listView("default", lv -> lv
            .columns("messageId", "attemptNo", "outcome", "detail", "attemptedTime")
            .filters("messageId", "outcome", "attemptedTime")
            .sorts("attemptedTime", "attemptNo")
            .defaultSort("attemptedTime", false));
    });

    @Bean
    EntityDefinition mailMessageEntity() {
        return MESSAGE_ENTITY;
    }

    @Bean
    EntityDefinition mailAttemptEntity() {
        return ATTEMPT_ENTITY;
    }

    @Bean
    DatasetDefinition mailMessageDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(MESSAGE_DATASET, MESSAGE, false, poolRef);
    }

    @Bean
    DatasetDefinition mailAttemptDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        // Read-only: attempts are a delivery log the platform writes itself, like sys_notification_attempt and the
        // operation tables, outside the entity write path and its auditing (docs/design/21-audit-retention.md section 1).
        return dataset(ATTEMPT_DATASET, ATTEMPT, true, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, boolean readOnly, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(MailPermissions.READ, MailPermissions.WRITE)
            .policy(p -> (readOnly ? p.readOnly(true) : p.processOnlyWrites()).maxQueryBatchSize(MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

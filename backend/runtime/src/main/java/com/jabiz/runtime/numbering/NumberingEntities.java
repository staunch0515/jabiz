package com.jabiz.runtime.numbering;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The numbers issued by every sequence, as the platform entity {@code NumberAssignment} (docs/design/18-numbering-
 * approvals-tasks.md section 2): one row per number, written once by the step {@link AssignNumber} in the process
 * that drew it, never changed (the table is append-only). Readable for completeness checks: per sequence and scope
 * the values are 1 … n without gaps.
 */
@Configuration
public class NumberingEntities {

    public static final String ENTITY = "NumberAssignment";
    public static final String DATASET = "urn:jabiz:dataset:platform:NumberAssignment";
    static final String TABLE = "sys_number_assignment";

    public static final String ASSIGNMENT_ID = "assignmentId";
    public static final String SEQUENCE_NAME = "sequenceName";
    public static final String SCOPE_KEY = "scopeKey";
    public static final String VALUE_NO = "valueNo";
    public static final String NUMBER = "number";
    public static final String PROCESS_SEQ_ID = "processSeqId";
    public static final String ASSIGNED_TIME = "assignedTime";

    public static final EntityDefinition NUMBER_ASSIGNMENT = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable(TABLE);
        eb.primaryKey(ASSIGNMENT_ID);
        eb.field(ASSIGNMENT_ID, f -> f.physicalColumn("assignment_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:number-assignment"));
        eb.field(SEQUENCE_NAME, f -> f.physicalColumn("sequence_name").immutable(true).required(true).asText(100));
        eb.field(SCOPE_KEY, f -> f.physicalColumn("scope_key").immutable(true).required(true).asText(100));
        eb.field(VALUE_NO, f -> f.physicalColumn("value_no").immutable(true).required(true).asNumeric(19, 0));
        eb.field(NUMBER, f -> f.physicalColumn("number").immutable(true).required(true).asText(200));
        eb.field(PROCESS_SEQ_ID, f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.field(ASSIGNED_TIME, f -> f.physicalColumn("assigned_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.listView("default", lv -> lv
            .columns(SEQUENCE_NAME, SCOPE_KEY, VALUE_NO, NUMBER, ASSIGNED_TIME, PROCESS_SEQ_ID)
            .filters(SEQUENCE_NAME, SCOPE_KEY, NUMBER, VALUE_NO)
            .sorts(VALUE_NO, ASSIGNED_TIME)
            .defaultSort(ASSIGNED_TIME, false));
    });

    @Bean
    EntityDefinition numberAssignmentEntity() {
        return NUMBER_ASSIGNMENT;
    }

    @Bean
    DatasetDefinition numberAssignmentDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions(NumberingPermissions.READ, NumberingPermissions.WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(500))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

package com.jabiz.runtime.security;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.runtime.dictionary.LabelsKindSupport;
import com.jabiz.security.LoginOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Users, roles, permissions, menus and login records as temporal platform entities, each with its default dataset
 * (docs/design/10-security.md section 3). Like every other entity they are read and changed through their datasets
 * and processes; the password hash is sensitive and only the password processes set it.
 */
@Configuration
public class SecurityEntities {

    public static final String USER = "SecUser";
    public static final String ROLE = "SecRole";
    public static final String ROLE_PERMISSION = "SecRolePermission";
    public static final String USER_ROLE = "SecUserRole";
    public static final String MENU = "SecMenu";
    public static final String LOGIN_RECORD = "SecLoginRecord";

    public static final String USER_DATASET = "urn:jabiz:dataset:platform:SecUser";
    public static final String ROLE_DATASET = "urn:jabiz:dataset:platform:SecRole";
    public static final String ROLE_PERMISSION_DATASET = "urn:jabiz:dataset:platform:SecRolePermission";
    public static final String USER_ROLE_DATASET = "urn:jabiz:dataset:platform:SecUserRole";
    public static final String MENU_DATASET = "urn:jabiz:dataset:platform:SecMenu";
    public static final String LOGIN_RECORD_DATASET = "urn:jabiz:dataset:platform:SecLoginRecord";

    public static final String LOGIN_OUTCOME_DICTIONARY = "urn:jabiz:dict:platform:login-outcome";

    public static final EntityDefinition SEC_USER = EntityDefinition.define(USER, eb -> {
        eb.physicalTable("sec_user_version");
        eb.primaryKey("userId");
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:user"));
        eb.field("userName", f -> f.physicalColumn("user_name").immutable(true).required(true).asText(100));
        eb.field("displayName", f -> f.physicalColumn("display_name").asText(200));
        eb.field("tenantId", f -> f.physicalColumn("tenant_id").asText(100));
        eb.field("enabled", f -> f.physicalColumn("enabled").required(true).asBool());
        eb.field("passwordHash", f -> f.physicalColumn("password_hash").asText(100).sensitive());
        eb.unique("uk_sec_user_name", "userName");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("userName", "displayName", "tenantId", "enabled")
            .filters("userName", "tenantId", "enabled")
            .sorts("userName")
            .defaultSort("userName", true));
    });

    public static final EntityDefinition SEC_ROLE = EntityDefinition.define(ROLE, eb -> {
        eb.physicalTable("sec_role_version");
        eb.primaryKey("roleId");
        eb.field("roleId", f -> f.physicalColumn("role_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:role"));
        eb.field("roleCode", f -> f.physicalColumn("role_code").immutable(true).required(true).asText(100));
        eb.field("labels", f -> f.physicalColumn("labels").required(true)
            .asCustom(LabelsKindSupport.KIND_ID, Map.of()));
        eb.field("enabled", f -> f.physicalColumn("enabled").required(true).asBool());
        eb.unique("uk_sec_role_code", "roleCode");
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns("roleCode", "labels", "enabled")
            .filters("roleCode", "enabled")
            .sorts("roleCode")
            .defaultSort("roleCode", true));
    });

    public static final EntityDefinition SEC_ROLE_PERMISSION = EntityDefinition.define(ROLE_PERMISSION, eb -> {
        eb.physicalTable("sec_role_permission_version");
        eb.primaryKey("rolePermissionId");
        eb.field("rolePermissionId", f -> f.physicalColumn("role_permission_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:platform:role-permission"));
        eb.field("roleId", f -> f.physicalColumn("role_id").immutable(true).required(true).asReference(ROLE));
        eb.field("permission", f -> f.physicalColumn("permission").immutable(true).required(true).asText(200));
        eb.unique("uk_sec_role_permission", "roleId", "permission");
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns("roleId", "permission")
            .filters("roleId", "permission")
            .sorts("permission")
            .defaultSort("permission", true));
    });

    public static final EntityDefinition SEC_USER_ROLE = EntityDefinition.define(USER_ROLE, eb -> {
        eb.physicalTable("sec_user_role_version");
        eb.primaryKey("userRoleId");
        eb.field("userRoleId", f -> f.physicalColumn("user_role_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:user-role"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asReference(USER));
        eb.field("roleId", f -> f.physicalColumn("role_id").immutable(true).required(true).asReference(ROLE));
        eb.unique("uk_sec_user_role", "userId", "roleId");
        // An assignment can be scheduled: the role is in effect from its effective time on.
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns("userId", "roleId", "effectStartTime")
            .filters("userId", "roleId"));
    });

    public static final EntityDefinition SEC_MENU = EntityDefinition.define(MENU, eb -> {
        eb.physicalTable("sec_menu_version");
        eb.primaryKey("menuId");
        eb.field("menuId", f -> f.physicalColumn("menu_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:menu"));
        eb.field("menuCode", f -> f.physicalColumn("menu_code").immutable(true).required(true).asText(100));
        eb.field("parentCode", f -> f.physicalColumn("parent_code").asText(100));
        eb.field("labels", f -> f.physicalColumn("labels").required(true)
            .asCustom(LabelsKindSupport.KIND_ID, Map.of()));
        eb.field("path", f -> f.physicalColumn("path").asText(500));
        eb.field("icon", f -> f.physicalColumn("icon").asText(100));
        eb.field("sortOrder", f -> f.physicalColumn("sort_order").required(true).asNumeric(9, 0));
        // Default deny: a menu entry is shown only to holders of its permission.
        eb.field("permission", f -> f.physicalColumn("permission").required(true).asText(200));
        eb.field("enabled", f -> f.physicalColumn("enabled").required(true).asBool());
        eb.unique("uk_sec_menu_code", "menuCode");
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns("menuCode", "parentCode", "labels", "path", "sortOrder", "permission", "enabled")
            .filters("menuCode", "parentCode", "enabled")
            .sorts("sortOrder", "menuCode")
            .defaultSort("sortOrder", true));
    });

    public static final EntityDefinition SEC_LOGIN_RECORD = EntityDefinition.define(LOGIN_RECORD, eb -> {
        eb.physicalTable("sec_login_record_version");
        eb.primaryKey("loginRecordId");
        eb.field("loginRecordId", f -> f.physicalColumn("login_record_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:platform:login-record"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asReference(USER));
        eb.field("userName", f -> f.physicalColumn("user_name").immutable(true).required(true).asText(100));
        eb.field("attemptNo", f -> f.physicalColumn("attempt_no").immutable(true).required(true).asNumeric(18, 0));
        eb.field("outcome", f -> f.physicalColumn("outcome").immutable(true).required(true)
            .asCode(LOGIN_OUTCOME_DICTIONARY, LoginOutcome.codes()));
        eb.field("failureCount", f -> f.physicalColumn("failure_count").immutable(true).required(true)
            .asNumeric(9, 0));
        eb.field("lockedUntil", f -> f.physicalColumn("locked_until").immutable(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("attemptTime", f -> f.physicalColumn("attempt_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).asText(64));
        // Concurrent attempts cannot both build on the same latest record (decision D6 locks the pair).
        eb.unique("uk_sec_login_record_attempt", "userId", "attemptNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("userName", "attemptNo", "outcome", "failureCount", "lockedUntil", "attemptTime")
            .filters("userId", "userName", "outcome", "attemptTime")
            .sorts("attemptTime", "attemptNo")
            .defaultSort("attemptTime", false));
    });

    @Bean
    EntityDefinition secUserEntity() {
        return SEC_USER;
    }

    @Bean
    EntityDefinition secRoleEntity() {
        return SEC_ROLE;
    }

    @Bean
    EntityDefinition secRolePermissionEntity() {
        return SEC_ROLE_PERMISSION;
    }

    @Bean
    EntityDefinition secUserRoleEntity() {
        return SEC_USER_ROLE;
    }

    @Bean
    EntityDefinition secMenuEntity() {
        return SEC_MENU;
    }

    @Bean
    EntityDefinition secLoginRecordEntity() {
        return SEC_LOGIN_RECORD;
    }

    @Bean
    DatasetDefinition secUserDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(USER_DATASET, USER, SecurityPermissions.USER_READ, SecurityPermissions.USER_WRITE, poolRef);
    }

    @Bean
    DatasetDefinition secRoleDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ROLE_DATASET, ROLE, SecurityPermissions.ROLE_READ, SecurityPermissions.ROLE_WRITE, poolRef);
    }

    @Bean
    DatasetDefinition secRolePermissionDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ROLE_PERMISSION_DATASET, ROLE_PERMISSION, SecurityPermissions.ROLE_READ,
            SecurityPermissions.ROLE_WRITE, poolRef);
    }

    @Bean
    DatasetDefinition secUserRoleDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(USER_ROLE_DATASET, USER_ROLE, SecurityPermissions.USER_ROLE_READ,
            SecurityPermissions.USER_ROLE_WRITE, poolRef);
    }

    @Bean
    DatasetDefinition secMenuDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(MENU_DATASET, MENU, SecurityPermissions.MENU_READ, SecurityPermissions.MENU_WRITE, poolRef);
    }

    @Bean
    DatasetDefinition secLoginRecordDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(LOGIN_RECORD_DATASET, LOGIN_RECORD, SecurityPermissions.LOGIN_RECORD_READ,
            SecurityPermissions.LOGIN_RECORD_WRITE, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String read, String write, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(read, write)
            // Access and menus are read whole (Rbac.MAX_ROWS), never a first page of them.
            .policy(p -> p.maxQueryBatchSize(Rbac.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

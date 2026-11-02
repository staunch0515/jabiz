package com.jabiz.runtime.push;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform entities of push notifications (decision D69): the devices users registered ({@value #DEVICE}, an
 * ordinary table; the endpoint and keys are sensitive: never returned, {@code ***} in the audit) and their choices per
 * template ({@value #PREFERENCE}, temporal like the mail preferences). Both written by the platform's processes only.
 */
@Configuration
public class PushEntities {

    public static final String DEVICE = "SecUserDevice";
    public static final String DEVICE_DATASET = "urn:jabiz:dataset:platform:SecUserDevice";
    public static final String PREFERENCE = "SecUserPushPreference";
    public static final String PREFERENCE_DATASET = "urn:jabiz:dataset:platform:SecUserPushPreference";

    public static final String WEB_PUSH = "webpush";
    public static final String APNS = "apns";

    public static final EntityDefinition DEVICE_ENTITY = EntityDefinition.define(DEVICE, eb -> {
        eb.physicalTable("sec_user_device");
        eb.primaryKey("deviceId");
        eb.field("deviceId", f -> f.physicalColumn("device_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:user-device"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asReference(SecurityEntities.USER));
        eb.field("entry", f -> f.physicalColumn("entry").immutable(true).required(true).asText(40));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).asText(10));
        eb.field("endpoint", f -> f.physicalColumn("endpoint").immutable(true).required(true).asText(1000)
            .sensitive());
        eb.field("p256dh", f -> f.physicalColumn("p256dh").immutable(true).asText(200).sensitive());
        eb.field("auth", f -> f.physicalColumn("auth").immutable(true).asText(100).sensitive());
        eb.field("sessionId", f -> f.physicalColumn("session_id").required(true).asText(36));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("lastUsedAt", f -> f.physicalColumn("last_used_at").required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.listView("default", lv -> lv
            .columns("userId", "entry", "kind", "createdAt", "lastUsedAt")
            .filters("userId", "entry", "kind")
            .sorts("createdAt", "lastUsedAt")
            .defaultSort("lastUsedAt", false));
    });

    public static final EntityDefinition PREFERENCE_ENTITY = EntityDefinition.define(PREFERENCE, eb -> {
        eb.physicalTable("sec_user_push_preference_version");
        eb.primaryKey("userPushPreferenceId");
        eb.field("userPushPreferenceId", f -> f.physicalColumn("user_push_preference_id").immutable(true)
            .required(true).generated(true).asSemanticIdentity("urn:jabiz:entity:platform:user-push-preference"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asReference(SecurityEntities.USER));
        eb.field("template", f -> f.physicalColumn("template").immutable(true).required(true).asText(100));
        eb.field("subscribed", f -> f.physicalColumn("subscribed").required(true).asBool());
        eb.unique("uk_sec_user_push_preference", "userId", "template");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("userId", "template", "subscribed")
            .filters("userId", "template", "subscribed")
            .sorts("template")
            .defaultSort("template", true));
    });

    @Bean
    EntityDefinition secUserDeviceEntity() {
        return DEVICE_ENTITY;
    }

    @Bean
    EntityDefinition secUserPushPreferenceEntity() {
        return PREFERENCE_ENTITY;
    }

    @Bean
    DatasetDefinition secUserDeviceDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DEVICE_DATASET, d -> d
            .targetEntityType(DEVICE)
            .asDefault()
            .permissions(SecurityPermissions.USER_READ, SecurityPermissions.USER_WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(Rbac.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition secUserPushPreferenceDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(PREFERENCE_DATASET, d -> d
            .targetEntityType(PREFERENCE)
            .asDefault()
            .permissions(SecurityPermissions.USER_READ, SecurityPermissions.USER_WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(Rbac.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

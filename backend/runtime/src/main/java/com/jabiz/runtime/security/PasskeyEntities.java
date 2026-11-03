package com.jabiz.runtime.security;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The passkeys users registered ({@value #PASSKEY}, decision D71; docs/design/10-security.md section 15.16): an ordinary
 * table written by the platform's processes only. The credential's data (its public key) is sensitive: never returned
 * by the dataset, {@code ***} in the audit; the processes read it to check a signature. The name is the user's own
 * words (often their name and device), so the audit keeps it masked too: it goes when the account is erased.
 */
@Configuration
public class PasskeyEntities {

    public static final String PASSKEY = "SecUserPasskey";
    public static final String PASSKEY_DATASET = "urn:jabiz:dataset:platform:SecUserPasskey";

    public static final EntityDefinition PASSKEY_ENTITY = EntityDefinition.define(PASSKEY, eb -> {
        eb.physicalTable("sec_user_passkey");
        eb.primaryKey("passkeyId");
        eb.field("passkeyId", f -> f.physicalColumn("passkey_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:user-passkey"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asReference(SecurityEntities.USER));
        eb.field("credentialId", f -> f.physicalColumn("credential_id").immutable(true).required(true)
            .asText(1400));
        eb.field("credentialData", f -> f.physicalColumn("credential_data").immutable(true).required(true)
            .asText(4000).sensitive());
        eb.field("signCount", f -> f.physicalColumn("sign_count").required(true).asNumeric(18, 0));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(100).auditMasked());
        eb.field("transports", f -> f.physicalColumn("transports").immutable(true).asText(200));
        eb.field("backedUp", f -> f.physicalColumn("backed_up").required(true).asBool());
        eb.field("aaguid", f -> f.physicalColumn("aaguid").immutable(true).asText(36));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("lastUsedAt", f -> f.physicalColumn("last_used_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.unique("uk_sec_user_passkey_credential", "credentialId");
        eb.listView("default", lv -> lv
            .columns("userId", "name", "backedUp", "createdAt", "lastUsedAt")
            .filters("userId")
            .sorts("createdAt", "lastUsedAt")
            .defaultSort("createdAt", false));
    });

    @Bean
    EntityDefinition secUserPasskeyEntity() {
        return PASSKEY_ENTITY;
    }

    @Bean
    DatasetDefinition secUserPasskeyDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(PASSKEY_DATASET, d -> d
            .targetEntityType(PASSKEY)
            .asDefault()
            .permissions(SecurityPermissions.USER_READ, SecurityPermissions.USER_WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(Rbac.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

package com.jabiz.runtime.mail;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.security.Rbac;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The mail layout administrators write (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D72) as the
 * temporal platform entity {@value #ENTITY} ({@code sys_mail_layout_version}): one instance per language, whose
 * versions are the history. A version of kind {@value MailTextEntities#CUSTOM} replaces the default layout (the
 * configuration {@code jabiz.mail.layout.<language>.*}); one of kind {@value MailTextEntities#DEFAULT} returns to it.
 * Written only by {@code MAIL_LAYOUT_RESET} and the publication of a controlled change of target {@value #ENTITY}
 * ({@link MailLayoutControlTarget}), which {@link MailLayoutGuard} enforces; audited as every temporal write.
 */
@Configuration
public class MailLayoutEntities {

    public static final String ENTITY = "MailLayout";
    public static final String DATASET = "urn:jabiz:dataset:platform:MailLayout";

    public static final String LOCALE = "locale";
    public static final String KIND = "kind";
    public static final String DISPLAY_NAME = "displayName";
    public static final String LOGO_URL = "logoUrl";
    public static final String LOGO_ALT = "logoAlt";
    public static final String FOOTER = "footer";
    public static final String DEFAULT_HASH = "defaultHash";

    static final String KINDS = "urn:jabiz:dict:mail:layout-kind";

    public static final EntityDefinition MAIL_LAYOUT = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sys_mail_layout_version");
        eb.primaryKey("mailLayoutId");
        eb.field("mailLayoutId", f -> f.physicalColumn("mail_layout_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:platform:mail-layout"));
        eb.field(LOCALE, f -> f.physicalColumn("locale").immutable(true).required(true).asText(10));
        eb.field(KIND, f -> f.physicalColumn("layout_kind").required(true)
            .asCode(KINDS, MailTextEntities.CUSTOM, MailTextEntities.DEFAULT));
        // The lengths are checked by MailLayoutRules on every write path.
        eb.field(DISPLAY_NAME, f -> f.physicalColumn("display_name").asText(null, false));
        eb.field(LOGO_URL, f -> f.physicalColumn("logo_url").asText(null, false));
        eb.field(LOGO_ALT, f -> f.physicalColumn("logo_alt").asText(null, false));
        eb.field(FOOTER, f -> f.physicalColumn("footer").asText(null, true));
        eb.field(DEFAULT_HASH, f -> f.physicalColumn("default_hash").required(true).asText(64));
        eb.unique("uk_sys_mail_layout", LOCALE);
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns(LOCALE, KIND, DISPLAY_NAME, "effectStartTime")
            .filters(LOCALE, KIND)
            .sorts(LOCALE, "effectStartTime")
            .defaultSort(LOCALE, true));
    });

    @Bean
    EntityDefinition mailLayoutEntity() {
        return MAIL_LAYOUT;
    }

    @Bean
    DatasetDefinition mailLayoutDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions(MailPermissions.TEXT_READ, MailPermissions.TEXT_WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(Rbac.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

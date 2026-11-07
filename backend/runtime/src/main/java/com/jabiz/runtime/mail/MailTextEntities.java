package com.jabiz.runtime.mail;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.security.Rbac;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The texts administrators write for a template's mail (docs/design/18-numbering-approvals-tasks.md section 5.6;
 * decision D72) as the temporal platform entity {@value #ENTITY} ({@code sys_mail_text_version}): one instance per
 * template and language, whose versions are the history. A version of kind {@value #CUSTOM} overrides the default
 * subject and body (the messages {@code mail.<name>.*}); one of kind {@value #DEFAULT} returns to them. Written only
 * by {@code MAIL_TEXT_SAVE}, {@code MAIL_TEXT_RESET} and the publication of a controlled change of target
 * {@value #ENTITY} ({@link MailTextControlTarget}); audited as every temporal write.
 */
@Configuration
public class MailTextEntities {

    public static final String ENTITY = "MailText";
    public static final String DATASET = "urn:jabiz:dataset:platform:MailText";

    public static final String TEMPLATE = "template";
    public static final String LOCALE = "locale";
    public static final String KIND = "kind";
    public static final String SUBJECT = "subject";
    public static final String BODY = "body";
    public static final String DEFAULT_HASH = "defaultHash";
    /** Whether the version publishes a controlled change another administrator approved. */
    public static final String APPROVED = "approved";

    /** Kinds of a version. */
    public static final String CUSTOM = "CUSTOM";
    public static final String DEFAULT = "DEFAULT";

    static final String KINDS = "urn:jabiz:dict:mail:text-kind";

    public static final EntityDefinition MAIL_TEXT = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sys_mail_text_version");
        eb.primaryKey("mailTextId");
        eb.field("mailTextId", f -> f.physicalColumn("mail_text_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:mail-text"));
        eb.field(TEMPLATE, f -> f.physicalColumn("template").immutable(true).required(true).asText(100));
        eb.field(LOCALE, f -> f.physicalColumn("locale").immutable(true).required(true).asText(10));
        eb.field(KIND, f -> f.physicalColumn("text_kind").required(true).asCode(KINDS, CUSTOM, DEFAULT));
        // The lengths are configuration (jabiz.mail.texts.*), checked by MailTextRules on every write path.
        eb.field(SUBJECT, f -> f.physicalColumn("subject").asText(null, false));
        eb.field(BODY, f -> f.physicalColumn("body").asText(null, true));
        eb.field(DEFAULT_HASH, f -> f.physicalColumn("default_hash").required(true).asText(64));
        // A template that came to need approval after its text was saved sends the default until a change of it is
        // approved (the delivery checks it; MailTextGuard lets only an approved publication set it).
        eb.field(APPROVED, f -> f.physicalColumn("approved").required(true).asBool());
        eb.unique("uk_sys_mail_text", TEMPLATE, LOCALE);
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns(TEMPLATE, LOCALE, KIND, SUBJECT, "effectStartTime")
            .filters(TEMPLATE, LOCALE, KIND)
            .sorts(TEMPLATE, LOCALE, "effectStartTime")
            .defaultSort(TEMPLATE, true));
    });

    @Bean
    EntityDefinition mailTextEntity() {
        return MAIL_TEXT;
    }

    @Bean
    DatasetDefinition mailTextDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions(MailPermissions.TEXT_READ, MailPermissions.TEXT_WRITE)
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(Rbac.MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

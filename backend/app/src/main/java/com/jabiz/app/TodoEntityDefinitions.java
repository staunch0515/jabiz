package com.jabiz.app;

import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;

public final class TodoEntityDefinitions extends BaseEntityDefinitions {

    public static final EntityDefinition TODO = EntityDefinition.define("Todo", eb -> {
        eb.physicalTable("todo");
        eb.primaryKey("id");

        eb.field("id", semanticIdentity("id", "urn:jabiz:entity:todo:todo")
            .andThen(f -> f.generated(true)));

        eb.field("title", f -> f.physicalColumn("title")
            .required(true)
            .rule("TITLE_NOT_BLANK", v -> v instanceof String s && !s.isBlank()));

        eb.field("done", f -> f.physicalColumn("done")
            .rule("DONE_IS_BOOLEAN", v -> v instanceof Boolean));

        // Who the entry belongs to; the member dataset shows each actor their own entries only.
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).asText(128));

        eb.field("recordedTime", systemRecordedTime("created_at"));
        eb.field("rowVersion", rowVersion("version"));

        eb.listView("default", lv -> lv
            .columns("title", "done", "ownerId", "recordedTime")
            .filters("title", "done", "ownerId")
            .sorts("title", "recordedTime")
            .defaultSort("recordedTime", false));
    });

    private TodoEntityDefinitions() {}
}

package com.jabiz.runtime.dictionary;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * The database dictionary as a platform entity: {@code SysDictItem}, temporal and schedulable, stored in
 * {@code sys_dict_item_version} (docs/design/02-metamodel.md section 5).
 */
@Configuration
class DictionaryEntityConfig {

    static final String ENTITY = "SysDictItem";
    static final String DATASET = "urn:jabiz:dataset:platform:SysDictItem";

    static final EntityDefinition SYS_DICT_ITEM = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sys_dict_item_version");
        eb.primaryKey("dictItemId");
        eb.field("dictItemId", f -> f.physicalColumn("dict_item_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:dict-item"));
        eb.field("dictUrn", f -> f.physicalColumn("dict_urn").immutable(true).required(true).asText(200));
        eb.field("itemCode", f -> f.physicalColumn("item_code").immutable(true).required(true).asText(100));
        eb.field("labels", f -> f.physicalColumn("labels").required(true)
            .asCustom(LabelsKindSupport.KIND_ID, Map.of()));
        eb.field("sortOrder", f -> f.physicalColumn("sort_order").required(true).asNumeric(9, 0));
        eb.field("enabled", f -> f.physicalColumn("enabled").required(true).asBool());
        eb.unique("uk_sys_dict_item", "dictUrn", "itemCode");
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns("dictUrn", "itemCode", "labels", "sortOrder", "enabled", "effectStartTime")
            .filters("dictUrn", "itemCode", "enabled")
            .sorts("dictUrn", "sortOrder", "itemCode")
            .defaultSort("sortOrder", true));
    });

    @Bean
    EntityDefinition sysDictItemEntityDefinition() {
        return SYS_DICT_ITEM;
    }

    @Bean
    DatasetDefinition sysDictItemDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions("platform.dict.read", "platform.dict.write")
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

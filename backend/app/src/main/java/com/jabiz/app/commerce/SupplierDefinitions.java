package com.jabiz.app.commerce;

import com.jabiz.app.CarrierEntityDefinitions;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Supplier, the example object of the tutorial docs/guide/new-business-object.md: an entity, its dataset and its
 * messages, nothing else. List, form and history pages, the dataset API and the audit trail come from the platform.
 */
@Configuration
public class SupplierDefinitions extends BaseEntityDefinitions {

    public static final String SUPPLIER = "Supplier";
    public static final String DATASET = "urn:jabiz:dataset:default:Supplier";

    public static final EntityDefinition SUPPLIER_ENTITY = EntityDefinition.define(SUPPLIER, eb -> {
        eb.physicalTable("supplier_version");
        eb.primaryKey("supplierId");
        eb.field("supplierId", f -> f.physicalColumn("supplier_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:commerce:supplier"));
        eb.field("supplierCode", f -> f.physicalColumn("supplier_code").immutable(true).required(true).asText(10)
            .apply(Rules.pattern("SUPPLIER_CODE_FORMAT", "[A-Z0-9]{2,10}")));
        eb.field("supplierName", f -> f.physicalColumn("supplier_name").required(true).asText(100)
            .apply(Rules.notBlank("SUPPLIER_NAME_BLANK")));
        eb.field("countryCode", f -> f.physicalColumn("country_code").required(true)
            .asCode(CarrierEntityDefinitions.COUNTRY_DICTIONARY));
        eb.field("leadTimeDays", f -> f.physicalColumn("lead_time_days").required(true).asNumeric(3, 0)
            .apply(Rules.range("SUPPLIER_LEAD_TIME_RANGE", BigDecimal.ONE, new BigDecimal("365"))));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_supplier_code", "supplierCode");
        eb.listView("default", lv -> lv
            .columns("supplierCode", "supplierName", "countryCode", "leadTimeDays", "active")
            .filters("supplierCode", "supplierName", "countryCode", "leadTimeDays", "active")
            .sorts("supplierCode", "supplierName", "leadTimeDays")
            .defaultSort("supplierCode", true));
        eb.temporal(t -> t.allowScheduled(true));
    });

    @Bean
    EntityDefinition supplierEntityDefinition() {
        return SUPPLIER_ENTITY;
    }

    @Bean
    DatasetDefinition supplierDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(SUPPLIER)
            .asDefault()
            .permissions("commerce.supplier.read", "commerce.supplier.write")
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

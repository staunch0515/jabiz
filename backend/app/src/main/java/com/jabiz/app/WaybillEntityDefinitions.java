package com.jabiz.app;

import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.ext.geo.DimensionType;
import com.jabiz.ext.geo.GeoFields;
import com.jabiz.ext.geo.H3AreaGuard;

import java.util.Set;

public final class WaybillEntityDefinitions extends BaseEntityDefinitions {

    public static final String STATUS_DICTIONARY = "urn:ubos:dict:waybill_status";

    /** H3 cells (resolution 8) covering the customs area of Tokyo port. */
    public static final Set<Long> TOKYO_PORT_CUSTOMS_CELLS = Set.of(0x882f516a23ffffL, 0x882f516a21ffffL);

    public static final EntityDefinition WAYBILL = EntityDefinition.define("WaybillTracking", eb -> {
        eb.physicalTable("t_legacy_waybill_2026");
        eb.primaryKey("waybillId");

        eb.field("waybillId", semanticIdentity("f_wb_sn", "urn:ubos:entity:logistics:waybill"));

        eb.field("freightCharge", nonNegativeMonetary("f_charge_amt", "NON_NEGATIVE_CHARGE", "JPY", 0));

        eb.field("totalWeight", GeoFields.rangeQuantity("f_gross_wt", "VALID_MASS_RANGE",
            DimensionType.MASS, "urn:unit:si:kilogram", 10.0, 50000.0));

        eb.field("shippedTime", temporalCausality("f_shipped_timestamp", "TEMPORAL_CAUSALITY", 300));

        eb.field("recordedTime", systemRecordedTime("f_sys_created_at"));

        eb.field("rowVersion", rowVersion("f_version"));

        eb.field("currentLocation", GeoFields.h3Cell("f_current_h3_cell", 8));

        eb.field("status", f -> f.physicalColumn("f_status_code")
            .asCode(STATUS_DICTIONARY, "CREATED", "IN_TRANSIT", "CUSTOMS_CLEARED", "DELIVERED"));

        eb.stateTransitions("status", st -> {
            st.from("CREATED").to("IN_TRANSIT");
            st.from("IN_TRANSIT").to("CUSTOMS_CLEARED");
            st.from("CUSTOMS_CLEARED").to("DELIVERED");
        });

        // Customs clearance happens inside the customs area of the port only.
        eb.guard("TOKYO_PORT_CUSTOMS_AREA", "*", "CUSTOMS_CLEARED",
            H3AreaGuard.within("currentLocation", TOKYO_PORT_CUSTOMS_CELLS));

        eb.listView("default", lv -> lv
            .columns("waybillId", "status", "freightCharge", "totalWeight", "shippedTime")
            .filters("waybillId", "status", "freightCharge", "shippedTime")
            .sorts("waybillId", "shippedTime", "freightCharge")
            .defaultSort("shippedTime", false));
    });

    private WaybillEntityDefinitions() {}
}

package com.jabiz.app;

import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;

public final class CustomsDeclarationEntityDefinitions extends BaseEntityDefinitions {

    /** Customs offices; a database dictionary (sys_dict_item) maintained without redeploying. */
    public static final String PORT_DICTIONARY = "urn:jabiz:dict:customs_port";

    public static final EntityDefinition CUSTOMS_DECLARATION = EntityDefinition.define("CustomsDeclaration", eb -> {
        eb.physicalTable("t_customs_declaration");
        eb.primaryKey("declarationId");

        eb.field("declarationId", semanticIdentity("f_decl_no", "urn:ubos:entity:customs:declaration"));
        // A declaration belongs to an existing waybill; a waybill with declarations cannot be deleted.
        eb.field("waybillRef", f -> f.physicalColumn("f_wb_ref_sn").required(true).asReference("WaybillTracking"));
        eb.field("dutyAmount", nonNegativeMonetary("f_duty_amt", "NON_NEGATIVE_DUTY", "JPY", 0));
        eb.field("portCode", f -> f.physicalColumn("f_port_code").asCode(PORT_DICTIONARY));
        eb.field("rowVersion", rowVersion("f_version"));

        eb.listView("default", lv -> lv
            .columns("declarationId", "waybillRef", "portCode", "dutyAmount")
            .filters("waybillRef", "portCode", "dutyAmount")
            .sorts("declarationId", "dutyAmount")
            .defaultSort("declarationId", true));
    });

    private CustomsDeclarationEntityDefinitions() {}
}

package com.jabiz.app;

import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;

public final class CustomsDeclarationEntityDefinitions extends BaseEntityDefinitions {

    public static final EntityDefinition CUSTOMS_DECLARATION = EntityDefinition.define("CustomsDeclaration", eb -> {
        eb.physicalTable("t_customs_declaration");
        eb.primaryKey("declarationId");

        eb.field("declarationId", semanticIdentity("f_decl_no", "urn:ubos:entity:customs:declaration"));
        eb.field("waybillRef", f -> f.physicalColumn("f_wb_ref_sn").required(true));
        eb.field("dutyAmount", nonNegativeMonetary("f_duty_amt", "NON_NEGATIVE_DUTY", "JPY", 0));
        eb.field("rowVersion", rowVersion("f_version"));

        // A declaration belongs to an existing waybill; a waybill with declarations cannot be deleted.
        eb.reference("waybillRef", "WaybillTracking");
    });

    private CustomsDeclarationEntityDefinitions() {}
}

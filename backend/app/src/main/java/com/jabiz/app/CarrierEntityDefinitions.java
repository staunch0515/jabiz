package com.jabiz.app;

import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.Rules;

import java.math.BigDecimal;

/**
 * Carrier (a transport company), the sample of ROADMAP phase 10: an entity and a dataset declared here, and nothing
 * else. The frontend has no code of its own for it; its list, form and history pages come from the metadata
 * (docs/design/12-frontend.md).
 */
public final class CarrierEntityDefinitions extends BaseEntityDefinitions {

    public static final String COUNTRY_DICTIONARY = "urn:jabiz:dict:country";
    /** Reading carriers' bank accounts in plain text, and writing them. */
    public static final String BANK_ACCOUNT_PERMISSION = "logistics.carrier.bank-account";

    public static final EntityDefinition CARRIER = EntityDefinition.define("Carrier", eb -> {
        eb.physicalTable("carrier_version");
        eb.primaryKey("carrierId");

        eb.field("carrierId", f -> f.physicalColumn("carrier_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:logistics:carrier"));
        eb.field("carrierCode", f -> f.physicalColumn("carrier_code").immutable(true).required(true).asText(10)
            .apply(Rules.pattern("CARRIER_CODE_FORMAT", "[A-Z0-9]{2,10}")));
        eb.field("carrierName", f -> f.physicalColumn("carrier_name").required(true).asText(100)
            .apply(Rules.notBlank("CARRIER_NAME_BLANK")));
        eb.field("countryCode", f -> f.physicalColumn("country_code").required(true).asCode(COUNTRY_DICTIONARY));
        eb.field("creditLimit", f -> f.physicalColumn("credit_limit").required(true).asMonetary("JPY", 0)
            .apply(Rules.range("CREDIT_LIMIT_RANGE", BigDecimal.ZERO, new BigDecimal("100000000")))
            .apply(Rules.scale("CREDIT_LIMIT_SCALE", 0)));
        eb.field("contactEmail", f -> f.physicalColumn("contact_email").asText(200)
            .apply(Rules.pattern("CONTACT_EMAIL_FORMAT", "[^@ ]+@[^@ ]+\\.[^@ ]+")));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        // Shown as ****1234; holders of the permission show one value at a time, each time on the record
        // (docs/design/10-security.md section 13.1).
        eb.field("bankAccount", f -> f.physicalColumn("bank_account").asText(34)
            .masked(BANK_ACCOUNT_PERMISSION, MaskStyle.LAST4));

        eb.unique("uk_carrier_code", "carrierCode");
        eb.listView("default", lv -> lv
            .columns("carrierCode", "carrierName", "countryCode", "creditLimit", "active", "bankAccount",
                "effectStartTime")
            // Only holders of the bank account permission may filter and sort by it.
            .filters("carrierCode", "carrierName", "countryCode", "creditLimit", "active", "bankAccount")
            .sorts("carrierCode", "carrierName", "creditLimit", "bankAccount", "effectStartTime")
            .defaultSort("carrierCode", true));
        eb.temporal(t -> t.allowScheduled(true));
    });

    private CarrierEntityDefinitions() {}
}

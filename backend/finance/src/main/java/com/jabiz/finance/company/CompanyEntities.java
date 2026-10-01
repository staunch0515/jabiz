package com.jabiz.finance.company;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;

/**
 * The company's profile (FIN-AR-005; docs/finance/00-design.md section 8): its legal name, address, contact and the
 * remittance instructions its documents show. Data rather than configuration (platform decision D30), so each issued
 * document keeps the profile of its time. One row, key {@code COMPANY}; temporal and written only by
 * {@link CompanyProcesses}.
 */
public final class CompanyEntities {

    public static final String PROFILE = "FinCompanyProfile";
    public static final String PROFILE_DATASET = "urn:jabiz:dataset:default:FinCompanyProfile";
    public static final String PROFILE_KEY = "COMPANY";

    public static final EntityDefinition PROFILE_ENTITY = EntityDefinition.define(PROFILE, eb -> {
        eb.physicalTable("fi_company_profile_version");
        eb.primaryKey("profileId");
        eb.field("profileId", f -> f.physicalColumn("profile_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:company-profile"));
        eb.field("profileKey", f -> f.physicalColumn("profile_key").immutable(true).required(true).asText(10));
        eb.field("legalName", f -> f.physicalColumn("legal_name").required(true).asText(200)
            .apply(Rules.notBlank("FIN_COMPANY_NAME_BLANK")));
        eb.field("street", f -> f.physicalColumn("street").asText(200));
        eb.field("city", f -> f.physicalColumn("city").asText(100));
        eb.field("state", f -> f.physicalColumn("state").asText(20));
        eb.field("postalCode", f -> f.physicalColumn("postal_code").asText(20));
        eb.field("country", f -> f.physicalColumn("country").asText(60));
        eb.field("phone", f -> f.physicalColumn("phone").asText(40));
        eb.field("email", f -> f.physicalColumn("email").asText(200));
        // Where and how customers pay, as printed on invoices: bank, account, reference to quote.
        eb.field("remittance", f -> f.physicalColumn("remittance").asText(1000, true));
        eb.unique("uk_fi_company_profile_key", "profileKey");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("legalName", "street", "city", "state", "postalCode", "country", "phone", "email")
            .filters("profileKey")
            .sorts("profileKey")
            .defaultSort("profileKey", true));
    });

    private CompanyEntities() {}
}

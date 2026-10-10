package com.jabiz.quizbuks.country;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The country dictionary {@code QbCountry} (docs/quizbuks/02-design.md section 3.1): every ISO 3166-1 country with
 * its name in the three languages and the regions it belongs to. It is a temporal entity rather than a static
 * dictionary because the regions are data (default answer to Q7) that may change, and each change keeps its history.
 * Only processes write it: {@code QB_SETUP} brings the countries in.
 */
@Configuration
public class QbCountries {

    public static final String COUNTRY = "QbCountry";
    public static final String DATASET = "urn:jabiz:dataset:default:QbCountry";

    public static final String CODE_FORMAT = "QB_COUNTRY_CODE_FORMAT";
    public static final String REGIONS_FORMAT = "QB_COUNTRY_REGIONS_FORMAT";

    public static final EntityDefinition COUNTRY_ENTITY = EntityDefinition.define(COUNTRY, eb -> {
        eb.physicalTable("qb_country_version");
        eb.primaryKey("countryId");
        eb.field("countryId", f -> f.physicalColumn("country_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:country"));
        eb.field("code", f -> f.physicalColumn("code").immutable(true).required(true).asText(2)
            .apply(Rules.pattern(CODE_FORMAT, "[A-Z]{2}")));
        eb.field("name", f -> f.physicalColumn("name").required(true)
            .apply(I18nText.of(100).required("en", "zh", "ja")));
        eb.field("regions", f -> f.physicalColumn("regions").required(true).asText(Regions.MAX_LENGTH)
            .apply(Rules.pattern(REGIONS_FORMAT, Regions.CANONICAL.pattern())));
        eb.unique("uk_qb_country_code", "code");
        eb.display("name");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("code", "name", "regions")
            .filters("code", "regions")
            .sorts("code")
            .defaultSort("code", true));
    });

    @Bean
    EntityDefinition qbCountryEntity() {
        return COUNTRY_ENTITY;
    }

    @Bean
    DatasetDefinition qbCountryDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(COUNTRY)
            .asDefault()
            // Written by QB_SETUP only; whoever runs it may write.
            .permissions(QbPermissions.COUNTRY_READ, QbPermissions.SETUP)
            // QB_SETUP brings every country in at once.
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(300))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbRegionDictionary() {
        return StaticDictionary.define(Regions.DICTIONARY, d -> d
            .item(Regions.GLOBAL, "en", "Global", "zh", "全球", "ja", "グローバル")
            .item(Regions.JP, "en", "Japan", "zh", "日本", "ja", "日本")
            .item(Regions.CN, "en", "China", "zh", "中国", "ja", "中国")
            .item(Regions.US, "en", "United States", "zh", "美国", "ja", "アメリカ")
            .item(Regions.EUROPE, "en", "Europe", "zh", "欧洲", "ja", "ヨーロッパ")
            .item(Regions.ASIA, "en", "Asia", "zh", "亚洲", "ja", "アジア")
            .item(Regions.NORTH_AMERICA, "en", "North America", "zh", "北美洲", "ja", "北アメリカ")
            .item(Regions.SOUTH_AMERICA, "en", "South America", "zh", "南美洲", "ja", "南アメリカ"));
    }
}

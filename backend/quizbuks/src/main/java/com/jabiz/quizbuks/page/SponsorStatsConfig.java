package com.jabiz.quizbuks.page;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.PublicCounter;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import com.jabiz.runtime.publicread.PublicCounterEntities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The views of sponsors' pages and the clicks on their products' shop links, by day (Q60c;
 * docs/quizbuks/plans/Q60-sponsor-page.md section 8): platform public counters (decision D65), counted for anonymous
 * and signed-in visitors alike, once per visitor and day, in the days of {@code jabiz.fiscal-year-zone}. A page counts
 * while published and by its handle (the user id is not public); a product while listed. Both name the sponsor as
 * the owner of their keys, so that the sponsor's dataset gives each sponsor theirs only; the owner is the sponsor's
 * account, so their counts go with it (Q70, platform 17z-6).
 */
@Configuration
public class SponsorStatsConfig {

    /** A sponsor page's views, keyed by its handle. */
    public static final String PAGE_VIEWS = "qb.sponsor-page-view";
    /** The clicks on a product's shop link, keyed by the product. */
    public static final String PRODUCT_CLICKS = "qb.product-click";
    /** The sponsor's own counts (both counters), read-only. */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:PublicCounterDay";
    /** The administration's counts, an operator's session limited to its sponsors' (Q60c-2), read-only. */
    public static final String ADMIN_DATASET = "urn:jabiz:dataset:admin:PublicCounterDay";

    private final String zone;
    private final String poolRef;

    SponsorStatsConfig(@Value("${jabiz.fiscal-year-zone:UTC}") String zone,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.zone = zone;
        this.poolRef = poolRef;
    }

    @Bean
    PublicCounter qbSponsorPageViews() {
        return PublicCounter.define(PAGE_VIEWS, c -> c.keys(QbSponsorPages.PUBLIC_DATASET, "handle").owner("userId")
            .ownerIsUser().zone(zone));
    }

    @Bean
    PublicCounter qbProductClicks() {
        return PublicCounter.define(PRODUCT_CLICKS, c -> c.keys(QbSponsorProducts.PUBLIC_DATASET, "productId")
            .owner("userId").ownerIsUser().zone(zone));
    }

    /** The counts of the caller's own page and products: those whose owner is the caller. */
    @Bean
    DatasetDefinition qbMyPublicCounterDataset() {
        return DatasetDefinition.define(MY_DATASET, d -> d
            .targetEntityType(PublicCounterEntities.ENTITY)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false).maxQueryBatchSize(1000))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("ownerKey", "actorId", RequestContext::actorId)));
    }

    /**
     * The counts for headquarters and the operators (the template {@code qb.admin.sponsor-stats}): every sponsor's, an
     * operator's session its own sponsors' only (by the counts' owner, platform decision D64).
     */
    @Bean
    DatasetDefinition qbAdminPublicCounterDataset() {
        return DatasetDefinition.define(ADMIN_DATASET, d -> d
            .targetEntityType(PublicCounterEntities.ENTITY)
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(PublicCounterEntities.ENTITY))
            .policy(p -> p.readOnly(true).allowTimeTravel(false).maxQueryBatchSize(1000))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

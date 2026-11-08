package com.jabiz.quizbuks.play;

import com.jabiz.dataset.PublicCounter;
import com.jabiz.quizbuks.publishing.QbPublications;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F-S10 (docs/quizbuks/plans/impl/F-S10.md): how often the App's full-screen vertical mode is opened, by the publication
 * it opened on and day: a platform public counter (decision D65), once per visitor, key and day, in the days of
 * {@code jabiz.fiscal-year-zone}, only for a live public card. The owner of a key is the publication's sponsor, so that
 * the sponsor's counts dataset ({@code me:PublicCounterDay}) gives them their own and headquarters reads all of them
 * ({@code admin:PublicCounterDay}). F-S13's exposure counts are to share it.
 */
@Configuration
public class ShortsStats {

    /** The openings of the vertical mode, keyed by the publication it opened on. */
    public static final String SHORTS_OPENS = "qb.shorts-open";

    private final String zone;

    ShortsStats(@Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.zone = zone;
    }

    @Bean
    PublicCounter qbShortsOpens() {
        return PublicCounter.define(SHORTS_OPENS, c -> c.keys(QbPublications.PUBLIC_CARDS, "publicationId")
            .owner("publisherId").ownerIsUser().zone(zone));
    }
}

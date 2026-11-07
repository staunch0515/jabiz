package com.jabiz.quizbuks.play;

import com.jabiz.dataset.PublicCounter;
import com.jabiz.quizbuks.publishing.QbPublications;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F-S10 (docs/quizbuks/plans/impl/F-S10.md): how often the App's full-screen vertical mode is opened, by the version
 * of the publication it opened on (the card's {@code versionId}) and day: a platform public counter (decision D65), once
 * per visitor, key and day, in the days of {@code jabiz.fiscal-year-zone}, only for a live public card. Keyed by the
 * version, not the publication: the platform binds the card's primary key ({@code publicationId}, a reference used as
 * the key of an ordinary table) as text against its uuid column (a defect of {@code PublicCounters} to fix on the
 * platform line); the card's {@code versionId} is a plain reference and works. A version's publications are the cards
 * that name it. The owner of a key is the publication's sponsor, so that
 * the sponsor's counts dataset ({@code me:PublicCounterDay}) gives them their own and headquarters reads all of them
 * ({@code admin:PublicCounterDay}). F-S13's exposure counts are to share it.
 */
@Configuration
public class ShortsStats {

    /** The openings of the vertical mode, keyed by the version of the publication it opened on. */
    public static final String SHORTS_OPENS = "qb.shorts-open";

    private final String zone;

    ShortsStats(@Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.zone = zone;
    }

    @Bean
    PublicCounter qbShortsOpens() {
        return PublicCounter.define(SHORTS_OPENS, c -> c.keys(QbPublications.PUBLIC_CARDS, "versionId")
            .owner("publisherId").ownerIsUser().zone(zone));
    }
}

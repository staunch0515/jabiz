package com.jabiz.quizbuks.publishing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.jabiz.quizbuks.publishing.PublicationStatus.DRAFT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.ENDED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.FUNDS_SHORT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.LIVE;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PAUSED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PENDING_REVIEW;
import static com.jabiz.quizbuks.publishing.PublicationStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;

class PublicationRulesTest {

    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-08T00:00:00Z");
    private static final Duration MS = Duration.ofMillis(1);

    @Test
    void onlyALivePublicationWithinItsTimeTakesNewAttempts() {
        assertThat(PublicationRules.acceptsNewAttempts(LIVE, START, END, START.minus(MS))).isFalse();
        assertThat(PublicationRules.acceptsNewAttempts(LIVE, START, END, START)).isTrue();
        assertThat(PublicationRules.acceptsNewAttempts(LIVE, START, END, END.minus(MS))).isTrue();
        assertThat(PublicationRules.acceptsNewAttempts(LIVE, START, END, END)).isFalse();
        for (String status : PublicationStatus.ALL) {
            if (!status.equals(LIVE)) {
                assertThat(PublicationRules.acceptsNewAttempts(status, START, END, START.plusSeconds(60)))
                    .as(status).isFalse();
            }
        }
        assertThat(PublicationRules.acceptsNewAttempts(LIVE, null, END, START)).isFalse();
        assertThat(PublicationRules.acceptsNewAttempts(LIVE, START, null, START)).isFalse();
    }

    @Test
    void theTickMovesScheduledAndRunningPublicationsOnTime() {
        assertThat(PublicationRules.next(SCHEDULED, START, END, START.minus(MS))).isEqualTo(SCHEDULED);
        assertThat(PublicationRules.next(SCHEDULED, START, END, START)).isEqualTo(LIVE);
        assertThat(PublicationRules.next(SCHEDULED, START, END, END)).isEqualTo(ENDED);
        assertThat(PublicationRules.next(LIVE, START, END, END.minus(MS))).isEqualTo(LIVE);
        for (String running : List.of(SCHEDULED, LIVE, PAUSED, FUNDS_SHORT)) {
            assertThat(PublicationRules.next(running, START, END, END)).as(running).isEqualTo(ENDED);
        }
        for (String other : List.of(DRAFT, PENDING_REVIEW, ENDED)) {
            assertThat(PublicationRules.next(other, START, END, END.plusSeconds(1))).as(other).isEqualTo(other);
        }
        assertThat(PublicationRules.next(PAUSED, START, END, START)).isEqualTo(PAUSED);
    }

    @Test
    void resumingGoesWhereTheTimeSays() {
        assertThat(PublicationRules.resumeTo(START, END, START.minus(MS))).isEqualTo(SCHEDULED);
        assertThat(PublicationRules.resumeTo(START, END, START)).isEqualTo(LIVE);
        assertThat(PublicationRules.resumeTo(START, END, END.minus(MS))).isEqualTo(LIVE);
        assertThat(PublicationRules.resumeTo(START, END, END)).isEqualTo(ENDED);
    }

    /** Every move the rules make is one the lifecycle declares (D-Q4-1), so the platform will let it through. */
    @Test
    void everyMoveOfTheRulesIsDeclared() {
        List<Instant> times = List.of(START.minus(MS), START, END.minus(MS), END, END.plusSeconds(1));
        for (String status : PublicationStatus.ALL) {
            for (Instant now : times) {
                String next = PublicationRules.next(status, START, END, now);
                if (!next.equals(status)) {
                    assertThat(QbPublications.PUBLICATION_ENTITY.allowsTransition(status, next))
                        .as(status + " -> " + next).isTrue();
                }
            }
        }
        List<String> resumed = new ArrayList<>();
        times.forEach(now -> resumed.add(PublicationRules.resumeTo(START, END, now)));
        for (String to : resumed) {
            assertThat(QbPublications.PUBLICATION_ENTITY.allowsTransition(PAUSED, to)).as("PAUSED -> " + to)
                .isTrue();
        }
        for (String to : List.of(SCHEDULED, LIVE)) {
            assertThat(QbPublications.PUBLICATION_ENTITY.allowsTransition(PENDING_REVIEW, to)).isTrue();
            assertThat(QbPublications.PUBLICATION_ENTITY.allowsTransition(DRAFT, to)).isTrue();
        }
    }

    /** The lifecycle of D-Q4-1, every pair of states. */
    @Test
    void theLifecycleIsDeclaredExactly() {
        java.util.Map<String, List<String>> declared = java.util.Map.of(
            DRAFT, List.of(PENDING_REVIEW, SCHEDULED, LIVE),
            PENDING_REVIEW, List.of(DRAFT, SCHEDULED, LIVE),
            SCHEDULED, List.of(LIVE, PAUSED, ENDED),
            LIVE, List.of(PAUSED, FUNDS_SHORT, ENDED),
            PAUSED, List.of(SCHEDULED, LIVE, ENDED),
            FUNDS_SHORT, List.of(LIVE, PAUSED, ENDED),
            ENDED, List.of());
        for (String from : PublicationStatus.ALL) {
            for (String to : PublicationStatus.ALL) {
                assertThat(QbPublications.PUBLICATION_ENTITY.allowsTransition(from, to)).as(from + " -> " + to)
                    .isEqualTo(declared.get(from).contains(to));
            }
        }
    }
}

package com.jabiz.quizbuks.certification;

import com.jabiz.quizbuks.certification.CertificationHolding.Entry;
import com.jabiz.quizbuks.certification.Certifications.Certification;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rule of holding a certification (Q69, D-Q69-3 and D-Q69-4). */
class CertificationHoldingTest {

    private static final Instant T = Instant.parse("2026-11-01T00:00:00Z");
    private static final Certification CERT = Certifications.SPONSOR_APPLY;

    private static Entry passed(Instant at) {
        return new Entry(CertificationHolding.PASSED, CERT.version(), at, at.plus(CERT.validity()));
    }

    @Test
    void aPassCountsForAYearFromWhenItWasSubmitted() {
        assertThat(CertificationHolding.heldUntil(List.of(passed(T)), CERT, T.plus(Duration.ofDays(364))))
            .contains(T.plus(Duration.ofDays(365)));
        assertThat(CertificationHolding.heldUntil(List.of(passed(T)), CERT, T.plus(Duration.ofDays(366)))).isEmpty();
        assertThat(CertificationHolding.heldUntil(List.of(), CERT, T)).isEmpty();
    }

    @Test
    void theLatestEndCountsAndOnlyTheCurrentVersion() {
        Instant later = T.plus(Duration.ofDays(100));
        assertThat(CertificationHolding.heldUntil(List.of(passed(T), passed(later)), CERT, later))
            .contains(later.plus(CERT.validity()));
        Certification next = new Certification(CERT.key(), CERT.validity(), CERT.version() + 1);
        assertThat(CertificationHolding.heldUntil(List.of(passed(T)), next, T)).isEmpty();
    }

    @Test
    void aRevocationOutweighsWhatCameBeforeButNotWhatCameAfter() {
        Entry revoked = new Entry(CertificationHolding.REVOKED, CERT.version(), T.plusSeconds(60), null);
        assertThat(CertificationHolding.heldUntil(List.of(passed(T), revoked), CERT, T.plusSeconds(120))).isEmpty();
        Instant again = T.plusSeconds(180);
        assertThat(CertificationHolding.heldUntil(List.of(passed(T), revoked, passed(again)), CERT, again))
            .contains(again.plus(CERT.validity()));
    }

    @Test
    void aGrantCountsAsAPass() {
        Entry granted = new Entry(CertificationHolding.GRANTED, CERT.version(), T, T.plus(CERT.validity()));
        assertThat(CertificationHolding.heldUntil(List.of(granted), CERT, T)).contains(T.plus(CERT.validity()));
    }

    @Test
    void theHoldingIsTheEntryWithTheLatestEndAndStartsWhenItWasRecorded() {
        Instant later = T.plus(Duration.ofDays(100));
        Entry granted = new Entry(CertificationHolding.GRANTED, CERT.version(), later, later.plus(CERT.validity()));
        assertThat(CertificationHolding.holding(List.of(passed(T), granted), CERT, later)).hasValueSatisfying(h -> {
            assertThat(h.kind()).isEqualTo(CertificationHolding.GRANTED);
            assertThat(h.from()).isEqualTo(later);
            assertThat(h.until()).isEqualTo(later.plus(CERT.validity()));
        });
        Entry revoked = new Entry(CertificationHolding.REVOKED, CERT.version(), later.plusSeconds(1), null);
        assertThat(CertificationHolding.holding(List.of(passed(T), granted, revoked), CERT, later.plusSeconds(2)))
            .isEmpty();
    }
}

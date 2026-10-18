package com.jabiz.quizbuks.messaging;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.QbPermissions;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of versioned legal texts (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-4, D-Q7-5): the version in
 * effect, the version to accept, announcements and what a publication may do, for any sequence of publications.
 */
class LegalRulesTest {

    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    /** A horizon no test reaches. */
    static final Instant FAR = T0.plus(Duration.ofDays(3650));

    static LegalRules.Version v(int number, boolean consent, Instant effectiveAt) {
        return new LegalRules.Version(number, consent, effectiveAt, "hash-" + number);
    }

    @Test
    void theVersionInEffectIsTheHighestNumberWhoseTimeHasCome() {
        List<LegalRules.Version> versions = List.of(v(1, true, T0), v(2, false, T0.plusSeconds(10)),
            v(3, true, T0.plusSeconds(100)));
        assertThat(LegalRules.current(versions, T0.minusSeconds(1))).isEmpty();
        assertThat(LegalRules.current(versions, T0)).map(LegalRules.Version::number).contains(1);
        assertThat(LegalRules.current(versions, T0.plusSeconds(50))).map(LegalRules.Version::number).contains(2);
        assertThat(LegalRules.upcoming(versions, T0.plusSeconds(50))).map(LegalRules.Version::number).contains(3);
        assertThat(LegalRules.current(versions, T0.plusSeconds(100))).map(LegalRules.Version::number).contains(3);
        assertThat(LegalRules.upcoming(versions, T0.plusSeconds(100))).isEmpty();
    }

    @Test
    void aRewordingAsksNobodyAgainAChangeOfSubstanceDoesOnceInEffect() {
        List<LegalRules.Version> versions = List.of(v(1, false, T0), v(2, false, T0.plusSeconds(10)),
            v(3, true, T0.plusSeconds(100)));
        // The first version is to accept even if it was marked otherwise.
        assertThat(LegalRules.toAccept(versions, T0.plusSeconds(50))).isEqualTo(1);
        assertThat(LegalRules.mustAccept(1, 1)).isFalse();
        assertThat(LegalRules.toAccept(versions, T0.plusSeconds(100))).isEqualTo(3);
        assertThat(LegalRules.mustAccept(3, 2)).isTrue();
        assertThat(LegalRules.mustAccept(3, 3)).isFalse();
        // Nothing in effect asks nothing.
        assertThat(LegalRules.toAccept(versions, T0.minusSeconds(1))).isZero();
        assertThat(LegalRules.mustAccept(0, 0)).isFalse();
    }

    @Test
    void aPublicationTakesEffectNowOrLaterOnceNothingIsAnnouncedWithANewText() {
        assertThat(LegalRules.publishProblems(List.of(), "h", T0, T0, FAR)).isEmpty();
        assertThat(LegalRules.requiresConsent(List.of(), false)).isTrue();
        assertThat(LegalRules.nextNumber(List.of())).isEqualTo(1);
        List<LegalRules.Version> versions = List.of(v(1, true, T0), v(2, true, T0.plus(Duration.ofDays(7))));
        assertThat(LegalRules.nextNumber(versions)).isEqualTo(3);
        assertThat(LegalRules.requiresConsent(versions, false)).isFalse();
        assertThat(LegalRules.publishProblems(versions, "hash-2", T0.minusSeconds(1), T0.plusSeconds(5), FAR))
            .extracting(Violation::ruleCode)
            .containsExactly(LegalCodes.EFFECTIVE_PAST, LegalCodes.UPCOMING_EXISTS, LegalCodes.UNCHANGED);
        assertThat(LegalRules.publishProblems(versions, "new", T0.plus(Duration.ofDays(8)),
            T0.plus(Duration.ofDays(7)), FAR)).isEmpty();
    }

    @Test
    void anEffectiveTimeBeyondTheHorizonIsRefused() {
        Instant horizon = T0.plus(Duration.ofDays(180));
        assertThat(LegalRules.publishProblems(List.of(), "h", horizon, T0, horizon)).isEmpty();
        assertThat(LegalRules.publishProblems(List.of(), "h", horizon.plusSeconds(1), T0, horizon))
            .extracting(Violation::ruleCode).containsExactly(LegalCodes.EFFECTIVE_TOO_LATE);
        assertThat(LegalRules.horizon(T0, 180)).isEqualTo(horizon);
    }

    @Test
    void aWithdrawnVersionNeverTakesEffectAsksNobodyAndKeepsItsNumber() {
        LegalRules.Version withdrawn = v(2, true, T0.plus(Duration.ofDays(7))).withdrawn();
        List<LegalRules.Version> versions = List.of(v(1, true, T0), withdrawn);
        Instant after = T0.plus(Duration.ofDays(8));
        assertThat(LegalRules.current(versions, after)).map(LegalRules.Version::number).contains(1);
        assertThat(LegalRules.upcoming(versions, T0)).isEmpty();
        assertThat(LegalRules.toAccept(versions, after)).isEqualTo(1);
        assertThat(LegalRules.nextNumber(versions)).isEqualTo(3);
        // Nothing is announced: the next may be published, the withdrawn text again too.
        assertThat(LegalRules.publishProblems(versions, "hash-2", T0.plusSeconds(10), T0.plusSeconds(10), FAR))
            .isEmpty();
        assertThat(LegalRules.publishProblems(versions, "hash-1", T0.plusSeconds(10), T0.plusSeconds(10), FAR))
            .extracting(Violation::ruleCode).containsExactly(LegalCodes.UNCHANGED);
        // Only the first version that is not withdrawn always asks.
        assertThat(LegalRules.requiresConsent(List.of(v(1, true, T0.plusSeconds(5)).withdrawn()), false)).isTrue();

        assertThat(LegalRules.withdrawProblems(v(2, true, T0.plusSeconds(5)), T0)).isEmpty();
        assertThat(LegalRules.withdrawProblems(v(2, true, T0.plusSeconds(5)), T0.plusSeconds(5)))
            .extracting(Violation::ruleCode).containsExactly(LegalCodes.WITHDRAW_IN_EFFECT);
        assertThat(LegalRules.withdrawProblems(withdrawn, T0)).extracting(Violation::ruleCode)
            .containsExactly(LegalCodes.WITHDRAWN);
    }

    @Test
    void theMissingLanguagesAreMissingOrBlank() {
        assertThat(LegalRules.missingLanguages(Map.of("en", "Text", "ja", " "), List.of("ja", "en")))
            .containsExactly("ja");
        assertThat(LegalRules.missingLanguages(null, List.of("ja", "en"))).containsExactly("ja", "en");
    }

    @Test
    void aCallerAcceptsTheTextsOfTheirEntryOrOfTheirRoles() {
        assertThat(LegalProcesses.kindsOf(context("app", Set.of(QbPermissions.SPONSOR_ME, QbPermissions.PLAY))))
            .containsExactlyInAnyOrderElementsOf(QbLegal.APP_KINDS);
        assertThat(LegalProcesses.kindsOf(context("sponsor", Set.of(QbPermissions.SPONSOR_ME, QbPermissions.PLAY))))
            .containsExactlyInAnyOrderElementsOf(QbLegal.SPONSOR_KINDS);
        assertThat(LegalProcesses.kindsOf(context("admin", Set.of(QbPermissions.PLAY))))
            .containsExactlyInAnyOrderElementsOf(QbLegal.APP_KINDS);
        assertThat(LegalProcesses.kindsOf(context(null, Set.of(QbPermissions.SPONSOR_ME, QbPermissions.PLAY))))
            .containsExactlyInAnyOrder(QbLegal.TERMS_APP, QbLegal.TERMS_SPONSOR, QbLegal.PRIVACY);
        assertThat(LegalProcesses.kindsOf(context("admin", Set.of(QbPermissions.ADMIN_CONTENT_WRITE)))).isEmpty();
    }

    private static RequestContext context(String entry, Set<String> permissions) {
        return new RequestContext("u", null, Locale.ENGLISH, "r", Set.of(), permissions, null, null, entry, true);
    }

    @Test
    void theContentHashCoversTheTitleAndTheTextWhateverTheirOrder() {
        String hash = LegalProcesses.hash(Map.of("en", "T", "ja", "タ"), Map.of("en", "B", "ja", "本"));
        assertThat(LegalProcesses.hash(new java.util.TreeMap<>(Map.of("ja", "タ", "en", "T")),
            Map.of("ja", "本", "en", "B"))).isEqualTo(hash);
        assertThat(LegalProcesses.hash(Map.of("en", "T", "ja", "タ"), Map.of("en", "B!", "ja", "本")))
            .isNotEqualTo(hash);
    }

    // ---- any sequence of publications ------------------------------------------------------------------------------

    /**
     * A publication: seconds after the previous one, whether it asks for consent, how far ahead it takes effect, and
     * whether the version announced, if any, is withdrawn first.
     */
    record Step(int gapSeconds, boolean consent, int aheadSeconds, boolean withdraw) {}

    @Provide
    Arbitrary<List<Step>> publications() {
        return Combinators.combine(Arbitraries.integers().between(0, 1000), Arbitraries.of(true, false),
            Arbitraries.integers().between(0, 3000), Arbitraries.of(true, false)).as(Step::new).list().ofMaxSize(12);
    }

    /**
     * Publishing and withdrawing what the rules allow, in order, keeps numbers in step and the effective times of
     * the versions not withdrawn too; the version in effect at any time is the latest not withdrawn whose time has
     * come, and the version to accept is the latest such asking for consent (or the first).
     */
    @Property
    void anySequenceOfPublicationsKeepsTheRules(@ForAll("publications") List<Step> steps,
        @ForAll int probeSeconds) {
        List<LegalRules.Version> versions = new ArrayList<>();
        Instant now = T0;
        for (Step step : steps) {
            now = now.plusSeconds(step.gapSeconds());
            Instant at = now;
            if (step.withdraw()) {
                LegalRules.upcoming(versions, now).ifPresent(announced -> {
                    assertThat(LegalRules.withdrawProblems(announced, at)).isEmpty();
                    versions.set(versions.indexOf(announced), announced.withdrawn());
                });
            }
            Instant effectiveAt = now.plusSeconds(step.aheadSeconds());
            String hash = "text-" + versions.size();
            List<Violation> problems = LegalRules.publishProblems(versions, hash, effectiveAt, now, FAR);
            boolean announced = LegalRules.upcoming(versions, now).isPresent();
            assertThat(problems.isEmpty()).isEqualTo(!announced);
            if (problems.isEmpty()) {
                versions.add(new LegalRules.Version(LegalRules.nextNumber(versions),
                    LegalRules.requiresConsent(versions, step.consent()), effectiveAt, hash));
            }
        }
        List<LegalRules.Version> standing = versions.stream().filter(v -> !v.isWithdrawn()).toList();
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i).number()).isEqualTo(versions.get(i - 1).number() + 1);
        }
        for (int i = 1; i < standing.size(); i++) {
            assertThat(standing.get(i).effectiveAt()).isAfterOrEqualTo(standing.get(i - 1).effectiveAt());
        }
        for (LegalRules.Version version : versions) {
            if (version.isWithdrawn()) {
                assertThat(LegalRules.withdrawProblems(version, now)).extracting(Violation::ruleCode)
                    .containsExactly(LegalCodes.WITHDRAWN);
            }
        }
        Instant probe = T0.plusSeconds(Math.floorMod(probeSeconds, 20_000));
        int expectedCurrent = 0;
        int expectedToAccept = 0;
        for (LegalRules.Version version : standing) {
            if (!version.effectiveAt().isAfter(probe)) {
                expectedCurrent = version.number();
                if (version.requiresConsent()) {
                    expectedToAccept = version.number();
                }
            }
        }
        assertThat(LegalRules.current(versions, probe).map(LegalRules.Version::number).orElse(0))
            .isEqualTo(expectedCurrent);
        assertThat(LegalRules.toAccept(versions, probe)).isEqualTo(expectedToAccept);
        assertThat(LegalRules.toAccept(versions, probe)).isLessThanOrEqualTo(expectedCurrent);
        if (!standing.isEmpty()) {
            assertThat(standing.getFirst().requiresConsent()).isTrue();
        }
    }
}

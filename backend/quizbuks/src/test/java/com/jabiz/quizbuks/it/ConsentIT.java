package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.messaging.LegalCodes;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consent (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-5, Q7-2, Q7-3): a taker accepts the terms in effect on
 * registration's second step, a sponsor with the application; whoever has not accepted the version to accept is
 * refused money and contract actions (top-up, submitting a publication, opening a payout account; onboarding accepts
 * itself) until they accept, but may still start a quiz. A rewording asks nobody again; a version announced for later
 * asks once it takes effect. The tests run in order: the first runs before any text is published.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsentIT extends LegalItSupport {

    @AfterEach
    void onlyInserted() {
        assertLegalOnlyInserted();
        assertAccountsOnlyInserted();
    }

    /** The process is not refused for consent (whatever else it says). */
    private void assertNotGated(String process, String token, Map<String, ?> input) {
        var result = call(process, token, input).expectBody(MAP).returnResult();
        assertThat(result.getStatus().value() == 200 || !codes(result.getResponseBody())
            .contains(LegalCodes.CONSENT_REQUIRED)).as(process + " answered " + result.getResponseBody()).isTrue();
    }

    private Map<String, Object> welcome(String token, Object accept, Object terms, Object privacy) {
        return body("displayName", "Newcomer", "countryCode", "JP", "preferredCurrency", "JPY", "acceptTerms", accept,
            "termsVersionNo", terms, "privacyVersionNo", privacy);
    }

    @Test
    @Order(1)
    void withoutATextInEffectNothingIsAsked() {
        assertThat(consents(taker(u1()), QbLegal.APP_KINDS)).isEmpty();
        String newcomer = createUser(unique("early"));
        run("QB_PROFILE_SAVE", tokenOf(newcomer, QbRoles.TAKER), welcome(tokenOf(newcomer, QbRoles.TAKER), null,
            null, null));
        assertThat(consentRows(newcomer)).isEmpty();
        // The gates let everybody through (top-ups are not configured in this class: refused for that only).
        assertNotGated("QB_TOPUP_START", sponsorToken(sponsorA()), body("amount", 1000));
    }

    @Test
    @Order(2)
    void aTakerAcceptsTheTermsInEffectOnTheWelcomeStep() {
        publishAllNow("v1");
        String newcomer = createUser(unique("newcomer"));
        String token = tokenOf(newcomer, QbRoles.TAKER);
        assertThat(codes(refused("QB_PROFILE_SAVE", token, welcome(token, null, 1, 1), 422)))
            .containsExactly(LegalCodes.TERMS_REQUIRED);
        assertThat(codes(refused("QB_PROFILE_SAVE", token, welcome(token, false, 1, 1), 422)))
            .containsExactly(LegalCodes.TERMS_REQUIRED);
        // What the page showed is no longer in effect (or it showed nothing).
        assertThat(violations(refused("QB_PROFILE_SAVE", token, welcome(token, true, 2, null), 422)))
            .extracting(v -> v.get("ruleCode"), v -> v.get("field"))
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(LegalCodes.TERMS_OUTDATED, "termsVersionNo"),
                org.assertj.core.groups.Tuple.tuple(LegalCodes.TERMS_OUTDATED, "privacyVersionNo"));
        assertThat(profileOf(newcomer)).isNull();

        assertThat(run("QB_PROFILE_SAVE", token, welcome(token, true, 1, 1))).containsEntry("created", true);
        assertThat(consentRows(newcomer)).extracting(row -> row.get("kind"), row -> row.get("source"))
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(QbLegal.TERMS_APP, QbLegal.SOURCE_WELCOME),
                org.assertj.core.groups.Tuple.tuple(QbLegal.PRIVACY, QbLegal.SOURCE_WELCOME));
        assertThat(consents(token, QbLegal.APP_KINDS).values()).hasSize(2)
            .allSatisfy(row -> assertThat(row).containsEntry("mustAccept", false).containsEntry("versionNo", 1)
                .containsEntry("accepted", 1));
        // Later saves ask nothing.
        run("QB_PROFILE_SAVE", token, body("displayName", "Newcomer 2", "countryCode", "JP", "preferredCurrency",
            "JPY"));
        assertThat(consentRows(newcomer)).hasSize(2);
    }

    @Test
    @Order(3)
    void whoHasNotAcceptedIsRefusedMoneyAndContractsButMayStartAQuiz() {
        // The sponsor and the takers of the installation registered before the terms.
        String sponsor = sponsorA();
        String sponsorToken = sponsorToken(sponsor);
        assertThat(consents(sponsorToken, QbLegal.SPONSOR_KINDS).values())
            .allSatisfy(row -> assertThat(row).containsEntry("mustAccept", true).containsEntry("accepted", null));
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken, body("amount", 1000), 422)))
            .containsExactly(LegalCodes.CONSENT_REQUIRED, LegalCodes.CONSENT_REQUIRED);
        String quiz = version(sponsorToken, "Consent quiz");
        String draft = draft(sponsorToken, quiz, "Consent publication");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", sponsorToken, body("publicationId", draft), 422)))
            .contains(LegalCodes.CONSENT_REQUIRED);

        // Accepting through the prompt: the versions in effect, recorded once.
        assertThat(acceptCall(sponsorToken, QbLegal.TERMS_SPONSOR, 1, QbLegal.PRIVACY, 1).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().toString()).contains("TERMS_SPONSOR", "PRIVACY");
        assertThat(consentRows(sponsor)).extracting(row -> row.get("source"))
            .containsOnly(QbLegal.SOURCE_RECONSENT).hasSize(2);
        assertNotGated("QB_TOPUP_START", sponsorToken, body("amount", 1000));
        assertThat(submit(sponsorToken, draft).get("status")).isIn("LIVE", "SCHEDULED", "PENDING_REVIEW");

        // A taker who has not accepted starts a quiz (Q7-2: only the pages ask), but opens no payout account.
        credit(sponsor, 1_000);
        String live = livePublication(sponsor, "Open to all", Map.of("regions", List.of("GLOBAL")));
        String takerToken = taker(u2());
        assertThat(consents(takerToken, QbLegal.APP_KINDS).values())
            .allSatisfy(row -> assertThat(row).containsEntry("mustAccept", true));
        assertThat(start(takerToken, live)).containsKey("attemptId");
        assertThat(codes(refused("QB_PAYOUT_ONBOARD", taker(u1()), body(), 422)))
            .containsExactly(LegalCodes.CONSENT_REQUIRED, LegalCodes.CONSENT_REQUIRED);
        acceptCall(taker(u1()), QbLegal.TERMS_APP, 1, QbLegal.PRIVACY, 1).expectStatus().isOk();
        var onboard = call("QB_PAYOUT_ONBOARD", taker(u1()), body()).expectBody(MAP).returnResult();
        assertThat(String.valueOf(onboard.getResponseBody())).doesNotContain(LegalCodes.CONSENT_REQUIRED);
        assertThat(onboard.getStatus().value()).isEqualTo(200);
    }

    @Test
    @Order(4)
    void aSponsorAcceptsWithTheApplicationAndTheReviewIsBoundToTheVersion() {
        String applicant = createUser(unique("applicant"));
        assignRole(applicant, QbRoles.SPONSOR);
        String token = tokenOf(applicant, QbRoles.SPONSOR);
        Map<String, Object> application = body("displayName", "Ann", "company", "Ann Ltd", "acceptTerms", true);
        assertThat(codes(refused("QB_SPONSOR_APPLY", token, application, 422)))
            .containsExactly(LegalCodes.TERMS_OUTDATED, LegalCodes.TERMS_OUTDATED);
        application.put("termsVersionNo", 1);
        application.put("privacyVersionNo", 1);
        run("QB_SPONSOR_APPLY", token, application);
        assertThat(consentRows(applicant)).extracting(row -> row.get("kind"), row -> row.get("source"))
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(QbLegal.TERMS_SPONSOR, QbLegal.SOURCE_ONBOARDING),
                org.assertj.core.groups.Tuple.tuple(QbLegal.PRIVACY, QbLegal.SOURCE_ONBOARDING));
        assertThat(((Number) onboardingOf(applicant).get("terms_version_no")).intValue()).isEqualTo(1);
        assertThat(consents(token, QbLegal.SPONSOR_KINDS).values())
            .allSatisfy(row -> assertThat(row).containsEntry("mustAccept", false));
    }

    @Test
    @Order(5)
    void aRewordingAsksNobodyAgainAndAChangeAsksOnceInEffect() {
        String taker = u1();
        String token = taker(taker);
        assertThat(publishNew(QbLegal.TERMS_APP, "v2 wording", null, false)).containsEntry("versionNo", 2);
        assertThat(consents(token, QbLegal.APP_KINDS).get(QbLegal.TERMS_APP)).containsEntry("versionNo", 2)
            .containsEntry("toAccept", 1).containsEntry("accepted", 1).containsEntry("mustAccept", false);

        Instant later = clock.instant().plus(Duration.ofDays(7));
        assertThat(publishNew(QbLegal.TERMS_APP, "v3 substance", later, true)).containsEntry("versionNo", 3);
        // Announced: nothing is asked yet, and an announced version cannot be accepted ahead.
        assertThat(consents(token, QbLegal.APP_KINDS).get(QbLegal.TERMS_APP)).containsEntry("mustAccept", false);
        assertThat(codes(acceptCall(token, QbLegal.TERMS_APP, 3).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).containsExactly(LegalCodes.TERMS_OUTDATED);

        clock.set(later.plusSeconds(60));
        token = taker(taker);
        assertThat(consents(token, QbLegal.APP_KINDS).get(QbLegal.TERMS_APP)).containsEntry("versionNo", 3)
            .containsEntry("toAccept", 3).containsEntry("mustAccept", true);
        assertThat(codes(refused("QB_PAYOUT_ONBOARD", token, body(), 422)))
            .containsExactly(LegalCodes.CONSENT_REQUIRED);
        assertThat(codes(acceptCall(token, QbLegal.TERMS_APP, 2).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).containsExactly(LegalCodes.TERMS_OUTDATED);
        acceptCall(token, QbLegal.TERMS_APP, 3).expectStatus().isOk();
        assertThat(consents(token, QbLegal.APP_KINDS).get(QbLegal.TERMS_APP)).containsEntry("mustAccept", false);
        assertThat(consentRows(taker)).extracting(row -> ((Number) row.get("legal_version_no")).intValue())
            .contains(1, 3).doesNotContain(2);
        // Accepting the same again records nothing more.
        int before = consentRows(taker).size();
        acceptCall(token, QbLegal.TERMS_APP, 3).expectStatus().isOk();
        assertThat(consentRows(taker)).hasSize(before);
    }

    @Test
    @Order(6)
    void aCallerAcceptsOnlyTheTextsTheirFrontEndAsksFor() {
        String token = tokenOf(u2(), QbRoles.TAKER);
        // The administration's tokens of the tests: the kinds of the roles held (a taker's only).
        assertThat(codes(acceptCall(token, QbLegal.TERMS_SPONSOR, 1).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).containsExactly(LegalCodes.CONSENT_KIND);
        assertThat(codes(acceptCall(token, QbLegal.COMMERCE_NOTICE, 1).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody())).containsExactly(LegalCodes.CONSENT_KIND);
        assertThat(codes(acceptCall(token, QbLegal.PRIVACY, 1, QbLegal.PRIVACY, 1).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody())).containsExactly(LegalCodes.CONSENT_KIND);
        // An unverified address writes nothing (D-Q2-1).
        call("QB_CONSENT_ACCEPT", unverifiedTokenOf(u2(), QbRoles.TAKER), body("items",
            List.of(body("kind", QbLegal.PRIVACY, "versionNo", 1)))).expectStatus().isForbidden();

        // Signed in at an entry, the entry's texts only whatever the roles: LegalProcessesTest.
    }
}

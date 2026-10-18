package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.LegalCodes;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Withdrawing an announced version and the announcement horizon (Q7b review M1): a version announced for later is
 * withdrawn ({@code QB_LEGAL_WITHDRAW}, a content administrator with a second factor and a reason) until it takes
 * effect; withdrawn, it never takes effect, asks nobody, and the next version gets the next number. An effective time
 * beyond {@code qb.legal.max-announce-days} is refused, so that no announcement blocks the kind for ever.
 */
class LegalWithdrawIT extends LegalItSupport {

    @AfterEach
    void onlyInserted() {
        assertLegalOnlyInserted();
    }

    /** The server's standing of a user ({@code qb.system.consent-state}) with a kind, or null without one. */
    private Map<String, Object> state(String userId, String kind) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        params.put("kinds", List.of(kind));
        params.put("at", clock.instant().toString());
        return items(QbLegal.CONSENT_STATE_TEMPLATE, TestTokens.bearer(tokens, "system-reader",
            QbPermissions.MESSAGING_SYSTEM), body("params", params)).stream().findFirst().orElse(null);
    }

    @Test
    void anAnnouncedVersionIsWithdrawnNeverTakesEffectAndItsNumberIsNotReused() {
        String kind = QbLegal.TERMS_APP;
        String taker = u1();
        publishNew(kind, "v1", null, true);
        acceptCall(taker(taker), kind, 1).expectStatus().isOk();
        Instant later = clock.instant().plus(Duration.ofDays(7));
        publishNew(kind, "v2 announced", later, true);

        // Only content administrators with a second factor withdraw, with a reason.
        Map<String, Object> input = withdrawInput(kind, 2, "Announced by mistake");
        refused("QB_LEGAL_WITHDRAW", taker(taker), input, 403);
        refused("QB_LEGAL_WITHDRAW", TestTokens.withoutMfa(tokens, "legal-admin", QbPermissions.ADMIN_CONTENT_WRITE),
            input, 403);
        refused("QB_LEGAL_WITHDRAW", legalAdmin(), withdrawInput(kind, 2, " "), 400);
        // The version in effect is not withdrawn: a new version replaces it.
        assertThat(codes(refused("QB_LEGAL_WITHDRAW", legalAdmin(), withdrawInput(kind, 1, "No"), 422)))
            .containsExactly(LegalCodes.WITHDRAW_IN_EFFECT);

        assertThat(withdraw(kind, 2, "Announced by mistake")).containsEntry("kind", kind).containsEntry("versionNo", 2)
            .containsEntry("withdrawnAt", clock.instant().toString());
        assertThat(query("SELECT legal_version_no, reason, withdrawn_by FROM qb_legal_withdrawal_version"))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("reason", "Announced by mistake")
                .containsEntry("withdrawn_by", "legal-admin"));
        assertThat(codes(refused("QB_LEGAL_WITHDRAW", legalAdmin(), input, 422)))
            .containsExactly(LegalCodes.WITHDRAWN);
        // The administration's list of versions says who withdrew it and why; only content administrators read it.
        Map<String, Object> byKind = body("filters", List.of(body("field", "kind", "op", "eq", "value", kind)));
        assertThat(items("qb.admin.legal-versions", legalAdmin(), byKind))
            .extracting(row -> row.get("versionNo"), row -> row.get("withdrawn"), row -> row.get("reason"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple(2, true, "Announced by mistake"),
                org.assertj.core.groups.Tuple.tuple(1, false, null));
        client.post().uri("/api/queries/qb.admin.legal-versions")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, taker(taker)).bodyValue(Map.of())
            .exchange().expectStatus().isForbidden();

        // Nothing is announced any more; the public pages say so, and the history shows the withdrawn version.
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_TEMPLATE, "p.kind=" + kind + "&p.at=" + clock.instant()))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("versionNo", 1)
                .containsEntry("upcomingVersionNo", null));
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_HISTORY_TEMPLATE, "p.kind=" + kind))
            .extracting(row -> row.get("versionNo"), row -> row.get("withdrawn"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple(2, true), org.assertj.core.groups.Tuple.tuple(1,
                false));
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_VERSION_TEMPLATE, "p.kind=" + kind + "&p.versionNo=2"))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("withdrawn", true));

        // Its time comes and goes: version 1 stays in effect, nobody is asked again.
        clock.set(later.plusSeconds(60));
        String token = taker(taker);
        assertThat(state(taker, kind)).containsEntry("currentVersionNo", 1).containsEntry("toAccept", 1)
            .containsEntry("accepted", 1);
        assertThat(consents(token, List.of(kind)).get(kind)).containsEntry("versionNo", 1)
            .containsEntry("mustAccept", false);
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_TEMPLATE, "p.kind=" + kind + "&p.at=" + clock.instant()))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("versionNo", 1));
        assertThat(codes(acceptCall(token, kind, 2).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody())).containsExactly(LegalCodes.TERMS_OUTDATED);

        // The same text may be published again: it gets the next number, never the withdrawn one.
        assertThat(publish(kind, null, true)).containsEntry("versionNo", 3);
        assertThat(state(taker, kind)).containsEntry("currentVersionNo", 3).containsEntry("toAccept", 3);
        // Once in effect, a version is no longer withdrawn.
        assertThat(codes(refused("QB_LEGAL_WITHDRAW", legalAdmin(), withdrawInput(kind, 3, "Late"), 422)))
            .containsExactly(LegalCodes.WITHDRAW_IN_EFFECT);
    }

    @Test
    void anAnnouncementReachesNoFurtherThanTheHorizon() {
        String kind = QbLegal.PRIVACY;
        publishNew(kind, "v1", null, true);
        editDraft(kind, "v2", "v2", "v2", "v2", "v2", "v2");
        // qb.legal.max-announce-days: 180 unless set otherwise.
        for (String far : List.of(clock.instant().plus(Duration.ofDays(180)).plusSeconds(1).toString(),
            "9999-01-01T00:00:00Z", "+300000-01-01T00:00:00Z")) {
            Map<String, Object> input = publishInput(kind, null, true);
            input.put("effectiveAt", far);
            assertThat(codes(refused("QB_LEGAL_PUBLISH", legalAdmin(), input, 422))).as(far)
                .containsExactly(LegalCodes.EFFECTIVE_TOO_LATE);
        }
        assertThat(query("SELECT 1 FROM qb_legal_version_version WHERE kind = ?", kind)).hasSize(1);
        // A page asking at such a time is answered (the version in effect then). Beyond PostgreSQL's range a
        // template's time parameter is still a server error: the platform's binding, recorded in the plan.
        assertThat(client.get().uri(b -> b.path("/api/public/queries/" + QbLegal.PUBLIC_LEGAL_TEMPLATE)
                .queryParam("p.kind", "{kind}").queryParam("p.at", "{at}").build(kind, "9999-01-01T00:00:00Z"))
            .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody())
            .contains("\"versionNo\":1");
        Instant edge = clock.instant().plus(Duration.ofDays(180));
        assertThat(publish(kind, edge, true)).containsEntry("versionNo", 2)
            .containsEntry("effectiveAt", edge.toString());
    }
}

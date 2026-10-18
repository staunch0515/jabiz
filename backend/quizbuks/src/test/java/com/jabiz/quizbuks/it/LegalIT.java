package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.LegalCodes;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publishing legal texts (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-4, Q7-1, Q7-4 – Q7-6): the drafts are edited
 * by the content administrators only; {@code QB_LEGAL_PUBLISH} needs their permission, a second factor and a verified
 * address, Japanese and English, a new text, and an effective time not in the past while nothing is announced; the
 * version in effect changes when its time comes, without a job. Versions are written once.
 */
class LegalIT extends LegalItSupport {

    @AfterEach
    void onlyInserted() {
        assertLegalOnlyInserted();
    }

    @Test
    void onlyContentAdministratorsWithASecondFactorPublish() {
        Map<String, Object> input = publishInput(QbLegal.COMMERCE_NOTICE, null, true);
        refused("QB_LEGAL_PUBLISH", taker(u1()), input, 403);
        refused("QB_LEGAL_PUBLISH", sponsorToken(sponsorA()), input, 403);
        refused("QB_LEGAL_PUBLISH", TestTokens.withoutMfa(tokens, "legal-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), input, 403);
        refused("QB_LEGAL_PUBLISH", TestTokens.unverified(tokens, "legal-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), input, 403);
        assertThat(query("SELECT 1 FROM qb_legal_version_version WHERE kind = ?", QbLegal.COMMERCE_NOTICE))
            .isEmpty();

        // The drafts are the content administrators' only: others neither read nor write them.
        editDraftAs(sponsorToken(sponsorA()), QbLegal.COMMERCE_NOTICE, Map.of("title", text("x", "x")))
            .expectStatus().isForbidden();
        queryDataset(QbLegal.defaultDataset(QbLegal.DOC), taker(u1())).expectStatus().isForbidden();
        // Versions are written by the process only, even by an administrator holding everything.
        assertThat(client.post().uri("/api/datasets/" + QbLegal.defaultDataset(QbLegal.VERSION) + "/commit")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, admin())
            .bodyValue(Map.of("changes", List.of(body("action", "INSERT", "attributes", body("kind",
                QbLegal.COMMERCE_NOTICE, "versionNumber", 9, "title", text("x", "x"), "body", text("x", "x"),
                "requiresConsent", true, "publishedAt", clock.instant().toString(), "publishedBy", "x",
                "effectiveAt", clock.instant().toString(), "contentHash", "x")))))
            .exchange().expectBody(MAP).returnResult().getStatus().is2xxSuccessful()).isFalse();
    }

    @Test
    void aVersionNeedsJapaneseAndEnglishAndTheFirstAlwaysAsksForConsent() {
        String kind = QbLegal.TERMS_SPONSOR;
        editDraft(kind, "Sponsor terms", null, "Text", null, null, null);
        assertThat(violations(refused("QB_LEGAL_PUBLISH", legalAdmin(), publishInput(kind, null, false), 422)))
            .extracting(v -> v.get("ruleCode"), v -> v.get("field"), v -> v.get("message"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple(LegalCodes.TRANSLATION_MISSING, "title",
                    "The title needs a ja text before it is published."),
                org.assertj.core.groups.Tuple.tuple(LegalCodes.TRANSLATION_MISSING, "body",
                    "The body needs a ja text before it is published."));

        editDraft(kind, "Sponsor terms", "スポンサー利用規約", "Text", "本文", null, null);
        Map<String, Object> first = publish(kind, null, false);
        // The first version always asks for consent, whatever the publisher said.
        assertThat(first).containsEntry("kind", kind).containsEntry("versionNo", 1)
            .containsEntry("requiresConsent", true).containsEntry("effectiveAt", clock.instant().toString());
        assertThat(latest("qb_legal_doc_version", "doc_id", "kind = '" + kind + "'")).singleElement()
            .satisfies(draft -> assertThat(((Number) draft.get("latest_version_no")).intValue()).isEqualTo(1));
        assertThat(query("SELECT published_by, title->>'ja' AS ja, change_summary FROM qb_legal_version_version "
            + "WHERE kind = ?", kind)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("published_by", "legal-admin").containsEntry("ja", "スポンサー利用規約")
                .containsEntry("change_summary", null));

        // The same text again is no new version; a later one needs its summary of changes in both languages.
        assertThat(codes(refused("QB_LEGAL_PUBLISH", legalAdmin(), publishInput(kind, null, true), 422)))
            .containsExactly(LegalCodes.TRANSLATION_MISSING, LegalCodes.TRANSLATION_MISSING, LegalCodes.UNCHANGED);
        editDraft(kind, "Sponsor terms", "スポンサー利用規約", "Text 2", "本文 2", "What changed", null);
        assertThat(codes(refused("QB_LEGAL_PUBLISH", legalAdmin(), publishInput(kind, null, true), 422)))
            .containsExactly(LegalCodes.TRANSLATION_MISSING);
        editDraft(kind, "Sponsor terms", "スポンサー利用規約", "Text 2", "本文 2", "What changed", "変更点");
        assertThat(publish(kind, null, false)).containsEntry("versionNo", 2).containsEntry("requiresConsent", false);
    }

    @Test
    void aVersionAnnouncedForLaterTakesEffectWhenItsTimeComes() {
        String kind = QbLegal.PRIVACY;
        publishNew(kind, "v1", null, true);
        Instant now = clock.instant();
        Instant later = now.plus(Duration.ofDays(7));
        assertThat(publishNew(kind, "v2", later, true)).containsEntry("versionNo", 2)
            .containsEntry("effectiveAt", later.toString());
        // Announced: version 1 stays in effect, version 2 is the announced one.
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_TEMPLATE, "p.kind=PRIVACY&p.at=" + now)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("versionNo", 1).containsEntry("upcomingVersionNo", 2)
                .containsEntry("upcomingEffectiveAt", later.toString()));
        // An announcement is not replaced, and nothing takes effect in the past.
        editDraft(kind, "v3", "v3", "v3", "v3", "v3", "v3");
        assertThat(codes(refused("QB_LEGAL_PUBLISH", legalAdmin(), publishInput(kind, null, true), 422)))
            .containsExactly(LegalCodes.UPCOMING_EXISTS);
        assertThat(codes(refused("QB_LEGAL_PUBLISH", legalAdmin(), publishInput(kind, now.minusSeconds(60), true),
            422))).containsExactly(LegalCodes.EFFECTIVE_PAST, LegalCodes.UPCOMING_EXISTS);

        // Its time comes: in effect without any job, and the next can be published.
        clock.set(later.plusSeconds(60));
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_TEMPLATE, "p.kind=PRIVACY&p.at=" + clock.instant()))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("versionNo", 2)
                .containsEntry("upcomingVersionNo", null));
        assertThat(publish(kind, null, false)).containsEntry("versionNo", 3);
    }

    @Test
    void aDraftEditedMeanwhileIsNotPublished() {
        String kind = QbLegal.TERMS_APP;
        editDraft(kind, "Terms", "規約", "Text", "本文", null, null);
        long read = ((Number) draftOf(kind).get("version_no")).longValue();
        editDraft(kind, "Terms", "規約", "Text, edited", "本文（編集）", null, null);
        Map<String, Object> input = publishInput(kind, null, true);
        input.put("baseRevision", read);
        assertThat(codes(refused("QB_LEGAL_PUBLISH", legalAdmin(), input, 422))).containsExactly(LegalCodes.CHANGED);
        input.put("baseRevision", ((Number) draftOf(kind).get("version_no")).longValue());
        assertThat(run("QB_LEGAL_PUBLISH", legalAdmin(), input)).containsEntry("versionNo", 1);
        // The version is the edited text, written once: the table refuses changes.
        assertThat(query("SELECT body->>'en' AS en FROM qb_legal_version_version WHERE kind = ?", kind))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("en", "Text, edited"));
        assertThatThrownBy(() -> execute("UPDATE qb_legal_version_version SET requires_consent = false"))
            .hasMessageContaining("append-only");
        // The content administrators' role holds the permission (QB_SETUP grants it).
        assertThat(QbRoles.of(QbRoles.ADMIN_CONTENT).permissions()).contains(QbPermissions.ADMIN_CONTENT_WRITE);
    }
}

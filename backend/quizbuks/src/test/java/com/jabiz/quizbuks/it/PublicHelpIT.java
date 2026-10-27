package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anonymous reading of the help centre and the legal texts (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-6;
 * platform docs/design/15-public-access.md): only through the public templates, only published versions and published
 * questions, never a draft or an unpublished question (not by a parameter, a filter or a count), never who published a
 * version or its hash; a version announced for later is never the one in effect. The rest of the API still wants a
 * token, and nothing is written.
 */
class PublicHelpIT extends LegalItSupport {

    static final String SECRET = "SECRET-DRAFT-7b";

    @AfterEach
    void onlyInserted() {
        assertLegalOnlyInserted();
    }

    /** A question written by the content administrator in the administration. */
    void faq(String audience, String category, String question, String answer, int seq, boolean published) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("audience", audience);
        attributes.put("category", category);
        attributes.put("question", Map.of("en", question, "ja", question + "（日本語）"));
        attributes.put("answer", Map.of("en", answer));
        attributes.put("seq", seq);
        attributes.put("published", published);
        client.post().uri("/api/datasets/" + QbLegal.defaultDataset(QbLegal.FAQ) + "/commit")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, legalAdmin())
            .bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .exchange().expectStatus().isOk();
    }

    @Test
    void theHelpCentreShowsThePublishedQuestionsOfTheFrontEnd() {
        faq("APP", "ACCOUNT", "How do I sign up?", "Use **Sign up**.", 20, true);
        faq("ALL", "OTHER", "Who runs QuizBuks?", "The operator.", 10, true);
        faq("SPONSOR", "BILLING", "How do I top up?", "From the wallet.", 5, true);
        faq("APP", "REWARDS", SECRET + " question", "Not yet " + SECRET, 1, false);

        List<Map<String, Object>> app = publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP");
        assertThat(app).extracting(row -> (Object) ((Map<?, ?>) row.get("question")).get("en"))
            .containsExactly("Who runs QuizBuks?", "How do I sign up?");
        assertThat(app.getFirst()).containsOnlyKeys("faqId", "category", "question", "answer", "seq");
        assertThat(publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=SPONSOR"))
            .extracting(row -> (Object) ((Map<?, ?>) row.get("question")).get("en"))
            .containsExactly("How do I top up?", "Who runs QuizBuks?");
        // Searched in any language, literally.
        assertThat(publicSearch("APP", "（日"))
            .hasSize(2);
        assertThat(publicSearch("APP", "sign UP")).hasSize(1);
        assertThat(publicSearch("APP", "%")).isEmpty();

        // The unpublished question never appears: not by search, filter or count.
        assertThat(publicSearch("APP", SECRET)).isEmpty();
        assertThat(publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP&p.category=REWARDS")).isEmpty();
        assertThat(publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP&filter=category:eq:REWARDS")).isEmpty();
        Map<String, Object> counted = publicQuery(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP&count=true")
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(counted).containsEntry("total", 2);
        publicQuery(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP&filter=published:eq:false").expectStatus()
            .isBadRequest();
        assertThat(publicQuery(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP").expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody()).doesNotContain(SECRET);
        // Publishing it in the administration shows it.
        String hidden = String.valueOf(query("SELECT faq_id, version_no FROM qb_faq_entry_version WHERE "
            + "question->>'en' = ?", SECRET + " question").getFirst().get("faq_id"));
        long version = ((Number) query("SELECT max(version_no) AS v FROM qb_faq_entry_version WHERE faq_id = ?::uuid",
            hidden).getFirst().get("v")).longValue();
        commit(QbLegal.defaultDataset(QbLegal.FAQ), "UPDATE", hidden, version, Map.of("published", true), null);
        assertThat(publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP&p.category=REWARDS")).hasSize(1);
        // Deleting it in the administration takes it off the help centre again (a tombstone, not a removed row).
        commit(QbLegal.defaultDataset(QbLegal.FAQ), "DELETE", hidden, version + 1, null, null);
        assertThat(publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP&p.category=REWARDS")).isEmpty();
        assertThat(publicSearch("APP", SECRET)).isEmpty();
        // Only the content administrators write questions.
        client.post().uri("/api/datasets/" + QbLegal.defaultDataset(QbLegal.FAQ) + "/commit")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, tokenOf(u1(), QbRoles.TAKER))
            .bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("audience", "APP")))))
            .exchange().expectStatus().isForbidden();
    }

    @Test
    void theLegalPagesShowPublishedVersionsOnlyNeverADraft() {
        String kind = QbLegal.COMMERCE_NOTICE;
        // A draft only: nothing to read.
        editDraft(kind, SECRET, SECRET, SECRET, SECRET, null, null);
        Instant now = clock.instant();
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_TEMPLATE, "p.kind=" + kind + "&p.at=" + now)).isEmpty();
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_HISTORY_TEMPLATE, "p.kind=" + kind)).isEmpty();
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_VERSION_TEMPLATE, "p.kind=" + kind + "&p.versionNo=1")).isEmpty();

        publishNew(kind, "v1", null, true);
        // The draft edited after publishing stays a draft.
        editDraft(kind, SECRET, SECRET, SECRET, SECRET, SECRET, SECRET);
        Instant later = now.plus(Duration.ofDays(7));
        editDraft(kind, "Notice v2", "表記 v2", "Text v2", "本文 v2", "Changed", "変更");
        publish(kind, later, false);
        editDraft(kind, SECRET, SECRET, SECRET, SECRET, SECRET, SECRET);

        List<Map<String, Object>> current = publicItems(QbLegal.PUBLIC_LEGAL_TEMPLATE, "p.kind=" + kind + "&p.at="
            + now);
        assertThat(current).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("versionNo", 1).containsEntry("upcomingVersionNo", 2)
                .containsEntry("upcomingEffectiveAt", later.toString()).containsEntry("requiresConsent", true)
                .doesNotContainKeys("publishedBy", "contentHash", "publishedAt");
            assertThat(((Map<?, ?>) row.get("title")).get("en")).isEqualTo(kind + " v1");
        });
        // The announced version is readable in full (announcing is publishing), but not in effect before its time.
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_VERSION_TEMPLATE, "p.kind=" + kind + "&p.versionNo=2"))
            .singleElement().satisfies(row -> assertThat(((Map<?, ?>) row.get("body")).get("en")).isEqualTo("Text v2"));
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_HISTORY_TEMPLATE, "p.kind=" + kind))
            .extracting(row -> row.get("versionNo")).containsExactly(2, 1);
        assertThat(publicItems(QbLegal.PUBLIC_LEGAL_TEMPLATE, "p.kind=" + kind + "&p.at=" + later))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("versionNo", 2)
                .containsEntry("upcomingVersionNo", null));
        for (String id : List.of(QbLegal.PUBLIC_LEGAL_TEMPLATE + "?p.kind=" + kind + "&p.at=" + now,
            QbLegal.PUBLIC_LEGAL_HISTORY_TEMPLATE + "?p.kind=" + kind,
            QbLegal.PUBLIC_LEGAL_VERSION_TEMPLATE + "?p.kind=" + kind + "&p.versionNo=1",
            QbLegal.PUBLIC_LEGAL_VERSION_TEMPLATE + "?p.kind=" + kind + "&p.versionNo=2")) {
            String text = client.get().uri("/api/public/queries/" + id).exchange().expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .expectBody(String.class).returnResult().getResponseBody();
            assertThat(text).as(id).doesNotContain(SECRET).doesNotContain("legal-admin");
        }
    }

    @Test
    void onlyThePublicTemplatesAreAnonymousAndNothingIsWritten() {
        long processes = ((Number) query("SELECT count(*) AS n FROM op_process").getFirst().get("n")).longValue();
        publicQuery(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP").expectStatus().isOk();
        assertThat(((Number) query("SELECT count(*) AS n FROM op_process").getFirst().get("n")).longValue())
            .isEqualTo(processes);
        // The authenticated templates and the datasets want a token; private templates are not found publicly.
        for (String id : List.of(QbLegal.MY_CONSENTS_TEMPLATE, QbLegal.ADMIN_CONSENTS_TEMPLATE,
            QbLegal.CONSENT_STATE_TEMPLATE, QbLegal.MY_SUPPORT_TEMPLATE)) {
            publicQuery(id, "").expectStatus().isNotFound();
            client.post().uri("/api/queries/" + id).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isUnauthorized();
        }
        client.post().uri("/api/datasets/" + QbLegal.defaultDataset(QbLegal.DOC) + "/query")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/public/queries/" + QbLegal.PUBLIC_FAQ_TEMPLATE).exchange().expectStatus()
            .isEqualTo(405);
        // The parameters are checked: an unknown kind is refused, a missing audience too.
        publicQuery(QbLegal.PUBLIC_LEGAL_TEMPLATE, "p.kind=SECRET&p.at=" + clock.instant()).expectStatus()
            .isBadRequest();
        publicQuery(QbLegal.PUBLIC_FAQ_TEMPLATE, "").expectStatus().isBadRequest();
        // The support address is for signed-in users only, from the parameter (QB_SETUP's default since Q41).
        assertThat(items(QbLegal.MY_SUPPORT_TEMPLATE, taker(u1()), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("email", "quizbuks@gmail.com"));
    }
}

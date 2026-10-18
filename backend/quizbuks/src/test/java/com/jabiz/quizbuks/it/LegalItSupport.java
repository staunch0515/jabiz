package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.runtime.test.TestTokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Terms, privacy and help (docs/quizbuks/plans/Q7-messages-legal.md, Q7b): the drafts edited as a content
 * administrator through the administration's dataset, published by {@code QB_LEGAL_PUBLISH}, accepted by the users,
 * and the public templates read without a token. Installed as the answering tests are (players, sponsors).
 */
abstract class LegalItSupport extends PlayItSupport {

    /** The tables of Q7b, only ever inserted into. */
    static final List<String> LEGAL_TABLES = List.of("qb_legal_doc_version", "qb_legal_version_version",
        "qb_legal_withdrawal_version", "qb_consent_version", "qb_faq_entry_version");

    /** A content administrator as {@code QB_ADMIN_CONTENT} has the parts the tests use (with a second factor). */
    String legalAdmin() {
        return TestTokens.bearer(tokens, "legal-admin", QbPermissions.ADMIN_CONTENT_WRITE,
            QbPermissions.ADMIN_USERS_READ);
    }

    static Map<String, Object> text(String en, String ja) {
        Map<String, Object> text = new LinkedHashMap<>();
        if (en != null) {
            text.put("en", en);
        }
        if (ja != null) {
            text.put("ja", ja);
        }
        return text;
    }

    /** The current row of a kind's draft. */
    static Map<String, Object> draftOf(String kind) {
        return latest("qb_legal_doc_version", "doc_id", "kind = '" + kind + "' AND NOT is_deleted").getFirst();
    }

    static String docId(String kind) {
        return String.valueOf(draftOf(kind).get("doc_id"));
    }

    /** Edits a draft as the content administrator does in the administration (its dataset's generic API). */
    WebTestClient.ResponseSpec editDraftAs(String token, String kind, Map<String, Object> attributes) {
        Map<String, Object> draft = draftOf(kind);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", "UPDATE");
        change.put("id", String.valueOf(draft.get("doc_id")));
        change.put("version", ((Number) draft.get("version_no")).longValue());
        change.put("attributes", attributes);
        return client.post().uri("/api/datasets/" + QbLegal.defaultDataset(QbLegal.DOC) + "/commit")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(Map.of("changes", List.of(change))).exchange();
    }

    void editDraft(String kind, String titleEn, String titleJa, String bodyEn, String bodyJa, String summaryEn,
        String summaryJa) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", text(titleEn, titleJa));
        attributes.put("body", text(bodyEn, bodyJa));
        attributes.put("changeSummary", summaryEn == null && summaryJa == null ? null : text(summaryEn, summaryJa));
        editDraftAs(legalAdmin(), kind, attributes).expectStatus().isOk();
    }

    Map<String, Object> publishInput(String kind, Instant effectiveAt, boolean requiresConsent) {
        Map<String, Object> draft = draftOf(kind);
        Map<String, Object> input = body("docId", String.valueOf(draft.get("doc_id")), "requiresConsent",
            requiresConsent, "baseRevision", ((Number) draft.get("version_no")).longValue());
        if (effectiveAt != null) {
            input.put("effectiveAt", effectiveAt.toString());
        }
        return input;
    }

    Map<String, Object> publish(String kind, Instant effectiveAt, boolean requiresConsent) {
        return run("QB_LEGAL_PUBLISH", legalAdmin(), publishInput(kind, effectiveAt, requiresConsent));
    }

    /**
     * Publishes a new text of the kind (its title and text carry {@code mark}, so each call is new), effective at once
     * or later.
     */
    Map<String, Object> publishNew(String kind, String mark, Instant effectiveAt, boolean requiresConsent) {
        editDraft(kind, kind + " " + mark, kind + " 「" + mark + "」", "# " + kind + "\n\n" + mark,
            "# " + kind + "\n\n" + mark + "（日本語）", "Changes: " + mark, "変更点：" + mark);
        return publish(kind, effectiveAt, requiresConsent);
    }

    /** Publishes a first version of each of the three texts users accept, in effect now. */
    void publishAllNow(String mark) {
        for (String kind : QbLegal.REQUIRED_FOR_LIVE) {
            publishNew(kind, mark, null, true);
        }
    }

    /** The key of a kind's published version. */
    static String versionId(String kind, int versionNo) {
        return String.valueOf(query("SELECT legal_version_id FROM qb_legal_version_version WHERE kind = ? "
            + "AND legal_version_no = ?", kind, versionNo).getFirst().get("legal_version_id"));
    }

    Map<String, Object> withdrawInput(String kind, int versionNo, String reason) {
        return body("legalVersionId", versionId(kind, versionNo), "reason", reason);
    }

    /** Withdraws an announced version, as the content administrator does in the administration. */
    Map<String, Object> withdraw(String kind, int versionNo, String reason) {
        return run("QB_LEGAL_WITHDRAW", legalAdmin(), withdrawInput(kind, versionNo, reason));
    }

    /** The caller's standing ({@code qb.me.consents}) with the kinds, by kind. */
    Map<String, Map<String, Object>> consents(String token, List<String> kinds) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("kinds", kinds);
        params.put("at", clock.instant().toString());
        Map<String, Map<String, Object>> byKind = new LinkedHashMap<>();
        for (Map<String, Object> row : items(QbLegal.MY_CONSENTS_TEMPLATE, token, body("params", params))) {
            byKind.put(String.valueOf(row.get("kind")), row);
        }
        return byKind;
    }

    /** Accepts the versions (kind, number, kind, number, ...). */
    WebTestClient.ResponseSpec acceptCall(String token, Object... kindsAndNumbers) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < kindsAndNumbers.length; i += 2) {
            items.add(body("kind", kindsAndNumbers[i], "versionNo", kindsAndNumbers[i + 1]));
        }
        return call("QB_CONSENT_ACCEPT", token, body("items", items));
    }

    /** The user's consent rows, oldest first. */
    static List<Map<String, Object>> consentRows(String userId) {
        return query("SELECT kind, legal_version_no, source, entry FROM qb_consent_version WHERE user_id = ? "
            + "ORDER BY accepted_at, kind", userId);
    }

    /** Anonymous: a public template's page (no token). */
    WebTestClient.ResponseSpec publicQuery(String id, String query) {
        return client.get().uri("/api/public/queries/" + id + (query == null || query.isEmpty() ? "" : "?" + query))
            .exchange();
    }

    /** Anonymous: the help centre's questions for an audience containing {@code q}, which is encoded here. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> publicSearch(String audience, String q) {
        Map<String, Object> page = client.get().uri(b -> b.path("/api/public/queries/" + QbLegal.PUBLIC_FAQ_TEMPLATE)
                .queryParam("p.audience", "{audience}").queryParam("p.q", "{q}").build(audience, q))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> publicItems(String id, String query) {
        Map<String, Object> page = publicQuery(id, query).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    /** The tables of Q7b were only ever inserted into, and carry the append-only guard. */
    static void assertLegalOnlyInserted() {
        for (String table : LEGAL_TABLES) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table).isNotEmpty();
        }
    }
}

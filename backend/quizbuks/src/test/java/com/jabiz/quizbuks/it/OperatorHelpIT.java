package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The help centre's audience OPERATOR: an operator's members read the published questions of it after signing in
 * ({@code qb.operator.faq}), nobody else does, and it is never public.
 */
class OperatorHelpIT extends LegalItSupport {

    @AfterEach
    void onlyInserted() {
        assertLegalOnlyInserted();
    }

    void faq(String audience, String question, int seq, boolean published) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("audience", audience);
        attributes.put("category", "OTHER");
        attributes.put("question", Map.of("en", question, "ja", question + "（日本語）"));
        attributes.put("answer", Map.of("en", "Answer of " + question));
        attributes.put("seq", seq);
        attributes.put("published", published);
        client.post().uri("/api/datasets/" + QbLegal.defaultDataset(QbLegal.FAQ) + "/commit")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, legalAdmin())
            .bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .exchange().expectStatus().isOk();
    }

    private List<Object> questions(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (Object) ((Map<?, ?>) row.get("question")).get("en")).toList();
    }

    @Test
    void anOperatorsMembersReadTheirOwnPublishedQuestionsAndNobodyElseDoes() {
        faq("OPERATOR", "Op two", 2, true);
        faq("OPERATOR", "Op one", 1, true);
        faq("OPERATOR", "Op draft", 3, false);
        faq("APP", "App one", 1, true);
        faq("SPONSOR", "Sponsor one", 1, true);
        faq("ALL", "Both one", 1, true);
        String member = TestTokens.bearer(tokens, "op-help-member", QbPermissions.OPERATOR_USE);
        // Only the published ones of the operators' audience, in their order: not the app's, the sponsors', the shared
        // ones (ALL is the app's and the sponsors' both) and not the draft.
        assertThat(questions(items("qb.operator.faq", member, Map.of()))).containsExactly("Op one", "Op two");
        // Searched in any language, literally; the draft is not found by it.
        assertThat(questions(items("qb.operator.faq", member, Map.of("params", Map.of("q", "（日")))))
            .containsExactly("Op one", "Op two");
        assertThat(items("qb.operator.faq", member, Map.of("params", Map.of("q", "%")))).isEmpty();
        assertThat(items("qb.operator.faq", member, Map.of("params", Map.of("q", "draft")))).isEmpty();
        // A Taker and a Sponsor have no such help.
        client.post().uri("/api/queries/qb.operator.faq").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, tokenOf(u1(), QbRoles.TAKER)).bodyValue(Map.of()).exchange()
            .expectStatus().isForbidden();
        client.post().uri("/api/queries/qb.operator.faq").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of()).exchange().expectStatus().isUnauthorized();
        // Never public: the public help centre does not hand it out, whatever audience is asked for.
        assertThat(questions(publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=OPERATOR")))
            .containsExactly("Both one");
        assertThat(questions(publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=APP")))
            .containsExactlyInAnyOrder("App one", "Both one");
        assertThat(questions(publicItems(QbLegal.PUBLIC_FAQ_TEMPLATE, "p.audience=SPONSOR")))
            .containsExactlyInAnyOrder("Sponsor one", "Both one");
        assertThat(publicSearch("APP", "Op one")).isEmpty();
    }
}

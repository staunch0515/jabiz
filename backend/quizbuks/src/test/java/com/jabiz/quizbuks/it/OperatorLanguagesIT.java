package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operators by language (docs/quizbuks/plans/Q59-languages.md, Q59c): headquarters gives an operator the language
 * whose new sponsors it takes, one active operator a language; a sponsor's first application goes to the operator of
 * the language they applied in, and stays where it is after.
 */
class OperatorLanguagesIT extends PlayItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "q59c-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private Map<String, Object> save(Map<String, ?> body) {
        return run(OperatorProcesses.SAVE, hq(), body);
    }

    private String newOperator(String name, String language) {
        return (String) save(body("name", name, "contactName", "Owner of " + name, "contactEmail",
            "ops@" + name.toLowerCase() + ".example", "language", language)).get("operatorId");
    }

    private Map<String, Object> operator(String operatorId) {
        return items("qb.admin.operators", hq(), Map.of("params", Map.of("operatorId", operatorId))).getFirst();
    }

    /** The sponsor's application, made in the console's {@code language}. */
    private void applyIn(String userId, String language) {
        var result = client.post().uri("/api/processes/QB_SPONSOR_APPLY/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, sponsor(userId))
            .header(HttpHeaders.ACCEPT_LANGUAGE, language)
            .bodyValue(body("displayName", "Owner", "company", "Company of " + userId, "acceptTerms", true))
            .exchange().expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(String.valueOf(result.getResponseBody())).isEqualTo(200);
    }

    private List<Map<String, Object>> assignment(String userId) {
        return query("SELECT operator_id, assigned_by, reason FROM qb_sponsor_operator WHERE user_id = ?", userId);
    }

    @Test
    void oneActiveOperatorTakesALanguage() {
        String taipei = newOperator(unique("Taipei"), "zh");
        assertThat(operator(taipei)).containsEntry("language", "zh");
        // A second active operator of the language is refused; without a language, any number.
        assertThat(codes(refused(OperatorProcesses.SAVE, hq(), body("name", "Shanghai", "contactName", "S",
            "contactEmail", "ops@shanghai.example", "language", "zh"), 422)))
            .containsExactly(OperatorCodes.LANGUAGE_TAKEN);
        String plain = newOperator(unique("Plain"), null);
        assertThat(operator(plain).get("language")).isNull();
        newOperator(unique("Plain2"), null);
        // Only the platform's languages.
        refused(OperatorProcesses.SAVE, hq(), body("name", "Paris", "contactName", "P", "contactEmail",
            "ops@paris.example", "language", "fr"), 400);

        // A suspended operator keeps its language and takes no one; it runs again only once the language is free.
        String shanghai = (String) save(body("name", "Shanghai", "contactName", "S", "contactEmail",
            "ops@shanghai.example", "language", "zh", "status", QbOperators.SUSPENDED)).get("operatorId");
        assertThat(codes(refused(OperatorProcesses.SAVE, hq(), body("operatorId", shanghai, "name", "Shanghai",
            "contactName", "S", "contactEmail", "ops@shanghai.example", "language", "zh", "status",
            QbOperators.ACTIVE, "baseRevision", 1), 422))).containsExactly(OperatorCodes.LANGUAGE_TAKEN);
        // Saving the language's own operator again is no conflict with itself.
        save(body("operatorId", taipei, "name", "Taipei", "contactName", "T", "contactEmail", "ops@taipei.example",
            "language", "zh", "baseRevision", 1));
        save(body("operatorId", taipei, "name", "Taipei", "contactName", "T", "contactEmail", "ops@taipei.example",
            "baseRevision", 2));
        assertThat(operator(taipei).get("language")).isNull();
        save(body("operatorId", shanghai, "name", "Shanghai", "contactName", "S", "contactEmail",
            "ops@shanghai.example", "language", "zh", "status", QbOperators.ACTIVE, "baseRevision", 1));
        assertThat(operator(shanghai)).containsEntry("language", "zh").containsEntry("status", QbOperators.ACTIVE);
        // The database holds it too, whatever the path.
        assertThat(query("SELECT count(*) AS n FROM qb_operator WHERE language = 'zh' AND status = 'ACTIVE'"))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("n")).intValue()).isEqualTo(1));
    }

    @Test
    void aFirstApplicationGoesToTheOperatorOfItsLanguage() {
        String tokyo = newOperator(unique("Tokyo"), "ja");

        // In Japanese: Tokyo's, by the platform.
        String ja = user(unique("s-q59c-ja"));
        applyIn(ja, "ja");
        assertThat(assignment(ja)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("operator_id", tokyo).containsEntry("assigned_by", QbOperators.BY_LANGUAGE)
            .containsEntry("reason", "Sign-up language: ja"));

        // In English, which no operator takes: headquarters'.
        String en = user(unique("s-q59c-en"));
        applyIn(en, "en");
        assertThat(assignment(en)).isEmpty();

        // Applying again does not move them, even once a language has an operator.
        String london = newOperator(unique("London"), "en");
        applyIn(en, "en");
        assertThat(assignment(en)).isEmpty();
        // Headquarters moves a sponsor by hand as before; the platform's assignment is replaced.
        run(OperatorProcesses.ASSIGN, hq(), body("userId", ja, "operatorId", london, "reason", "Moved"));
        assertThat(assignment(ja)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("operator_id", london).containsEntry("assigned_by", "q59c-hq"));

        // A suspended operator takes no one.
        save(body("operatorId", london, "name", "London", "contactName", "L", "contactEmail", "ops@london.example",
            "language", "en", "status", QbOperators.SUSPENDED, "baseRevision", 1));
        String late = user(unique("s-q59c-late"));
        applyIn(late, "en");
        assertThat(assignment(late)).isEmpty();
    }
}

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

    /** The sponsor's application in Japanese, naming the trial that brought them (the sign-up link's key). */
    private Map<String, Object> applyWithTrial(String userId, String trialKey) {
        var result = client.post().uri("/api/processes/QB_SPONSOR_APPLY/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, sponsor(userId))
            .header(HttpHeaders.ACCEPT_LANGUAGE, "ja")
            .bodyValue(body("displayName", "Owner", "company", "Company of " + userId, "acceptTerms", true,
                "trialKey", trialKey))
            .exchange().expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(String.valueOf(result.getResponseBody())).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) result.getResponseBody().get("output");
        return output;
    }

    /** A trial mailed to a business by {@code owner} (an operator, or headquarters' {@code direct}); returns its key. */
    private String sentTrial(String owner, String domain) {
        String direction = java.util.UUID.randomUUID().toString();
        execute("INSERT INTO qb_prospect_direction (direction_id, owner, name, brief, active, per_search, created_at, "
            + "created_by, revision, version) VALUES (?, ?, 'Tea shops', 'Tea', true, 5, now(), 'q48e1', 1, 0)",
            direction, owner);
        String key = java.util.HexFormat.of().formatHex(java.security.SecureRandom.getSeed(24));
        execute("INSERT INTO qb_prospect (prospect_id, owner, direction_id, domain, website_url, status, found_at, "
            + "trial_key, sent_at, revision, version) VALUES (?, ?, ?, ?, ?, 'SENT', now(), ?, now(), 1, 0)",
            java.util.UUID.randomUUID().toString(), owner, direction, domain, "https://" + domain, key);
        return key;
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

    /**
     * Q48e-1: a first application that names the trial an operator mailed goes to that operator, review and all: the
     * operator that prospected the business looks after it from the start, not the one of the language. A trial of
     * headquarters', or one another sponsor claimed, does not count.
     */
    @Test
    void aFirstApplicationByAnOperatorsTrialIsThatOperatorsReviewFromTheStart() {
        String scout = newOperator(unique("Scout"), null);
        String key = sentTrial(scout, unique("tea") + ".example");
        String sponsor = user(unique("s-q48e1-trial"));
        Map<String, Object> applied = applyWithTrial(sponsor, key);
        assertThat(assignment(sponsor)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("operator_id", scout);
            assertThat((String) row.get("assigned_by")).startsWith("prospect:");
        });
        assertThat(query("SELECT converted_user_id FROM qb_prospect WHERE trial_key = ?", key).getFirst())
            .containsEntry("converted_user_id", sponsor);
        // Reviewed by a person (the tests' setting): the request is the scout's.
        assertThat(applied).as("the application").containsEntry("reviewStatus", "PENDING");
        assertThat(query("SELECT scope_name, scope_value FROM sys_approval_request_version"
            + " WHERE request_id = ?::uuid", String.valueOf(applied.get("approvalRequestId"))).getFirst())
            .containsEntry("scope_name", QbOperators.SCOPE).containsEntry("scope_value", scout);
        // The claim the console makes afterwards changes nothing.
        assertThat(run("QB_PROSPECT_CLAIM", sponsor(sponsor), body("trialKey", key)))
            .containsEntry("outcome", "again");

        // The same trial for another sponsor, or one of headquarters': by the language, as before (the active
        // operator of Japanese when there is one, else headquarters').
        List<Object> japanese = query("SELECT operator_id FROM qb_operator WHERE language = 'ja' AND status = 'ACTIVE'")
            .stream().map(row -> row.get("operator_id")).toList();
        String other = user(unique("s-q48e1-other"));
        applyWithTrial(other, key);
        String direct = user(unique("s-q48e1-direct"));
        applyWithTrial(direct, sentTrial("direct", unique("hq") + ".example"));
        for (String byLanguage : List.of(other, direct)) {
            assertThat(assignment(byLanguage)).extracting(row -> row.get("operator_id"))
                .containsExactlyElementsOf(japanese);
        }
    }
}

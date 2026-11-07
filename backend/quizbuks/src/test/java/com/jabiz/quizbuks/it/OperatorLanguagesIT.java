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
 * Operators' languages and first applications (Q59c, as F-S1 changed them, docs/quizbuks/plans/impl/F-S1.md): an
 * operator's language is shown and used by prospecting only, and several operators may share one; a sponsor who
 * applies on their own goes to the platform operator whatever their language, one brought by an operator's trial to
 * that operator, and either stays where it is after.
 */
class OperatorLanguagesIT extends PlayItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "q59c-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private Map<String, Object> save(Map<String, ?> body) {
        return run(OperatorProcesses.SAVE, hq(), body);
    }

    private String operatorOf(String name, String language) {
        return newOperatorWithLead(hq(), name, language).operatorId();
    }

    /** The platform operator QB_SETUP made (F-S1). */
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

    /** A trial mailed to a business by {@code owner} (an operator); returns its key. */
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
    void operatorsMayShareALanguage() {
        String taipei = operatorOf(unique("Taipei"), "zh");
        String shanghai = operatorOf(unique("Shanghai"), "zh");
        assertThat(operator(taipei)).containsEntry("language", "zh");
        assertThat(operator(shanghai)).containsEntry("language", "zh").containsEntry("status", QbOperators.ACTIVE);
        String plain = operatorOf(unique("Plain"), null);
        assertThat(operator(plain).get("language")).isNull();
        // Only the platform's languages.
        refused(OperatorProcesses.SAVE, hq(), body("name", "Paris", "contactName", "P", "contactEmail",
            "ops@paris.example", "language", "fr", "leadUserId", verifiedUser(unique("q59c-paris"))), 400);
        // A new operator needs its lead.
        assertThat(codes(refused(OperatorProcesses.SAVE, hq(), body("name", "Lyon", "contactName", "L",
            "contactEmail", "ops@lyon.example"), 422))).containsExactly(OperatorCodes.LEAD_REQUIRED);
    }

    @Test
    void aFirstApplicationGoesToThePlatformOperatorWhateverItsLanguage() {
        String tokyo = operatorOf(unique("Tokyo"), "ja");
        String platform = platformOperator();

        String ja = user(unique("s-q59c-ja"));
        applyIn(ja, "ja");
        assertThat(assignment(ja)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("operator_id", platform).containsEntry("assigned_by", QbOperators.BY_DEFAULT));
        String en = user(unique("s-q59c-en"));
        applyIn(en, "en");
        assertThat(assignment(en)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("operator_id", platform));

        // Headquarters moves a sponsor by hand once their review is decided; applying again does not move them.
        String reviewed = newSponsor(unique("s-q59c-moved"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", reviewed, "operatorId", tokyo, "reason", "Moved"));
        assertThat(assignment(reviewed)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("operator_id", tokyo).containsEntry("assigned_by", "q59c-hq"));
    }

    /**
     * Q48e-1: a first application that names the trial an operator mailed goes to that operator, review and all: the
     * operator that prospected the business looks after it from the start, not the one of the language. A trial of
     * headquarters', or one another sponsor claimed, does not count.
     */
    @Test
    void aFirstApplicationByAnOperatorsTrialIsThatOperatorsReviewFromTheStart() {
        String scout = operatorOf(unique("Scout"), null);
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

        // The same trial for another sponsor: the platform operator, as for anyone who applies on their own.
        String other = user(unique("s-q48e1-other"));
        applyWithTrial(other, key);
        assertThat(assignment(other)).extracting(row -> row.get("operator_id")).containsExactly(platformOperator());
    }
}

package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.BanProcesses;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operators see and act on their own sponsors only (docs/quizbuks/plans/Q59-languages.md §6, Q59c-2; platform
 * decision D64): an operator's session carries its operator, and the sponsors, their content and publications, the
 * reviews and the acts on them are limited to the sponsors it looks after; another operator's and headquarters'
 * direct sponsors are not found. Headquarters sees all.
 */
class OperatorIsolationIT extends PlayItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "q59c2-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ,
            QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.ADMIN_QUIZ_READ, "approval.read");
    }

    /** A session of the operators' entry, as its sign-in issues it: the role's permissions, in its operator. */
    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }

    private String newOperator(String name) {
        return (String) run(OperatorProcesses.SAVE, hq(), body("name", name, "contactName", "Owner of " + name,
            "contactEmail", "ops@" + name.toLowerCase() + ".example")).get("operatorId");
    }

    /** A verified user made a member of the operator; returns the user id. */
    private String member(String operatorId, String name) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId, "userId", userId, "lead", true));
        return userId;
    }

    private void assign(String sponsor, String operatorId) {
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "Q59c-2"));
    }

    @SuppressWarnings("unchecked")
    private List<Object> datasetIds(String dataset, String token) {
        var result = client.post().uri("/api/datasets/" + dataset + "/query").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of("limit", 1000)).exchange().expectBody(MAP)
            .returnResult();
        assertThat(result.getStatus().value()).as(dataset + ": " + result.getResponseBody()).isEqualTo(200);
        return ((List<Map<String, Object>>) result.getResponseBody().get("items")).stream()
            .map(row -> row.get("id")).map(String::valueOf).map(Object.class::cast).toList();
    }

    private List<Object> column(String template, String token, String column) {
        return items(template, token, Map.of()).stream().map(row -> row.get(column)).map(String::valueOf)
            .map(Object.class::cast).toList();
    }

    @Test
    void anOperatorSeesAndActsOnItsOwnSponsorsOnly() {
        String north = newOperator(unique("North"));
        String south = newOperator(unique("South"));
        String northMember = member(north, unique("q59c2-n"));
        String southMember = member(south, unique("q59c2-s"));
        String northSession = operatorSession(northMember, north);
        String southSession = operatorSession(southMember, south);

        String ann = newSponsor(unique("s-q59c2-ann"));
        String bob = newSponsor(unique("s-q59c2-bob"));
        String direct = newSponsor(unique("s-q59c2-direct"));
        assign(ann, north);
        assign(bob, south);
        credit(ann, 10_000);
        credit(bob, 10_000);
        credit(direct, 10_000);
        String annPublication = livePublication(ann, "Ann's");
        String bobPublication = livePublication(bob, "Bob's");
        String directPublication = livePublication(direct, "Direct");

        // The operator's sponsors list, the publications and the dataset API: its own sponsors' rows only.
        assertThat(column("qb.operator.sponsors", northSession, "userId")).contains(ann).doesNotContain(bob, direct);
        assertThat(column("qb.admin.publications", northSession, "publicationId")).contains(annPublication)
            .doesNotContain(bobPublication, directPublication);
        assertThat(datasetIds(QbPublications.defaultDataset(QbPublications.PUBLICATION), northSession))
            .contains(annPublication).doesNotContain(bobPublication, directPublication);
        assertThat(datasetIds(QbPublications.defaultDataset(QbPublications.PUBLICATION), southSession))
            .contains(bobPublication).doesNotContain(annPublication, directPublication);
        List<Object> northQuizzes = datasetIds(QbContent.defaultDataset(QbContent.QUIZ), northSession);
        assertThat(northQuizzes).isNotEmpty();
        assertThat(query("SELECT DISTINCT owner_id FROM qb_quiz_version WHERE quiz_id::text = ANY(?)",
            (Object) northQuizzes.stream().map(String::valueOf).toArray(String[]::new)))
            .extracting(row -> String.valueOf(row.get("owner_id"))).containsOnly(ann);
        // The live public cards are read through the public template only: not through the dataset API.
        client.post().uri("/api/datasets/" + QbPublications.PUBLIC_CARDS + "/query")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, northSession)
            .bodyValue(Map.of("limit", 10)).exchange().expectStatus().isForbidden();
        // Headquarters sees all.
        assertThat(datasetIds(QbPublications.defaultDataset(QbPublications.PUBLICATION), hq()))
            .contains(annPublication, bobPublication, directPublication);

        // Another operator's sponsor, or a direct one, is not found to act on; its own is.
        refused(PublicationAdmin.OPERATOR_PAUSE, northSession, body("publicationId", bobPublication, "reason",
            "Not ours"), 404);
        refused(PublicationAdmin.OPERATOR_PAUSE, northSession, body("publicationId", directPublication, "reason",
            "Not ours"), 404);
        run(PublicationAdmin.OPERATOR_PAUSE, northSession, body("publicationId", annPublication, "reason", "Ours"));
        refused(BanProcesses.OPERATOR_BAN, northSession, body("userId", bob, "reason", "Not ours"), 404);
        refused(BanProcesses.OPERATOR_BAN, southSession, body("userId", direct, "reason", "Not ours"), 404);
        run(BanProcesses.OPERATOR_BAN, southSession, body("userId", bob, "reason", "Ours"));
        refused(BanProcesses.OPERATOR_UNBAN, northSession, body("userId", bob), 404);
    }

    @Test
    void aSponsorsReviewIsItsOperatorsOnly() {
        String east = newOperator(unique("East"));
        String west = newOperator(unique("West"));
        String eastSession = operatorSession(member(east, unique("q59c2-e")), east);
        String westSession = operatorSession(member(west, unique("q59c2-w")), west);
        String carl = newSponsor(unique("s-q59c2-carl"));
        assign(carl, east);
        credit(carl, 10_000);
        // Reviews by a person (the switch off): the publication's request is the east's.
        autoApprove(false);
        String requestId;
        try {
            String token = sponsorToken(carl);
            String versionId = version(token, "Carl's quiz");
            java.time.Instant now = clock.instant();
            Map<String, Object> save = complete(versionId, "Carl's", now, now.plus(java.time.Duration.ofDays(7)));
            String publication = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
            run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publication));
            requestId = String.valueOf(query("SELECT approval_request_id FROM qb_publication_version"
                + " WHERE publication_id = ?::uuid ORDER BY version_no DESC LIMIT 1", publication).getFirst()
                .get("approval_request_id"));
        } finally {
            autoApprove(true);
        }
        assertThat(query("SELECT scope_name, scope_value FROM sys_approval_request_version WHERE request_id = ?::uuid",
            requestId).getFirst()).containsEntry("scope_name", QbOperators.SCOPE).containsEntry("scope_value", east);
        // The west does not see the review's task nor decide it; the east does.
        assertThat(taskSubjects(westSession)).doesNotContain(requestId);
        assertThat(taskSubjects(eastSession)).contains(requestId);
        refused("APPROVAL_DECIDE", westSession, body("requestId", requestId, "decision", "APPROVE", "reason", "ok"),
            404);
    }

    /**
     * Q48e-1: voiding a prize and deciding a disputed digital prize check the caller's membership within the act, as the
     * operator's other acts do: a member removed stops at once, though their access token is still valid. The check
     * comes before the record is read, so a record that does not exist shows it: refused for the removed member, not
     * found for a member and for headquarters.
     */
    @Test
    void aRemovedMemberNoLongerVoidsOrResolvesWithTheirTokenStillValid() {
        String operator = newOperator(unique("Gone"));
        String stays = member(operator, unique("q48e1-stays"));
        String leaves = member(operator, unique("q48e1-leaves"));
        String staysSession = operatorSession(stays, operator);
        String leavesSession = operatorSession(leaves, operator);
        run(OperatorProcesses.MEMBER_REMOVE, hq(), body("userId", leaves));
        String hq = TestTokens.bearer(tokens, "q48e1-hq", QbPermissions.REDEMPTION_VOID, QbPermissions.DIGITAL_RESOLVE);
        Map<String, Object> voiding = body("redemptionId", java.util.UUID.randomUUID().toString(), "note", "gone");
        Map<String, Object> resolving = body("digitalPrizeId", java.util.UUID.randomUUID().toString(),
            "resolution", "VOID", "note", "gone");

        assertThat(String.valueOf(refused("QB_REDEMPTION_VOID", leavesSession, voiding, 422).get("violations")))
            .contains("QB_OPERATOR_NOT_MEMBER");
        assertThat(String.valueOf(refused("QB_DIGITAL_PRIZE_RESOLVE", leavesSession, resolving, 422)
            .get("violations"))).contains("QB_OPERATOR_NOT_MEMBER");
        refused("QB_REDEMPTION_VOID", staysSession, voiding, 404);
        refused("QB_DIGITAL_PRIZE_RESOLVE", staysSession, resolving, 404);
        refused("QB_REDEMPTION_VOID", hq, voiding, 404);
        refused("QB_DIGITAL_PRIZE_RESOLVE", hq, resolving, 404);
    }

    @SuppressWarnings("unchecked")
    private List<Object> taskSubjects(String token) {
        Map<String, Object> result = client.get().uri("/api/tasks/mine").header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) result.get("tasks")).stream().map(task -> task.get("subjectId"))
            .map(Object.class::cast).toList();
    }
}

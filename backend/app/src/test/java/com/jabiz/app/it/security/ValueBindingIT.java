package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItBindingFixtures;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Values are bound as the type of the field they are compared with (ROADMAP 16b fix; QuizBuks Q2 platform gaps 15 and
 * 16): a sign-in guard loading by a text field or by a uuid reference, and a dataset scope taken from the caller
 * (a text actor id) against a text column or a uuid column, in templates and in the dataset API. A value that cannot
 * be converted is the caller's 400, never a database error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ValueBindingIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";

    private void mark(String userText, String userRef, String reason) {
        Map<String, Object> attributes = new java.util.HashMap<>(Map.of("markId", unique("m"), "reason", reason));
        if (userText != null) {
            attributes.put("userText", userText);
        }
        if (userRef != null) {
            attributes.put("userRef", userRef);
        }
        insert(ItBindingFixtures.MARK_DATASET, attributes, null);
    }

    private String as(String actor) {
        return TestTokens.bearer(tokens, actor, "it.query", "it.read", "it.write");
    }

    private WebTestClient.ResponseSpec template(String id, String actor) {
        return post("/api/queries/" + id, as(actor), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> reasons(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream().map(row -> row.get("reason")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Object> datasetReasons(String dataset, String actor) {
        Map<String, Object> page = post("/api/datasets/" + dataset + "/query", as(actor), Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(row -> ((Map<String, Object>) row.get("attributes")).get("reason")).toList();
    }

    /** A user who may sign in; returns the user id. */
    private String user(String name) {
        return userWith(name, PASSWORD, "it.read");
    }

    private List<Object> templateReasons(String id, String actor) {
        return reasons(template(id, actor).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody());
    }

    // ---------------------------------------------------------------- Gap 15: the sign-in guard's loads

    @Test
    void aGuardLoadingByATextFieldLetsUnmarkedUsersIn() {
        String name = unique("ann");
        String userId = user(name);
        mark(userId, null, "note");
        mark(null, userId, "note");

        signIn(name, PASSWORD);

        Map<String, List<Map<String, Object>>> seen = ItBindingFixtures.SEEN.get(userId);
        assertThat(seen.get(ItBindingFixtures.BY_TEXT)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userText", userId).containsEntry("reason", "note"));
        assertThat(seen.get(ItBindingFixtures.BY_REF)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("reason", "note"));
    }

    @Test
    void aGuardLoadingByATextFieldRefusesMarkedUsers() {
        String name = unique("bob");
        String userId = user(name);
        mark(userId, null, ItBindingFixtures.BLOCK);

        assertThat(ruleCode(login(name, PASSWORD).expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("SIGN_IN_REFUSED");
        assertThat(ItBindingFixtures.SEEN.get(userId).get(ItBindingFixtures.BY_TEXT)).hasSize(1);
        assertThat(ItBindingFixtures.SEEN.get(userId).get(ItBindingFixtures.BY_REF)).isEmpty();
    }

    @Test
    void aGuardLoadingByAUuidReferenceRefusesMarkedUsers() {
        String name = unique("cid");
        String userId = user(name);
        mark(null, userId, ItBindingFixtures.BLOCK);
        // Another user's mark is not this user's.
        String other = createUser(unique("gil"), PASSWORD);
        mark(other, other, ItBindingFixtures.BLOCK);

        assertThat(ruleCode(login(name, PASSWORD).expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("SIGN_IN_REFUSED");
        assertThat(ItBindingFixtures.SEEN.get(userId).get(ItBindingFixtures.BY_REF)).hasSize(1);
        assertThat(ItBindingFixtures.SEEN.get(userId).get(ItBindingFixtures.BY_TEXT)).isEmpty();
    }

    // ---------------------------------------------------------------- Gap 16: scope values in templates and datasets

    @Test
    void aTextActorIsComparedWithAUuidScopeColumnAsAUuid() {
        String me = createUser(unique("dan"), PASSWORD);
        mark(null, me, "mine");
        mark(null, createUser(unique("eve"), PASSWORD), "theirs");

        assertThat(templateReasons("it.my_marks_by_ref", me)).containsExactly("mine");
        // Written as the platform writes user ids or not, the caller is the same.
        assertThat(templateReasons("it.my_marks_by_ref", me.toUpperCase())).containsExactly("mine");

        assertThat(datasetReasons(ItBindingFixtures.MINE_BY_REF_DATASET, me)).containsExactly("mine");
    }

    @Test
    void writesThroughAUuidScopeFillAndCheckTheScopeAsAUuid() {
        String me = createUser(unique("fay"), PASSWORD);
        post("/api/datasets/" + ItBindingFixtures.MINE_BY_REF_DATASET + "/commit", as(me), Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("markId", unique("w"), "reason", "filled")))))
            .expectStatus().isOk();
        post("/api/datasets/" + ItBindingFixtures.MINE_BY_REF_DATASET + "/commit", as(me), Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("markId", unique("w"), "reason", "given",
                "userRef", me)))))
            .expectStatus().isOk();

        assertThat(templateReasons("it.my_marks_by_ref", me)).containsExactlyInAnyOrder("filled", "given");
        assertThat(query("SELECT f_reason FROM it_account_mark WHERE f_user_ref = ?::uuid ORDER BY f_reason", me))
            .extracting(row -> row.get("f_reason")).containsExactly("filled", "given");
    }

    @Test
    void aTextActorIsComparedWithATextScopeColumnAsText() {
        String me = unique("alice");
        mark(me, null, "mine");
        mark(unique("other"), null, "theirs");

        assertThat(templateReasons("it.my_marks_by_text", me)).containsExactly("mine");
        assertThat(datasetReasons(ItBindingFixtures.MINE_BY_TEXT_DATASET, me)).containsExactly("mine");
    }

    @Test
    void anActorThatIsNoUuidIsRefusedByAUuidScopeAsInvalid() {
        String notAUuid = unique("alice");

        assertThat(ruleCode(template("it.my_marks_by_ref", notAUuid).expectStatus().isBadRequest()
            .expectBody(MAP).returnResult().getResponseBody())).isEqualTo("INVALID_VALUE");
        assertThat(ruleCode(post("/api/datasets/" + ItBindingFixtures.MINE_BY_REF_DATASET + "/query", as(notAUuid),
            Map.of()).expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("INVALID_VALUE");
    }
}

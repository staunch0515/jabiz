package com.jabiz.app.it.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names pages show for user ids (docs/design/10-security.md section 14; ROADMAP phase 14r): any signed-in user
 * resolves ids to display names, nothing else about a user (not another user's login name), and nothing for an id
 * that is no user's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class UserNamesIT extends SecurityItSupport {

    private static final String PASSWORD = "correct horse battery";

    @Test
    @SuppressWarnings("unchecked")
    void aSignedInUserSeesTheNamesOfUsersById() {
        String alice = unique("alice");
        String aliceId = userWith(alice, PASSWORD, "pricing.price.read");
        String bob = unique("bob");
        String bobId = createUser(bob, PASSWORD);
        String session = bearerOf(signIn(alice, PASSWORD));

        Map<String, Object> me = get("/api/auth/me", session).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(me).containsEntry("userId", aliceId).containsEntry("displayName", alice);

        String unknown = UUID.randomUUID().toString();
        Map<String, Object> answer = get("/api/users/names?ids=" + aliceId + "&ids=" + bobId + "&ids=" + unknown
            + "&ids=system", session).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((Map<String, Object>) answer.get("names")).containsOnly(Map.entry(aliceId, alice),
            Map.entry(bobId, bob));
        assertThat((Map<String, Object>) get("/api/users/names", session).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody().get("names")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aUserWithoutADisplayNameIsShownByIdToOthersAndByLoginNameToThemselves() {
        String carol = unique("carol");
        Map<String, Object> created = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", carol, "password", PASSWORD)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        String carolId = (String) ((Map<String, Object>) created.get("output")).get("userId");
        assign(carolId, createRole(unique("ROLE"), "pricing.price.read"), null);
        String dave = unique("dave");
        userWith(dave, PASSWORD, "pricing.price.read");

        Map<String, Object> answer = get("/api/users/names?ids=" + carolId, bearerOf(signIn(dave, PASSWORD)))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((Map<String, Object>) answer.get("names")).isEmpty();
        assertThat(get("/api/auth/me", bearerOf(signIn(carol, PASSWORD))).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody()).containsEntry("displayName", carol);
    }

    @Test
    @SuppressWarnings("unchecked")
    void idsBeyondTheLimitAreLeftOut() {
        String erin = unique("erin");
        String erinId = userWith(erin, PASSWORD, "pricing.price.read");
        StringBuilder query = new StringBuilder("/api/users/names?ids=");
        for (int i = 0; i < 200; i++) {
            query.append(UUID.randomUUID()).append("&ids=");
        }
        query.append(erinId);
        Map<String, Object> answer = get(query.toString(), bearerOf(signIn(erin, PASSWORD))).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat((Map<String, Object>) answer.get("names")).isEmpty();
    }

    @Test
    void namesNeedASignedInUser() {
        get("/api/users/names?ids=" + UUID.randomUUID(), null).expectStatus().isUnauthorized();
    }
}

package com.jabiz.app.it.mail;

import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signed-in user's mail preferences and mail language (docs/design/18-numbering-approvals-tasks.md section 5.6;
 * decision D49; ROADMAP phase 16s): the list holds the notifications of the session's sign-in entry only, by their
 * display names in the request's language; a template of another entry can neither be seen nor set there, and is
 * refused exactly like a name that is no notification; the unsubscribe link works whatever the entry; the mail
 * language is read and set by its own user only.
 */
@SpringBootTest
class MailPreferencesIT extends SecurityItSupport {

    @Autowired
    JwtService jwt;

    /** A user of the platform, with an address. */
    @SuppressWarnings("unchecked")
    private String user() {
        Map<String, Object> input = new HashMap<>();
        input.put("userName", unique("pref"));
        input.put("email", unique("pref").toLowerCase() + "@example.com");
        Map<String, Object> result = post("/api/processes/SEC_USER_CREATE/latest", admin(), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) result.get("output")).get("userId");
    }

    /** The user's access token of a session at that sign-in entry. */
    private String at(String entry, String userId) {
        return "Bearer " + jwt.issue(new Actor(userId, null, Set.of(), Set.of(), jwt.clock().instant(), null, entry,
            true)).token();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> preferences(String bearer, String language) {
        Map<String, Object> body = client.get().uri("/api/auth/mail/preferences")
            .header(HttpHeaders.AUTHORIZATION, bearer).header(HttpHeaders.ACCEPT_LANGUAGE, language).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) body.get("templates");
    }

    private static Map<String, Object> preference(String template, boolean subscribed, String displayName) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("template", template);
        expected.put("subscribed", subscribed);
        expected.put("displayName", displayName);
        return expected;
    }

    private Map<String, Object> set(String bearer, Map<String, Object> body, int status) {
        return post("/api/auth/mail/preferences", bearer, body).expectStatus().isEqualTo(status).expectBody(MAP)
            .returnResult().getResponseBody();
    }

    private Map<String, Object> mailLanguage(String bearer) {
        return get("/api/auth/account/locale", bearer).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    @Test
    void eachEntryListsItsOwnNotificationsByTheirNames() {
        String user = user();

        // The administration: the notification for every entry, not the portal's (commerce.order-shipped, it.offers).
        assertThat(preferences(at("admin", user), "en")).containsExactly(preference("it.news", true, "News"));
        // The portal: its own and the one for every entry, in the request's language.
        assertThat(preferences(at("portal", user), "ja")).containsExactly(
            preference("commerce.order-shipped", true, "発送のお知らせ"),
            preference("it.news", true, "お知らせ"),
            preference("it.offers", true, "お客様向けのご案内"));
        assertThat(preferences(at("portal", user), "zh")).extracting(row -> row.get("displayName"))
            .containsExactly("发货通知", "消息", "给客户的优惠");
        // A language the application does not have: its default (en).
        assertThat(preferences(at("portal", user), "fr")).extracting(row -> row.get("displayName"))
            .containsExactly("Shipment notices", "News", "Offers for customers");
        // An entry no template is declared for sees the ones for every entry only.
        assertThat(preferences(at("shop", user), "en")).extracting(row -> row.get("template"))
            .containsExactly("it.news");
    }

    @Test
    void anotherEntrysNotificationCannotBeSetAndIsRefusedLikeAnUnknownName() {
        String user = user();
        String admin = at("admin", user);

        Map<String, Object> foreign = set(admin, Map.of("template", "it.offers", "subscribed", false), 422);
        Map<String, Object> unknown = set(admin, Map.of("template", "it.nothing", "subscribed", false), 422);
        Map<String, Object> transactional = set(admin, Map.of("template", "it.verify", "subscribed", false), 422);
        // The same answer, but for the name the caller sent: nothing tells whether a portal template of that name exists.
        for (Map<String, Object> problem : List.of(foreign, unknown, transactional)) {
            assertThat(ruleCode(problem)).isEqualTo("INVALID_VALUE");
            assertThat(violations(problem)).singleElement().satisfies(v -> assertThat(v.get("field"))
                .isEqualTo("template"));
        }
        assertThat(String.valueOf(violations(foreign).getFirst().get("message"))
            .replace("it.offers", "X")).isEqualTo(String.valueOf(violations(unknown).getFirst().get("message"))
            .replace("it.nothing", "X"));
        assertThat(query("SELECT 1 FROM sec_user_mail_preference_version WHERE user_id = ?::uuid", user)).isEmpty();

        // At the portal it is the user's to set; the administration still does not list it.
        String portal = at("portal", user);
        set(portal, Map.of("template", "it.offers", "subscribed", false), 200);
        assertThat(preferences(portal, "en")).contains(preference("it.offers", false, "Offers for customers"));
        assertThat(preferences(admin, "en")).extracting(row -> row.get("template")).containsExactly("it.news");
    }

    @Test
    void aUserSetsTheirOwnPreferencesOnly() {
        String user = user();
        String other = user();

        // The body names a template and a choice; a user id in it is not read.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template", "it.news");
        body.put("subscribed", false);
        body.put("userId", other);
        set(at("admin", user), body, 200);

        assertThat(preferences(at("admin", user), "en")).containsExactly(preference("it.news", false, "News"));
        assertThat(preferences(at("admin", other), "en")).containsExactly(preference("it.news", true, "News"));
        assertThat(query("SELECT user_id::text AS user_id FROM sec_user_mail_preference_version WHERE user_id IN"
            + " (?::uuid, ?::uuid)", user, other)).extracting(row -> row.get("user_id")).containsExactly(user);
        // Who is no user has no preferences (an actor of the development headers, or a token of no user).
        get("/api/auth/mail/preferences", bearer()).expectStatus().isForbidden();
        get("/api/auth/mail/preferences", null).expectStatus().isUnauthorized();
    }

    @Test
    void theUnsubscribeLinkWorksWhateverTheEntry() {
        String user = user();
        // A link of the portal's notification, used anonymously.
        post("/api/auth/mail/unsubscribe", null, Map.of("token", jwt.issueUnsubscribe(user, "it.offers")))
            .expectStatus().isNoContent();
        assertThat(preferences(at("portal", user), "en")).contains(preference("it.offers", false,
            "Offers for customers"));
        // A link of a template that is no notification stays refused.
        assertThat(ruleCode(post("/api/auth/mail/unsubscribe", null,
            Map.of("token", jwt.issueUnsubscribe(user, "it.verify"))).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody())).isEqualTo("TOKEN_INVALID");
    }

    @Test
    void usersReadAndSetTheLanguageOfTheirOwnMail() {
        String user = user();
        String other = user();
        String self = at("portal", user);

        // Not chosen: mail goes in the application's default language.
        Map<String, Object> initial = mailLanguage(self);
        assertThat(initial).containsEntry("locale", null).containsEntry("effective", "en");
        assertThat(initial.get("languages")).asList().containsExactlyInAnyOrder("zh", "ja", "en");

        post("/api/auth/account/locale", self, Map.of("locale", "ja")).expectStatus().isOk();
        assertThat(mailLanguage(self)).containsEntry("locale", "ja").containsEntry("effective", "ja");
        assertThat(query("SELECT locale FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC"
            + " LIMIT 1", user)).extracting(row -> row.get("locale")).containsExactly("ja");
        // The other user's stays as it was: each reads and sets their own.
        assertThat(mailLanguage(at("admin", other))).containsEntry("locale", null);

        // Empty: back to the default.
        post("/api/auth/account/locale", self, Map.of("locale", "")).expectStatus().isOk();
        assertThat(mailLanguage(self)).containsEntry("locale", null).containsEntry("effective", "en");
        post("/api/auth/account/locale", self, Map.of("locale", "fr")).expectStatus().isBadRequest();

        get("/api/auth/account/locale", bearer()).expectStatus().isForbidden();
        get("/api/auth/account/locale", null).expectStatus().isUnauthorized();
    }
}

package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administrators' account templates (P-01, P-09; docs/quizbuks/plans/Q2-accounts.md, SQL 模板):
 * {@code qb.admin.users} joins the platform's users with their profile, onboarding and ban, searched by user name,
 * address, name or company (ignoring case, the text taken literally) and filtered by country, region, registration,
 * ban, onboarding and verification; {@code qb.admin.sponsors} is the onboarding queue. Both need only
 * {@code qb.admin.users.read}: a template's permissions are its own, the platform's user dataset is not asked.
 */
class AccountTemplatesIT extends AccountItSupport {

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    /** Only the template's permission: no security.user.read. */
    private String reader() {
        return TestTokens.bearer(tokens, "reader", QbPermissions.ADMIN_USERS_READ);
    }

    private List<Map<String, Object>> users(String q, List<Map<String, Object>> filters, String region) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        if (q != null) {
            params.put("q", q);
        }
        if (region != null) {
            params.put("region", region);
        }
        return items("qb.admin.users", reader(), Map.of("params", params, "filters", filters, "limit", 100));
    }

    private static Map<String, Object> filter(String field, String op, Object value) {
        return Map.of("field", field, "op", op, "value", value);
    }

    @Test
    void usersAreFoundByWhatAdministratorsKnowOfThem() {
        String tag = unique("q").substring(2);
        String tom = userWithRole("Tom_" + tag, QbRoles.TAKER);
        run("QB_PROFILE_SAVE", tokenOf(tom, QbRoles.TAKER), body("displayName", "Thomas 100%", "countryCode", "JP",
            "city", "Osaka", "preferredCurrency", "JPY"));
        String ann = userWithRole("Ann-" + tag, QbRoles.TAKER);
        run("QB_PROFILE_SAVE", tokenOf(ann, QbRoles.TAKER), body("displayName", "Ann", "countryCode", "FR",
            "preferredCurrency", "EUR"));
        String sara = userWithRole("Sara-" + tag, QbRoles.SPONSOR);
        run("QB_SPONSOR_APPLY", tokenOf(sara, QbRoles.SPONSOR), body("displayName", "Sara", "company",
            "Quiz_Corp " + tag, "acceptTerms", true));
        String nobody = createUser("Nobody-" + tag);
        String boss = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", ann, "reason", "Spam"));

        // By user name or address, ignoring case.
        assertThat(users(tag.toUpperCase(), List.of(), null)).extracting(row -> row.get("userId"))
            .containsExactlyInAnyOrder(tom, ann, sara, nobody);
        assertThat(users("tom_" + tag + "@EXAMPLE", List.of(), null)).extracting(row -> row.get("userId"))
            .containsExactly(tom);
        // By name or company; % and _ are letters, not wildcards.
        assertThat(users("thomas 100%", List.of(), null)).extracting(row -> row.get("userId")).containsExactly(tom);
        assertThat(users("100_", List.of(), null)).isEmpty();
        assertThat(users("quiz_corp " + tag, List.of(), null)).extracting(row -> row.get("userId"))
            .containsExactly(sara);
        assertThat(users("quiz%corp", List.of(), null)).isEmpty();

        Map<String, Object> tomRow = users("Tom_" + tag, List.of(), null).getFirst();
        assertThat(tomRow).containsEntry("displayName", "Thomas 100%").containsEntry("countryCode", "JP")
            .containsEntry("city", "Osaka").containsEntry("preferredCurrency", "JPY").containsEntry("banned", false)
            .containsEntry("emailVerified", false).containsEntry("enabled", true).containsEntry("sponsorStatus", null)
            .containsEntry("email", "tom_" + tag.toLowerCase() + "@example.com");
        assertThat(tomRow.get("countryName").toString()).contains("Japan");
        assertThat(tomRow.get("registeredAt")).isNotNull();

        // By country, region, ban, onboarding, registration.
        assertThat(users(tag, List.of(filter("countryCode", "eq", "FR")), null)).extracting(row -> row.get("userId"))
            .containsExactly(ann);
        assertThat(users(tag, List.of(), "ASIA")).extracting(row -> row.get("userId")).containsExactly(tom);
        assertThat(users(tag, List.of(), "EUROPE")).extracting(row -> row.get("userId")).containsExactly(ann);
        assertThat(users(tag, List.of(), "GLOBAL")).extracting(row -> row.get("userId"))
            .containsExactlyInAnyOrder(tom, ann);
        // A region matches a whole code, not a part of one.
        assertThat(users(tag, List.of(), "ASI")).isEmpty();
        assertThat(users(tag, List.of(filter("banned", "eq", true)), null)).extracting(row -> row.get("userId"))
            .containsExactly(ann);
        assertThat(users(tag, List.of(filter("sponsorStatus", "eq", "PENDING")), null))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("userId", sara)
                .containsEntry("company", "Quiz_Corp " + tag).containsEntry("displayName", "Sara"));
        // Without a profile, no registration time: such users are not in a time filter.
        assertThat(users(tag, List.of(filter("registeredAt", "gte", "2000-01-01T00:00:00Z")), null))
            .extracting(row -> row.get("userId")).containsExactlyInAnyOrder(tom, ann, sara);
        assertThat(users(tag, List.of(filter("emailVerified", "eq", true)), null)).isEmpty();
    }

    @Test
    void theOnboardingQueueListsApplicationsNewestFirst() {
        String tag = unique("s").substring(2);
        String first = userWithRole("First-" + tag, QbRoles.SPONSOR);
        run("QB_SPONSOR_APPLY", tokenOf(first, QbRoles.SPONSOR), body("displayName", "First", "company",
            "First " + tag, "acceptTerms", true));
        clock.set(clock.instant().plusSeconds(60));
        String second = userWithRole("Second-" + tag, QbRoles.SPONSOR);
        Object request = run("QB_SPONSOR_APPLY", tokenOf(second, QbRoles.SPONSOR), body("displayName", "Second",
            "company", "Second " + tag, "acceptTerms", true)).get("approvalRequestId");

        List<Map<String, Object>> pending = items("qb.admin.sponsors", reader(), Map.of("filters",
            List.of(filter("reviewStatus", "eq", "PENDING"))));
        assertThat(pending).extracting(row -> row.get("userId")).containsSubsequence(second, first);
        assertThat(pending.stream().filter(row -> second.equals(row.get("userId"))).findFirst().orElseThrow())
            .containsEntry("company", "Second " + tag).containsEntry("displayName", "Second")
            .containsEntry("approvalRequestId", request)
            .containsEntry("email", "second-" + tag.toLowerCase() + "@example.com");
        // Approved sponsors A and B are in the queue's history too.
        assertThat(items("qb.admin.sponsors", reader(), Map.of("filters", List.of(filter("reviewStatus", "eq",
            "APPROVED"))))).extracting(row -> row.get("userId")).contains(SPONSOR_A, SPONSOR_B);
    }

    @Test
    void onlyAdministratorsRunTheTemplates() {
        for (String id : List.of("qb.admin.users", "qb.admin.sponsors")) {
            for (String token : List.of(sponsorA(), tokenOf("t", QbRoles.TAKER),
                TestTokens.bearer(tokens, "platform-reader", "security.user.read"))) {
                client.post().uri("/api/queries/" + id).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of()).exchange().expectStatus()
                    .isForbidden();
            }
        }
    }
}

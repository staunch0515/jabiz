package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A taker's own profile (U-02 second step, U-03, U-04, U-70; docs/quizbuks/plans/Q2-accounts.md, D-Q2-2): saved by
 * {@code QB_PROFILE_SAVE} for the caller only, with a country of the dictionary and a currency of the list; the first
 * save that gives a country is registration's second step and sends the welcome mail. Each person reads their own
 * profile; administrators read all; nobody writes through a dataset.
 */
class ProfileIT extends AccountItSupport {

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    private static Map<String, Object> profile(String name, String country, String city, String currency) {
        Map<String, Object> input = body("displayName", name, "countryCode", country, "preferredCurrency", currency);
        if (city != null) {
            input.put("city", city);
        }
        return input;
    }

    @Test
    void aTakerCompletesAndChangesTheirOwnProfile() {
        String taker = userWithRole(unique("tom"), QbRoles.TAKER);
        String token = tokenOf(taker, QbRoles.TAKER);

        Map<String, Object> first = run("QB_PROFILE_SAVE", token, profile("Tom", "JP", "Osaka", "USD"));
        assertThat(first).containsEntry("userId", taker).containsEntry("revision", 1).containsEntry("created", true);
        assertThat(profileOf(taker)).containsEntry("display_name", "Tom").containsEntry("country_code", "JP")
            .containsEntry("city", "Osaka").containsEntry("preferred_currency", "USD");
        // Registration's second step done: the welcome mail (recorded; mail is off in tests).
        assertThat(mailTo(taker)).singleElement().satisfies(mail -> assertThat(mail)
            .containsEntry("template", "qb.welcome").containsEntry("category", "NOTIFICATION")
            .satisfies(m -> assertThat(m.get("params").toString()).contains("Tom")));

        // The same again changes nothing; a change is a new revision; no second welcome.
        assertThat(run("QB_PROFILE_SAVE", token, profile("Tom", "JP", "Osaka", "USD")))
            .containsEntry("revision", 1).containsEntry("created", false);
        Map<String, Object> changed = run("QB_PROFILE_SAVE", token, body("displayName", "Tom", "countryCode", "FR",
            "preferredCurrency", "EUR", "baseRevision", 1));
        assertThat(changed).containsEntry("revision", 2).containsEntry("created", false);
        assertThat(profileOf(taker)).containsEntry("country_code", "FR").containsEntry("city", null)
            .containsEntry("preferred_currency", "EUR");
        assertThat(templatesTo(taker)).containsExactly("qb.welcome");

        // Edited elsewhere meanwhile.
        assertThat(codes(refused("QB_PROFILE_SAVE", token, body("displayName", "Tommy", "countryCode", "FR",
            "preferredCurrency", "EUR", "baseRevision", 1), 422))).containsExactly(AccountCodes.PROFILE_CHANGED);
        // The history keeps every version.
        assertThat(query("SELECT version_no FROM qb_profile_version WHERE user_id = ? ORDER BY version_no", taker))
            .hasSize(2);
    }

    @Test
    void theCountryMustBeKnownAndTheCurrencyOneOfTheList() {
        String taker = userWithRole(unique("ann"), QbRoles.TAKER);
        String token = tokenOf(taker, QbRoles.TAKER);
        assertThat(codes(refused("QB_PROFILE_SAVE", token, profile("Ann", "ZZ", null, "USD"), 422)))
            .containsExactly(AccountCodes.COUNTRY_UNKNOWN);
        assertThat(codes(refused("QB_PROFILE_SAVE", token, profile("Ann", "jp", null, "USD"), 400)))
            .contains(AccountCodes.COUNTRY_FORMAT);
        assertThat(codes(refused("QB_PROFILE_SAVE", token, profile("Ann", "JP", null, "XYZ"), 400)))
            .isNotEmpty();
        assertThat(codes(refused("QB_PROFILE_SAVE", token, profile("   ", "JP", null, "USD"), 400)))
            .contains(AccountCodes.NAME_BLANK);
        // U-03 needs the country, U-04 the currency.
        assertThat(refused("QB_PROFILE_SAVE", token, body("displayName", "Ann", "preferredCurrency", "USD"), 400)
            .toString()).contains("countryCode");
        assertThat(refused("QB_PROFILE_SAVE", token, body("displayName", "Ann", "countryCode", "JP"), 400)
            .toString()).contains("preferredCurrency");
        assertThat(profileOf(taker)).isNull();
        assertThat(mailTo(taker)).isEmpty();
    }

    @Test
    void everyoneReadsTheirOwnProfileAndAdministratorsReadAll() {
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        String bob = userWithRole(unique("bob"), QbRoles.TAKER);
        run("QB_PROFILE_SAVE", tokenOf(ann, QbRoles.TAKER), profile("Ann", "JP", null, "JPY"));
        run("QB_PROFILE_SAVE", tokenOf(bob, QbRoles.TAKER), profile("Bob", "US", null, "USD"));

        assertThat(rows(QbAccounts.MY_PROFILE_DATASET, tokenOf(ann, QbRoles.TAKER))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", ann).containsEntry("displayName", "Ann"));
        assertThat(rows(QbAccounts.MY_PROFILE_DATASET, tokenOf(bob, QbRoles.TAKER))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", bob));
        // A taker does not read the administrators' dataset, nor another's profile by its key.
        queryDataset(PROFILES, tokenOf(ann, QbRoles.TAKER)).expectStatus().isForbidden();
        String bobsProfile = String.valueOf(profileOf(bob).get("profile_id"));
        client.get().uri("/api/datasets/{id}/entities/{key}", QbAccounts.MY_PROFILE_DATASET, bobsProfile)
            .header("Authorization", tokenOf(ann, QbRoles.TAKER)).exchange().expectStatus().isNotFound();
        // Administrators read every profile.
        assertThat(rows(PROFILES, tokenOf("admin-reader", QbRoles.ADMIN_CONTENT)))
            .extracting(row -> row.get("userId")).contains(ann, bob);
        // A sponsor reads their own profile (the name of their onboarding) too.
        assertThat(rows(QbAccounts.MY_PROFILE_DATASET, sponsorA())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", SPONSOR_A));
    }

    @Test
    void nobodyWritesProfilesThroughADataset() {
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        run("QB_PROFILE_SAVE", tokenOf(ann, QbRoles.TAKER), profile("Ann", "JP", null, "JPY"));
        Map<String, Object> row = profileOf(ann);
        for (String dataset : List.of(PROFILES, QbAccounts.MY_PROFILE_DATASET)) {
            for (String token : List.of(admin(), tokenOf(ann, QbRoles.TAKER))) {
                int status = client.post().uri("/api/datasets/" + dataset + "/commit")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Authorization", token)
                    .bodyValue(Map.of("changes", List.of(Map.of("action", "UPDATE", "id", row.get("profile_id"),
                        "version", row.get("version_no"), "attributes", Map.of("displayName", "Hacked")))))
                    .exchange().returnResult(String.class).getStatus().value();
                assertThat(status).as(dataset).isIn(403, 422);
            }
        }
        assertThat(profileOf(ann)).containsEntry("display_name", "Ann");
    }

    @Test
    void onlyTakersWithAVerifiedAddressSaveAProfile() {
        String sara = userWithRole(unique("sara"), QbRoles.SPONSOR);
        // A sponsor holds no qb.me.
        refused("QB_PROFILE_SAVE", tokenOf(sara, QbRoles.SPONSOR), profile("Sara", "JP", null, "JPY"), 403);
        // Unverified: pending activation (U-06), nothing is written.
        String ann = userWithRole(unique("ann"), QbRoles.TAKER);
        assertThat(refused("QB_PROFILE_SAVE", unverifiedTokenOf(ann, QbRoles.TAKER),
            profile("Ann", "JP", null, "JPY"), 403).toString()).contains("EMAIL_NOT_VERIFIED");
        refused("QB_PROFILE_SAVE", TestTokens.bearer(tokens, ann, QbPermissions.ACCOUNT_READ),
            profile("Ann", "JP", null, "JPY"), 403);
        assertThat(profileOf(ann)).isNull();
        assertThat(profileOf(sara)).isNull();
    }
}

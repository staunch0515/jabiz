package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.page.SponsorPageProcesses;
import com.jabiz.quizbuks.page.SponsorProductProcesses;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q69 (docs/quizbuks/plans/Q69-sponsor-erasure.md; platform decision D62): erasing a sponsor's account removes their
 * page, products and profile, tombstones their onboarding, and afterwards ends their running publications (kept as
 * records) with the reason that the account was deleted.
 */
class SponsorErasureIT extends PlayItSupport {

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        // The tests' login is the maintenance login too (a member of jabiz_maintenance, granted below), for the purge.
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @BeforeEach
    void maintenanceRole() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
    }

    @Test
    void anErasedSponsorsPageProductsAndProfileGoAndTheirPublicationsEnd() {
        String ann = newSponsor(unique("s-q69-ann"));
        String bob = newSponsor(unique("s-q69-bob"));
        String token = sponsorToken(ann);
        run(SponsorPageProcesses.SAVE, token, body("handle", "q69-" + UUID.randomUUID().toString().substring(0, 8),
            "language", "en", "headline", "Ann's bakery"));
        run(SponsorProductProcesses.SAVE, token, body("name", "Rye", "price", "100", "currency", "JPY",
            "shopUrl", "https://shop.example/rye", "listed", true));
        String publication = livePublication(ann, "Ann's quiz");
        String bobsPublication = livePublication(bob, "Bob's quiz");

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", ann, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(query("SELECT 1 FROM qb_sponsor_page WHERE user_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_sponsor_product WHERE user_id = ?", ann)).isEmpty();
        // The publication is kept as a record, ended because the account was deleted; another sponsor's runs on.
        assertThat(latest(publication)).containsEntry("status", "ENDED")
            .containsEntry("admin_reason", PublicationAdmin.ACCOUNT_DELETED);
        assertThat(latest(bobsPublication)).containsEntry("status", "LIVE");
        // The onboarding is tombstoned, and the purge overwrote who they are in every version.
        assertThat(query("SELECT is_deleted FROM qb_sponsor_version WHERE user_id = ? ORDER BY version_no DESC"
            + " LIMIT 1", ann).getFirst()).containsEntry("is_deleted", true);
        assertThat(query("SELECT DISTINCT contact_name, company FROM qb_sponsor_version WHERE user_id = ?", ann))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("contact_name", "***")
                .containsEntry("company", "***"));
        assertThat(query("SELECT DISTINCT display_name FROM qb_profile_version WHERE user_id = ?", ann))
            .allSatisfy(row -> assertThat(row).containsEntry("display_name", "***"));
        // Another sponsor is untouched.
        assertThat(query("SELECT DISTINCT company FROM qb_sponsor_version WHERE user_id = ?", bob))
            .noneSatisfy(row -> assertThat(row).containsEntry("company", "***"));
    }

    private static Map<String, Object> latest(String publicationId) {
        return query("SELECT status, admin_reason FROM qb_publication_version WHERE publication_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", publicationId).getFirst();
    }
}

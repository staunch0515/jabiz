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
 * Q72 (docs/quizbuks/plans/Q72-sponsor-erasure.md; platform decision D62): erasing a sponsor's account removes their
 * page, products, asset library (F-C1) and profile, tombstones their onboarding, and afterwards ends their running
 * publications (kept as records) with the reason that the account was deleted.
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
        // F-C1: an asset of the library and where it was copied to; Bob's stay.
        for (String owner : java.util.List.of(ann, bob)) {
            String asset = (String) run(com.jabiz.quizbuks.asset.AssetProcesses.SAVE, sponsorToken(owner), body("kind",
                "ARTICLE", "name", "Notes of " + owner, "language", "en", "body", "Private notes")).get("assetId");
            execute("INSERT INTO qb_asset_use (use_id, asset_id, sponsor_id, target_kind, target_id, asset_revision, "
                + "used_at, version) VALUES (?, ?, ?, 'QUIZ_INTRO', ?, 1, now(), 1)", UUID.randomUUID().toString(),
                asset, owner, UUID.randomUUID().toString());
        }
        // F-C2: a day of usage snapshots; Bob's stay.
        for (String owner : java.util.List.of(ann, bob)) {
            execute("INSERT INTO qb_usage_day (usage_day_id, sponsor_id, snapshot_day, meter, value, recorded_at, "
                + "version) VALUES (?, ?, DATE '2026-03-01', 'storage.bytes', 1234, now(), 1)",
                UUID.randomUUID().toString(), owner);
        }
        String publication = livePublication(ann, "Ann's quiz");
        String bobsPublication = livePublication(bob, "Bob's quiz");
        // A day of counts of their page (platform 17z-6: owned by the account, so they go with it); Bob's stay.
        for (String owner : java.util.List.of(ann, bob)) {
            execute("INSERT INTO sys_public_counter_day (row_id, counter_name, counter_key, day, hits, owner_key)"
                + " VALUES (gen_random_uuid(), 'qb.sponsor-page-view', 'h-" + UUID.randomUUID() + "', DATE '2026-03-01',"
                + " 3, '" + owner + "')");
        }

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", ann, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(query("SELECT 1 FROM qb_sponsor_page WHERE user_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_sponsor_product WHERE user_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_asset WHERE sponsor_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_asset_use WHERE sponsor_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_asset WHERE sponsor_id = ?", bob)).hasSize(1);
        assertThat(query("SELECT 1 FROM qb_asset_use WHERE sponsor_id = ?", bob)).hasSize(1);
        assertThat(query("SELECT 1 FROM qb_usage_day WHERE sponsor_id = ?", ann)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_usage_day WHERE sponsor_id = ?", bob)).hasSize(1);
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
        assertThat(query("SELECT owner_key FROM sys_public_counter_day WHERE owner_key IN (?, ?)", ann, bob))
            .extracting(row -> row.get("owner_key")).containsExactly(bob);
        // Another sponsor is untouched.
        assertThat(query("SELECT DISTINCT company FROM qb_sponsor_version WHERE user_id = ?", bob))
            .noneSatisfy(row -> assertThat(row).containsEntry("company", "***"));
    }

    private static Map<String, Object> latest(String publicationId) {
        return query("SELECT status, admin_reason FROM qb_publication_version WHERE publication_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", publicationId).getFirst();
    }
}

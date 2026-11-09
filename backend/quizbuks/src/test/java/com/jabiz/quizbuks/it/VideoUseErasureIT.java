package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.videouse.VideoUseProcesses;
import com.jabiz.quizbuks.videouse.VideoUseRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S14 (docs/quizbuks/plans/impl/F-S14.md): erasing a sponsor's account keeps the ledger of shared videos' uses (a
 * record between two sponsors) and overwrites only the free text the sponsor wrote (platform decision D62): the user's
 * note, the provider's reason. A class of its own: the purge rewrites the append-only rows that {@link VideoUseIT}
 * checks are never updated.
 */
class VideoUseErasureIT extends VideoUseItSupport {

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
    void anErasedSponsorsUseStaysWithoutItsFreeText() {
        World w = world("erased");
        String user = sponsorToken(w.user());
        String id = (String) run(VideoUseProcesses.SAVE, user, entry(w.publicationId(), VideoUseRules.FIXED, 10))
            .get("videoUseId");
        run(VideoUseProcesses.SUBMIT, user, body("videoUseId", id, "baseRevision", 1));
        run(VideoUseProcesses.REJECT, sponsorToken(w.provider()), body("videoUseId", id, "reason",
            "Call me at 090-0000-0000", "baseRevision", 2));

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", w.user(), "requestedBy", "it-support"));
        deliverEvents();
        // The use stays (a record between two sponsors), every version; the user's note is gone.
        assertThat(versions(id)).isGreaterThanOrEqualTo(3);
        assertThat(query("SELECT DISTINCT note FROM qb_video_use_version WHERE video_use_id = ?::uuid", id))
            .allSatisfy(row -> assertThat(row.get("note")).isIn("***", null));

        runAsSystem("ACCOUNT_ERASE", Map.of("userId", w.provider(), "requestedBy", "it-support"));
        deliverEvents();
        assertThat(query("SELECT DISTINCT reject_reason FROM qb_video_use_version WHERE video_use_id = ?::uuid", id))
            .allSatisfy(row -> assertThat(row.get("reject_reason")).isIn("***", null));
        assertThat(query("SELECT DISTINCT sponsor_id, provider_id FROM qb_video_use_version WHERE video_use_id = "
            + "?::uuid", id)).singleElement().satisfies(row -> assertThat(row).containsEntry("sponsor_id", w.user())
                .containsEntry("provider_id", w.provider()));
    }
}

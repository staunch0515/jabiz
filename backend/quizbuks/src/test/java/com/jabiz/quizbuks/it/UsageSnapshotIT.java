package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.usage.QbUsage;
import com.jabiz.quizbuks.usage.UsageMeters;
import com.jabiz.quizbuks.usage.UsageSnapshot;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-C2 (docs/quizbuks/first-launch-spec.md, docs/quizbuks/plans/impl/F-C2.md): the daily usage snapshot. A file is
 * counted once, for its uploader, however many places use it; a run again of a day overwrites it; above the free amount
 * is only noticed (once per crossing) and uploads go on; the AI usage is the month's of the sponsor's jobs; rows past
 * the retention go; an operator reads its own sponsors' only.
 */
class UsageSnapshotIT extends PlayItSupport {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Autowired
    @Qualifier("qbUsageSnapshotJob")
    JobDefinition<UsageSnapshot.ItemInput> snapshotJob;

    /** The job's scheduled time on a day (of the deployment's zone, Tokyo here), at 03:17 and some minutes. */
    private static Instant at(LocalDate day, int minutesLater) {
        return ZonedDateTime.of(day, LocalTime.of(3, 17).plusMinutes(minutesLater), TOKYO).toInstant();
    }

    private JobRunner.JobRun snapshot(Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobRunner.execute(snapshotJob, at);
        assertThat(run.outcome()).as("snapshot at " + at + ": " + run).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        return run;
    }

    /** A sponsor's values of a day, by meter (from the table). */
    private static Map<String, Long> usage(String sponsorId, LocalDate day) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (Map<String, Object> row : query("SELECT meter, value FROM qb_usage_day WHERE sponsor_id = ? "
            + "AND snapshot_day = ? ORDER BY meter", sponsorId, day)) {
            values.put((String) row.get("meter"), ((BigDecimal) row.get("value")).longValue());
        }
        return values;
    }

    private String uploadImage(String token) {
        return (String) upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), "picture.jpg", "image/jpeg", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private static long size(String fileId) {
        return ((Number) query("SELECT size_bytes FROM sys_file WHERE file_id = ?::uuid", fileId).getFirst()
            .get("size_bytes")).longValue();
    }

    private String asset(String token, Object... fields) {
        Map<String, Object> asset = body("language", "en");
        asset.putAll(body(fields));
        return (String) run(AssetProcesses.SAVE, token, asset).get("assetId");
    }

    /** Sets the controlled free amount (four eyes). */
    private void freeBytes(long bytes) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.USAGE_FREE_BYTES, "value", String.valueOf(bytes)), "reason",
            "F-C2 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    @Test
    void aFileUsedInSeveralPlacesCountsOnceForItsUploader() {
        String ann = newSponsor(unique("s-fc2-ann"));
        String bob = newSponsor(unique("s-fc2-bob"));
        String annToken = sponsorToken(ann);
        String picture = uploadImage(annToken);
        // The same file in an asset, a quiz's cover and an image material.
        asset(annToken, "kind", "IMAGE", "name", "Photo", "image", picture);
        String quizId = newQuiz(annToken, "Coffee");
        run("QB_QUIZ_SAVE", annToken, body("quizId", quizId, "title", "Coffee", "cover", picture));
        run("QB_MATERIAL_SAVE", annToken, body("quizId", quizId, "kind", "IMAGES", "title", "Shop",
            "images", List.of(body("image", picture))));
        // A link to an outside video: an asset and a material, never bytes.
        asset(annToken, "kind", "VIDEO_LINK", "name", "Clip", "url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "durationSec", 90);
        run("QB_MATERIAL_SAVE", annToken, body("quizId", quizId, "kind", "VIDEO_LINK", "title", "Watch",
            "url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "durationSec", 90));
        // A versioned quiz of their own.
        version(annToken, "Capitals");
        // Bob's file is his.
        String bobs = uploadImage(sponsorToken(bob));

        LocalDate day = LocalDate.of(2026, 2, 2);
        snapshot(at(day, 0));

        Map<String, Long> annUsage = usage(ann, day);
        assertThat(annUsage.keySet()).containsExactlyInAnyOrderElementsOf(UsageMeters.codes());
        assertThat(annUsage).containsEntry(UsageMeters.STORAGE_BYTES, size(picture))
            .containsEntry(UsageMeters.STORAGE_FILES, 1L)
            .containsEntry(UsageMeters.ASSETS_COUNT, 2L)
            // "Photo" and "Clip": the names are the assets' only texts.
            .containsEntry(UsageMeters.ASSETS_TEXT_BYTES, 9L)
            .containsEntry(UsageMeters.CONTENT_QUIZZES, 2L)
            .containsEntry(UsageMeters.CONTENT_VERSIONS, 1L)
            .containsEntry(UsageMeters.CONTENT_MATERIALS, 2L)
            .containsEntry(UsageMeters.AI_TOKENS, 0L);
        assertThat(usage(bob, day)).containsEntry(UsageMeters.STORAGE_BYTES, size(bobs))
            .containsEntry(UsageMeters.STORAGE_FILES, 1L).containsEntry(UsageMeters.ASSETS_COUNT, 0L);

        // The sponsor's card: the latest snapshot and the free amount (500 MB declared): not above.
        Map<String, Object> card = items(QbUsage.SPONSOR_TEMPLATE, annToken, Map.of()).getFirst();
        assertThat(card).containsEntry("day", day.toString()).containsEntry("storageFiles", 1)
            .containsEntry("assetsCount", 2).containsEntry("freeBytes", 524_288_000).containsEntry("overFree", false);
        assertThat(((Number) card.get("storageBytes")).longValue()).isEqualTo(size(picture));
        // The size of each asset: the file's for the photo, none for the link; neither used yet.
        assertThat(items(QbUsage.ASSET_STATS_TEMPLATE, annToken, Map.of())).hasSize(2)
            .anySatisfy(row -> assertThat(((Number) row.get("fileBytes")).longValue()).isEqualTo(size(picture)))
            .anySatisfy(row -> assertThat(row).containsEntry("fileBytes", 0).containsEntry("textBytes", 4))
            .allSatisfy(row -> assertThat(row).containsEntry("uses", 0));
        // Somebody else's card shows nothing of Ann's.
        assertThat(items(QbUsage.ASSET_STATS_TEMPLATE, sponsorToken(bob), Map.of())).isEmpty();
    }

    @Test
    void aRunAgainOfTheSameDayOverwritesItsRows() {
        String carl = newSponsor(unique("s-fc2-carl"));
        String token = sponsorToken(carl);
        String first = uploadImage(token);
        LocalDate day = LocalDate.of(2026, 2, 3);
        snapshot(at(day, 0));
        List<Map<String, Object>> rows = query("SELECT usage_day_id, meter, value, recorded_at FROM qb_usage_day "
            + "WHERE sponsor_id = ? AND snapshot_day = ? ORDER BY meter", carl, day);
        assertThat(rows).hasSize(UsageMeters.ALL.size());

        String second = uploadImage(token);
        clock.advance(java.time.Duration.ofMinutes(5));
        // Later the same day (by hand, say): the same rows, the new values.
        snapshot(at(day, 30));
        List<Map<String, Object>> again = query("SELECT usage_day_id, meter, value, recorded_at FROM qb_usage_day "
            + "WHERE sponsor_id = ? AND snapshot_day = ? ORDER BY meter", carl, day);
        assertThat(again).extracting(row -> row.get("usage_day_id"))
            .containsExactlyElementsOf(rows.stream().map(row -> row.get("usage_day_id")).toList());
        assertThat(usage(carl, day)).containsEntry(UsageMeters.STORAGE_BYTES, size(first) + size(second))
            .containsEntry(UsageMeters.STORAGE_FILES, 2L);
        // Only the changed rows were written again.
        assertThat(again).filteredOn(row -> UsageMeters.STORAGE_FILES.equals(row.get("meter"))).singleElement()
            .satisfies(row -> assertThat(row.get("recorded_at")).isNotEqualTo(rows.stream()
                .filter(r -> UsageMeters.STORAGE_FILES.equals(r.get("meter"))).findFirst().orElseThrow()
                .get("recorded_at")));

        // The same scheduled time once more is replayed by the platform: nothing changes, nothing doubles.
        JobRunner.JobRun replay = snapshot(at(day, 30));
        assertThat(replay.items().succeeded()).isZero();
        assertThat(query("SELECT 1 FROM qb_usage_day WHERE sponsor_id = ?", carl)).hasSize(UsageMeters.ALL.size());
    }

    @Test
    void aboveTheFreeAmountIsOnlyNoticedOncePerCrossingAndUploadsGoOn() {
        String dana = newSponsor(unique("s-fc2-dana"));
        String token = sponsorToken(dana);
        LocalDate day1 = LocalDate.of(2026, 2, 10);
        freeBytes(1_000);
        try {
            snapshot(at(day1, 0));
            assertThat(usage(dana, day1)).containsEntry(UsageMeters.STORAGE_BYTES, 0L);
            String picture = uploadImage(token);
            assertThat(size(picture)).isGreaterThan(1_000);

            LocalDate day2 = day1.plusDays(1);
            snapshot(at(day2, 0));
            deliverEvents();
            List<Map<String, Object>> messages = query("SELECT kind, action FROM qb_message WHERE recipient_id = ?",
                dana);
            assertThat(messages).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("kind", "USAGE_OVER_FREE").containsEntry("action", "ASSETS"));
            // Shown on the card; nothing refused: another upload goes in.
            Map<String, Object> card = items(QbUsage.SPONSOR_TEMPLATE, token, Map.of()).getFirst();
            assertThat(card).containsEntry("overFree", true).containsEntry("freeBytes", 1_000)
                .containsEntry("day", day2.toString());
            String more = uploadImage(token);
            assertThat(more).isNotNull();

            // The day again: the same crossing, no second message.
            clock.advance(java.time.Duration.ofMinutes(5));
            snapshot(at(day2, 10));
            deliverEvents();
            // Still above the next day: the same crossing.
            snapshot(at(day2.plusDays(1), 0));
            deliverEvents();
            assertThat(query("SELECT 1 FROM qb_message WHERE recipient_id = ?", dana)).hasSize(1);
        } finally {
            freeBytes(524_288_000);
        }
    }

    @Test
    void theAiUsageIsTheMonthsOfTheSponsorsJobsOnly() {
        String erin = newSponsor(unique("s-fc2-erin"));
        for (Object[] row : new Object[][] {{erin, "2026-02", 1_000, 234}, {erin, "2026-01", 50_000, 50_000},
            {"*", "2026-02", 9_000_000, 9_000_000}}) {
            execute("INSERT INTO qb_ai_usage (usage_id, sponsor_id, month, jobs, failed_jobs, input_tokens, "
                + "output_tokens, version) VALUES (?, ?, ?, 1, 0, ?, ?, 1) ON CONFLICT DO NOTHING",
                UUID.randomUUID().toString(), row[0], row[1], row[2], row[3]);
        }
        LocalDate day = LocalDate.of(2026, 2, 20);
        snapshot(at(day, 0));
        // February's tokens in and out; not January's, not everybody's. The AI help keeps its own usage elsewhere.
        assertThat(usage(erin, day)).containsEntry(UsageMeters.AI_TOKENS, 1_234L);
    }

    @Test
    void rowsPastTheRetentionAreDeleted() {
        String fred = newSponsor(unique("s-fc2-fred"));
        LocalDate day = LocalDate.of(2026, 3, 1);
        for (LocalDate old : List.of(day.minusDays(3), day.minusDays(2), day.minusDays(1))) {
            execute("INSERT INTO qb_usage_day (usage_day_id, sponsor_id, snapshot_day, meter, value, recorded_at, "
                + "version) VALUES (?, ?, ?, 'storage.files', 0, now(), 1)", UUID.randomUUID().toString(), fred, old);
        }
        run("PARAM_SET", admin(), body("key", QbParams.USAGE_RETENTION_DAYS, "value", "2"));
        try {
            snapshot(at(day, 0));
            // Two days kept: the day and the one before.
            assertThat(query("SELECT DISTINCT snapshot_day FROM qb_usage_day WHERE sponsor_id = ? ORDER BY 1", fred))
                .extracting(row -> row.get("snapshot_day").toString())
                .containsExactly(day.minusDays(1).toString(), day.toString());
        } finally {
            run("PARAM_SET", admin(), body("key", QbParams.USAGE_RETENTION_DAYS, "value", "800"));
        }
    }

    @Test
    void anOperatorReadsItsOwnSponsorsUsageOnlyAndNobodyRunsTheItemByHand() {
        String north = newOperator(unique("North"));
        String northSession = operatorSession(member(north, unique("fc2-n")), north);
        String ann = newSponsor(unique("s-fc2-op-ann"));
        String direct = newSponsor(unique("s-fc2-op-direct"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", ann, "operatorId", north, "reason", "F-C2"));
        LocalDate day = LocalDate.of(2026, 3, 5);
        snapshot(at(day, 0));

        Map<String, Object> request = Map.of("params", Map.of("from", "2026-03-01", "to", "2026-03-31"),
            "limit", 500);
        List<Map<String, Object>> operatorRows = items(QbUsage.ADMIN_TEMPLATE, northSession, request);
        assertThat(operatorRows).extracting(row -> row.get("sponsorId")).contains(ann).doesNotContain(direct);
        List<Map<String, Object>> hqRows = items(QbUsage.ADMIN_TEMPLATE, hq(), request);
        assertThat(hqRows).extracting(row -> row.get("sponsorId")).contains(ann, direct);
        assertThat(hqRows).filteredOn(row -> ann.equals(row.get("sponsorId"))).hasSize(UsageMeters.ALL.size())
            .allSatisfy(row -> assertThat(row).containsEntry("month", "2026-03").containsEntry("days", 1));
        // The default dataset likewise; the sponsor reads only their own (me:), not the administrators'.
        assertThat(rows(QbUsage.DEFAULT_DATASET, northSession)).extracting(row -> row.get("sponsorId"))
            .doesNotContain(direct);
        queryDataset(QbUsage.DEFAULT_DATASET, sponsorToken(ann)).expectStatus().isForbidden();
        assertThat(rows(QbUsage.MY_DATASET, sponsorToken(ann))).extracting(row -> row.get("sponsorId"))
            .containsOnly(ann);
        // The item is the job's: nobody runs it through the API.
        refusedAsInternal(UsageSnapshot.ITEM, sponsorToken(ann), body("sponsorId", ann, "day", day.toString(),
            "at", at(day, 0).toString()));
        assertThat(QbRoles.all()).noneMatch(role -> role.permissions().contains(QbPermissions.USAGE_SYSTEM));
    }

    @Test
    void theMonthsHaveTheirAveragePeakAndLastValue() {
        String gina = newSponsor(unique("s-fc2-gina"));
        long[] values = {100, 300, 200};
        for (int i = 0; i < values.length; i++) {
            execute("INSERT INTO qb_usage_day (usage_day_id, sponsor_id, snapshot_day, meter, value, recorded_at, "
                + "version) VALUES (?, ?, ?, 'storage.bytes', ?, now(), 1)", UUID.randomUUID().toString(), gina,
                LocalDate.of(2026, 4, 1 + i), values[i]);
        }
        List<Map<String, Object>> rows = items(QbUsage.ADMIN_TEMPLATE, hq(), Map.of("params", Map.of("from",
            "2026-04-01", "to", "2026-04-30", "sponsorId", gina, "meter", "storage.bytes"), "limit", 10));
        assertThat(rows).singleElement().satisfies(row -> assertThat(row).containsEntry("days", 3)
            .containsEntry("average", 200).containsEntry("peak", 300).containsEntry("latest", 200));
    }

    // ---- operators (as AssetIT) -------------------------------------------------------------------------------------

    private String hq() {
        return TestTokens.bearer(tokens, "fc2-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ,
            QbPermissions.ADMIN_PUBLICATION_READ);
    }

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
}

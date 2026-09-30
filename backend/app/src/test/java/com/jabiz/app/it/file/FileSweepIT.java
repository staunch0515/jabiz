package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The sweep (docs/design/14-files.md section 6; ROADMAP 13b acceptance 3): old files nothing refers to lose their row
 * and content; objects without a row go once they are old enough; referenced files, files a scheduled version will
 * use and recent uploads stay.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "jabiz.files.sweep-batch-size=2")
class FileSweepIT extends FileItSupport {

    @Autowired
    JobRunner jobs;

    @Autowired
    @Qualifier("fileSweepJob")
    JobDefinition<FileProcesses.PurgeInput> sweep;

    /** Each test runs the sweep once; the cluster lock of the previous run must not stand in its way. */
    @BeforeEach
    void releaseTheLock() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
    }

    /**
     * The storage half of a sweep runs after its commit, on its own, and removes stray objects older than its own
     * operation time. A test whose clock ran ahead must not leave one running into the next test, where it would take
     * that test's fresh strays for old ones: wait until every sweep's after-commit step has succeeded (a failed attempt
     * is recorded too, and is retried later).
     */
    @AfterEach
    void awaitTheStorageSweeps() {
        await().atMost(Duration.ofSeconds(30)).until(() -> query("SELECT 1 FROM op_process p "
            + "WHERE p.process_name = 'FILE_PURGE_ORPHANS' AND NOT EXISTS (SELECT 1 FROM op_process_after_commit a "
            + "WHERE a.process_seq_id = p.process_seq_id AND a.succeeded)").isEmpty());
    }

    /** A UUIDv7 created at {@code time}, as the platform's generator would. */
    private static UUID uuidV7At(Instant time) {
        UUID random = UUID.randomUUID();
        long high = (time.toEpochMilli() << 16) | 0x7000L | (random.getMostSignificantBits() & 0x0FFFL);
        long low = (random.getLeastSignificantBits() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(high, low);
    }

    /** An object stored without a row, as a crash between storing and recording would leave. */
    private static String strayObject(Instant createdAt) throws IOException {
        String key = FileKeys.key(uuidV7At(createdAt), FileKeys.ORIGINAL);
        Path path = filesRoot().resolve(key);
        Files.createDirectories(path.getParent());
        Files.write(path, FileSamples.pdf());
        return key;
    }

    private static boolean hasRow(String fileId) {
        return !query("SELECT 1 FROM sys_file WHERE file_id = ?", UUID.fromString(fileId)).isEmpty();
    }

    private static boolean hasObjects(String fileId) {
        String prefix = FileKeys.prefix(UUID.fromString(fileId)) + "/";
        return storedKeys().stream().anyMatch(key -> key.startsWith(prefix));
    }

    @Test
    void sweepsWhatNothingUsesAndNothingElse() throws IOException {
        String orphan = (String) uploaded(CommerceFiles.IMAGE, FileSamples.jpeg(400, 300), "o.jpg", "image/jpeg",
            photographer()).get("fileId");
        String used = uploadContract();
        createSupplier(used);
        String scheduled = uploadContract();
        String supplierId = createSupplier(null);
        commitSuppliers(List.of(Map.of("action", "UPDATE", "id", supplierId, "version", 1,
            "attributes", Map.of("contractFileId", scheduled), "effectiveTime", START.plus(Duration.ofDays(30)))))
            .expectStatus().isOk();
        String oldStray = strayObject(START.minus(Duration.ofHours(3)));

        clock.advance(Duration.ofHours(25));
        String recent = uploadContract();
        String freshStray = strayObject(clock.instant().minus(Duration.ofMinutes(10)));

        assertThat(jobs.run(sweep, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);

        assertThat(hasRow(orphan)).isFalse();
        await().atMost(Duration.ofSeconds(10)).until(() -> !hasObjects(orphan));
        await().atMost(Duration.ofSeconds(10)).until(() -> !storedKeys().contains(oldStray));
        assertThat(storedKeys()).contains(freshStray);
        for (String kept : List.of(used, scheduled, recent)) {
            assertThat(hasRow(kept)).as("row of %s", kept).isTrue();
            assertThat(hasObjects(kept)).as("objects of %s", kept).isTrue();
        }
        List<Map<String, Object>> runs = query("SELECT process_name, actor_id FROM op_process "
            + "WHERE process_name = 'FILE_PURGE_ORPHANS'");
        assertThat(runs).isNotEmpty().extracting(row -> row.get("actor_id")).containsOnly("system");
    }

    @Test
    void filesStillInUseDoNotHideNewerOrphans() {
        // More old files in use than one batch (2 here), all older than the orphan.
        for (int i = 0; i < 3; i++) {
            createSupplier(uploadContract());
        }
        clock.advance(Duration.ofMinutes(5));
        String orphan = uploadContract();

        clock.advance(Duration.ofHours(26));
        assertThat(jobs.run(sweep, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);

        assertThat(hasRow(orphan)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void orphansUnderALegalHoldAreKeptAndReported() {
        String held = uploadContract();
        String free = uploadContract();
        String keeper = bearer("it-counsel", "legal.hold.write");
        Map<String, Object> placed = post("/api/processes/LEGAL_HOLD_PLACE/latest", keeper, Map.of("name", "litigation",
            "reason", "court order", "entityType", "SysFile", "ids", List.of(held)))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String holdId = (String) ((Map<String, Object>) placed.get("output")).get("holdId");

        // Minutes past the other tests' runs: a job runs once per scheduled time.
        clock.advance(Duration.ofHours(25).plusMinutes(7));
        assertThat(jobs.run(sweep, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(hasRow(held)).isTrue();
        assertThat(hasObjects(held)).isTrue();
        assertThat(hasRow(free)).isFalse();
        // The sweep says what it kept.
        assertThat(query("SELECT r.output::text AS output FROM op_process_result r JOIN op_process p"
            + " ON p.process_seq_id = r.process_seq_id WHERE p.process_name = 'FILE_PURGE_ORPHANS'"
            + " ORDER BY p.process_seq_id DESC LIMIT 1").getFirst().get("output").toString()).contains(held);

        // A single deletion is refused outright.
        post("/api/processes/FILE_DELETE/latest", bearer("it-deleter", "file.delete"),
            Map.of("fileId", held)).expectStatus().isEqualTo(422);

        // Tokens are short-lived: a new one after the day that passed.
        post("/api/processes/LEGAL_HOLD_RELEASE/latest", bearer("it-counsel", "legal.hold.write"),
            Map.of("holdId", holdId, "reason", "settled")).expectStatus().isOk();
        clock.advance(Duration.ofHours(1));
        releaseTheLock();
        assertThat(jobs.run(sweep, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(hasRow(held)).isFalse();
    }
}

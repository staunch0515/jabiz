package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

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
class FileSweepIT extends FileItSupport {

    @Autowired
    JobRunner jobs;

    @Autowired
    @Qualifier("fileSweepJob")
    JobDefinition<FileProcesses.PurgeInput> sweep;

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
        assertThat(runs).extracting(row -> row.get("actor_id")).containsExactly("system");
    }
}

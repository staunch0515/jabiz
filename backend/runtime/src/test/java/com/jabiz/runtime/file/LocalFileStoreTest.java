package com.jabiz.runtime.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStoreTest {

    @TempDir
    Path root;

    private LocalFileStore store(String directory) {
        return new LocalFileStore(new FileProperties(DataSize.ofMegabytes(1), null, null, null, null, null, null,
            new FileProperties.Local(directory)));
    }

    @Test
    void writesReadsListsAndDeletes() {
        LocalFileStore store = store(root.toString());
        String key = "2026/01/abc/original";
        store.write(key, Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(
            "hello world".getBytes(StandardCharsets.UTF_8)))).block(Duration.ofSeconds(5));
        assertThat(store.size(key).block()).isEqualTo(11L);
        assertThat(store.size("2026/01/abc/none").blockOptional()).isEmpty();
        String part = DataBufferUtils.join(store.read(key, 6, 5)).map(buffer -> {
            String text = buffer.toString(StandardCharsets.UTF_8);
            DataBufferUtils.release(buffer);
            return text;
        }).block();
        assertThat(part).isEqualTo("world");
        store.newUploadFile().block();
        assertThat(store.list().collectList().block()).containsExactly(key);

        store.deleteAll("2026/01/abc").block();
        assertThat(store.list().collectList().block()).isEmpty();
        assertThat(root.resolve("2026/01/abc")).doesNotExist();
        // The month stays: an upload may just have created it.
        assertThat(root.resolve("2026/01")).isDirectory();
    }

    @Test
    void keysNeverLeaveTheRoot() {
        LocalFileStore store = store(root.toString());
        for (String key : new String[] {"../x", "a/../../x", "/etc/passwd", "a//b", ".uploads/x", "", "a/b\\c"}) {
            assertThatThrownBy(() -> store.resolve(key)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void removesOnlyStaleUploadLeftovers() throws Exception {
        LocalFileStore store = store(root.toString());
        Path fresh = store.newUploadFile().block();
        Path stale = store.newUploadFile().block();
        Path work = Files.createDirectories(stale.resolveSibling(stale.getFileName() + ".work"));
        Files.writeString(work.resolve("original"), "x");
        Path part = Files.createDirectories(root.resolve("2026/01/abc")).resolve(".original.123.part");
        Files.writeString(part, "x");
        Path object = Files.writeString(root.resolve("2026/01/abc/original"), "x");
        for (Path old : new Path[] {stale, work, part, object}) {
            Files.setLastModifiedTime(old, FileTime.fromMillis(0));
        }
        assertThat(store.deleteStaleParts(Duration.ofHours(1)).block()).isEqualTo(3);
        assertThat(fresh).exists();
        assertThat(stale).doesNotExist();
        assertThat(work).doesNotExist();
        assertThat(part).doesNotExist();
        assertThat(object).exists();
    }

    @Test
    void anUnconfiguredStoreStoresNothing() {
        LocalFileStore store = store(null);
        assertThat(store.configured()).isFalse();
        assertThat(store.list().collectList().block()).isEmpty();
        assertThat(store.deleteStaleParts(Duration.ofHours(1)).block()).isZero();
        assertThatThrownBy(store::root).hasMessageContaining("jabiz.files.local.root");
    }

    /**
     * Two sweeps can list the store while one of them deletes: a directory removed after its parent was read must not
     * end the other's listing with an error (the error would make its storage step run again later, at an older
     * operation's cut-off, over newer strays).
     */
    @Test
    void listingSurvivesObjectsDeletedMeanwhile() {
        LocalFileStore store = store(root.toString());
        for (String id : new String[] {"a", "b", "c", "d"}) {
            store.write("2026/01/" + id + "/original", Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(
                "x".getBytes(StandardCharsets.UTF_8)))).block(Duration.ofSeconds(5));
        }
        java.util.List<String> seen = new java.util.ArrayList<>();
        java.util.List<String> keys = store.list().doOnNext(key -> {
            if (seen.isEmpty()) {
                // Everything else goes while the walk is under way.
                for (String id : new String[] {"a", "b", "c", "d"}) {
                    if (!key.startsWith("2026/01/" + id + "/")) {
                        store.deleteAll("2026/01/" + id).block();
                    }
                }
            }
            seen.add(key);
        }).collectList().block(Duration.ofSeconds(5));
        assertThat(keys).hasSize(1);
    }
}

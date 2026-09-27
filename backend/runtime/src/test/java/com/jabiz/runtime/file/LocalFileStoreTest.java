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
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStoreTest {

    @TempDir
    Path root;

    private LocalFileStore store(String directory) {
        return new LocalFileStore(new FileProperties(DataSize.ofMegabytes(1), null, null, null, null, null,
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
        // Emptied month and year directories go too; the root stays.
        assertThat(root.resolve("2026")).doesNotExist();
        assertThat(root).exists();
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
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(0));
        assertThat(store.deleteStaleParts(Duration.ofHours(1)).block()).isEqualTo(1);
        assertThat(fresh).exists();
        assertThat(stale).doesNotExist();
    }

    @Test
    void anUnconfiguredStoreStoresNothing() {
        LocalFileStore store = store(null);
        assertThat(store.configured()).isFalse();
        assertThat(store.list().collectList().block()).isEmpty();
        assertThat(store.deleteStaleParts(Duration.ofHours(1)).block()).isZero();
        assertThatThrownBy(store::root).hasMessageContaining("jabiz.files.local.root");
    }
}

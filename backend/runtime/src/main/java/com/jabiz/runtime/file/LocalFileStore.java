package com.jabiz.runtime.file;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link FileStore} in a local directory ({@code jabiz.files.local.root}, docs/design/14-files.md section 6). Contents
 * are read and written through {@link AsynchronousFileChannel}; directory work, moves and deletes run on
 * {@code boundedElastic}. Objects are written to a hidden part file and moved into place, so a reader never sees half
 * an object. Directories whose name starts with a dot (the upload area, part files) are not objects.
 */
@Component
public class LocalFileStore implements FileStore {

    /** Where uploads are received before they become objects; inside the root so that moves stay on one volume. */
    public static final String UPLOAD_AREA = ".uploads";

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private static final int BUFFER_SIZE = 64 * 1024;

    private final FileProperties.Local settings;

    public LocalFileStore(FileProperties properties) {
        this.settings = properties.local();
    }

    public boolean configured() {
        return settings.configured();
    }

    /** The root directory; fails when none is configured. */
    public Path root() {
        if (!settings.configured()) {
            throw new IllegalStateException("No file storage directory is configured (jabiz.files.local.root)");
        }
        return settings.rootPath();
    }

    /** A new, empty file in the upload area; the caller deletes it. */
    public Mono<Path> newUploadFile() {
        return Mono.fromCallable(() -> {
            Path area = root().resolve(UPLOAD_AREA);
            Files.createDirectories(area);
            return Files.createFile(area.resolve(UUID.randomUUID() + ".part"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Deletes a file of the upload area, or a directory of them; missing is fine. */
    public Mono<Void> discard(Path path) {
        return Mono.<Void>fromRunnable(() -> deleteRecursively(path)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> write(String key, Flux<DataBuffer> content) {
        return Mono.defer(() -> {
            Path target = resolve(key);
            Path part = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".part");
            return Mono.fromCallable(() -> Files.createDirectories(target.getParent()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(DataBufferUtils.write(content, part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
                .then(Mono.fromCallable(() -> Files.move(part, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(error -> Mono.<Void>fromRunnable(() -> deleteRecursively(part))
                    .subscribeOn(Schedulers.boundedElastic()).then(Mono.error(error)))
                .then();
        });
    }

    @Override
    public Mono<Long> size(String key) {
        return Mono.fromCallable(() -> {
            Path path = resolve(key);
            return Files.isRegularFile(path) ? Files.size(path) : null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<DataBuffer> read(String key, long offset, long length) {
        return Flux.defer(() -> {
            Path path = resolve(key);
            Flux<DataBuffer> content = DataBufferUtils.readAsynchronousFileChannel(
                () -> AsynchronousFileChannel.open(path, StandardOpenOption.READ), offset,
                DefaultDataBufferFactory.sharedInstance, BUFFER_SIZE);
            return DataBufferUtils.takeUntilByteCount(content, length);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Month and year directories stay even when empty: removing them would race with an upload that has just created
     * them for its own file.
     */
    @Override
    public Mono<Void> deleteAll(String prefix) {
        return Mono.<Void>fromRunnable(() -> deleteRecursively(resolve(prefix)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Streams the keys while walking the tree depth first, so the keys of one file come one after another and nothing
     * is collected in memory. The walk (and every later request for more keys) runs on {@code boundedElastic}.
     */
    @Override
    public Flux<String> list() {
        if (!configured()) {
            return Flux.empty();
        }
        return Flux.defer(() -> {
            Path root = root();
            if (!Files.isDirectory(root)) {
                return Flux.<String>empty();
            }
            return Flux.using(() -> Files.walk(root), walk -> Flux.fromStream(walk
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(relative -> !hidden(relative))
                    .map(relative -> relative.toString().replace('\\', '/'))),
                Stream::close);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Deletes what crashed uploads left behind and is older than {@code maxAge}: entries of the upload area (received
     * files and their work directories) and part files next to objects. Ages come from the file system's clock, like
     * the cluster lock's: these are infrastructure leftovers, not business data, and their only time is the file
     * system's (docs/design/14-files.md section 6).
     */
    public Mono<Integer> deleteStaleParts(Duration maxAge) {
        if (!configured()) {
            return Mono.just(0);
        }
        return Mono.fromCallable(() -> {
            Path root = root();
            if (!Files.isDirectory(root)) {
                return 0;
            }
            long limit = System.currentTimeMillis() - maxAge.toMillis();
            List<Path> stale = new ArrayList<>();
            Path area = root.resolve(UPLOAD_AREA);
            if (Files.isDirectory(area)) {
                try (Stream<Path> entries = Files.list(area)) {
                    entries.filter(path -> modifiedBefore(path, limit)).forEach(stale::add);
                }
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(area) && hidden(root.relativize(path)))
                    .filter(path -> modifiedBefore(path, limit))
                    .forEach(stale::add);
            }
            stale.forEach(LocalFileStore::deleteRecursively);
            return stale.size();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static boolean modifiedBefore(Path path, long limit) {
        try {
            return Files.getLastModifiedTime(path).toMillis() < limit;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hidden(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** The path of a key: segments of safe characters only, and never outside the root. */
    Path resolve(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Empty storage key");
        }
        for (String segment : key.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Invalid storage key: " + key);
            }
        }
        Path root = root();
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Storage key outside the root: " + key);
        }
        return path;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(each);
                } catch (NoSuchFileException ignored) {
                    // deleted concurrently
                }
            }
        } catch (NoSuchFileException ignored) {
            // deleted concurrently
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

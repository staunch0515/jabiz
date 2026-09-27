package com.jabiz.runtime.file;

import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Where file contents are kept (docs/design/14-files.md section 6). Platform-internal: returns publishers and is never
 * handed to business code. Keys come from {@link FileKeys} only.
 */
public interface FileStore {

    /** Stores the content under {@code key}, replacing what was there; the object appears only once complete. */
    Mono<Void> write(String key, Flux<DataBuffer> content);

    /** Size of the object in bytes; empty when there is none. */
    Mono<Long> size(String key);

    /** {@code length} bytes of the object from {@code offset}. */
    Flux<DataBuffer> read(String key, long offset, long length);

    /** Deletes every object whose key starts with {@code prefix + "/"} (all objects of a file). */
    Mono<Void> deleteAll(String prefix);

    /** Keys of all objects, in no particular order. */
    Flux<String> list();
}

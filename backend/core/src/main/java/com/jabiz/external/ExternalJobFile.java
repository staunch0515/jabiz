package com.jabiz.external;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * One file an external job reads (decision D50 item 2), as its handler sees it during an attempt: the platform checked
 * at the start that the requester may read it, and copied its content for the attempt. Reading it is ordinary
 * blocking I/O on the handler's virtual thread. Once the attempt is over the copy is gone, and {@link #open()} fails.
 */
public interface ExternalJobFile {

    UUID fileId();

    /** The media type the platform recognised from the content when the file was stored. */
    String contentType();

    long sizeBytes();

    /**
     * The content, from its first byte; the caller closes the stream.
     *
     * @throws IllegalStateException once the attempt is over
     */
    InputStream open();

    /**
     * The whole content in memory; prefer {@link #open()} for large files.
     *
     * @throws IllegalStateException once the attempt is over
     */
    default byte[] bytes() {
        try (InputStream in = open()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

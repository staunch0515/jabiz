package com.jabiz.runtime.file;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responses carrying a stored object (docs/design/14-files.md section 5), shared by the authenticated and, later,
 * the public file endpoints: the recognised content type with {@code nosniff}, a sandboxing content security
 * policy, PDF as an attachment (never rendered inline on the application's origin), and single-range requests
 * ({@code Range: bytes=a-b}, for seeking in audio). Several ranges are answered with the whole object.
 */
public final class FileContent {

    /** Applied to every file response: whatever a file is, it runs no script on the application's origin. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; sandbox";

    private static final Pattern SINGLE_RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private FileContent() {}

    /** A single byte range, inclusive; {@code length} bytes from {@code start}. */
    record Range(long start, long length) {}

    /**
     * The range a {@code Range} header asks for in an object of {@code size} bytes: empty for the whole object
     * (no header, or one this endpoint does not serve partially, such as several ranges);
     * {@link IllegalArgumentException} when it cannot be satisfied (416).
     */
    static Optional<Range> range(String header, long size) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = SINGLE_RANGE.matcher(header.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String from = matcher.group(1);
        String to = matcher.group(2);
        try {
            if (from.isEmpty()) {
                if (to.isEmpty()) {
                    throw new IllegalArgumentException("empty range");
                }
                long suffix = Long.parseLong(to);
                if (suffix == 0 || size == 0) {
                    throw new IllegalArgumentException("unsatisfiable suffix range");
                }
                long length = Math.min(suffix, size);
                return Optional.of(new Range(size - length, length));
            }
            long start = Long.parseLong(from);
            long end = to.isEmpty() ? size - 1 : Math.min(Long.parseLong(to), size - 1);
            if (start >= size || end < start) {
                throw new IllegalArgumentException("unsatisfiable range");
            }
            return Optional.of(new Range(start, end - start + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("range out of bounds", e);
        }
    }

    /**
     * The response for {@code key}: 200 or 206 with the content, 416 for an unsatisfiable range, empty when the
     * object does not exist.
     *
     * @param downloadName file name offered to the browser
     * @param cacheControl the {@code Cache-Control} value: {@code private, no-store} behind authentication
     */
    static Mono<ResponseEntity<Flux<DataBuffer>>> serve(FileStore store, String key, String contentType,
        String downloadName, String rangeHeader, String cacheControl) {
        return store.size(key).map(size -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            headers.setCacheControl(cacheControl);
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            headers.setContentType(MediaType.parseMediaType(contentType));
            boolean attachment = !contentType.startsWith("image/") && !contentType.startsWith("audio/");
            headers.setContentDisposition((attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(downloadName, StandardCharsets.UTF_8).build());
            Optional<Range> range;
            try {
                range = range(rangeHeader, size);
            } catch (IllegalArgumentException e) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).headers(headers)
                    .body(Flux.<DataBuffer>empty());
            }
            if (range.isEmpty()) {
                headers.setContentLength(size);
                return ResponseEntity.ok().headers(headers).body(store.read(key, 0, size));
            }
            Range r = range.get();
            headers.setContentLength(r.length());
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + r.start() + "-" + (r.start() + r.length() - 1) + "/" + size);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers)
                .body(store.read(key, r.start(), r.length()));
        });
    }
}

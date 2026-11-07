package com.jabiz.file;

import java.util.Map;
import java.util.Objects;

/**
 * Downloads images from links for an external job's handler (decision D75), with every rule of
 * {@code POST /api/files/fetch}: https only, public addresses only on every hop (checked, then connected to), at most
 * {@code jabiz.files.fetch.max-redirects} redirects, the size counted as it streams, timeouts, the rate limits of the
 * job's requester and of the link's host, and the instance's cap on concurrent downloads. Blocking: call it from the
 * handler (it runs on a virtual thread, off the request path, without a database connection), never from a
 * non-blocking thread. The handler returns the bytes as an output file ({@code ExternalJobResult...file(key, name,
 * bytes)}); the platform re-encodes and registers them like an upload (decision D50).
 */
public interface RemoteImages {

    /**
     * The image {@code url} gives, at most {@code maxBytes}: a JPEG or a PNG as recognised from its content, before
     * any re-encoding.
     *
     * @throws Refused when the link, an address, a redirect, the size, the type, the time or a limit refuses it
     */
    Image fetch(String url, long maxBytes);

    /** Downloaded bytes and the type recognised from them. */
    record Image(byte[] content, MediaTypes type) {

        public Image {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(type, "type");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Image that && type == that.type && java.util.Arrays.equals(content, that.content);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(content) + type.hashCode();
        }

        @Override
        public String toString() {
            return "Image[" + type + ", " + content.length + " bytes]";
        }
    }

    /**
     * Why an image was not downloaded: one of the {@code FILE_FETCH_*} codes, {@code RATE_LIMITED} or
     * {@code PROCESS_BUSY}, with its parameters; the message never holds the link.
     */
    final class Refused extends RuntimeException {

        private final String code;
        private final transient Map<String, Object> params;

        public Refused(String code, String message, Map<String, Object> params) {
            super(message, null, false, false);
            this.code = Objects.requireNonNull(code, "code");
            this.params = params == null ? Map.of() : Map.copyOf(params);
        }

        public String code() {
            return code;
        }

        public Map<String, Object> params() {
            return params;
        }
    }
}

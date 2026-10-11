package com.jabiz.external;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synchronous HTTP to one declared {@link ExternalService} (decision D38), for blocking steps only: the platform
 * refuses calls on non-blocking threads, outside a blocking step, to hosts the service does not allow, and in dry
 * runs unless the service allows them. Redirects are never followed (a 3xx comes back as it is), the exchange is
 * bounded by the timeout and the body by the service's size limit, and nothing is retried: a step that wants a
 * retry decides itself, or runs after the commit with a retry policy.
 */
public interface ExternalHttp {

    /**
     * Sends the request and returns the answer, whatever its status.
     *
     * @throws ExternalCallException when the call is refused, times out, its answer is too large, or the connection
     *                               fails
     * @throws IllegalStateException when called on a non-blocking thread or outside a blocking step
     */
    Response send(Request request);

    /**
     * One request.
     *
     * @param method         an HTTP method, upper case
     * @param target         a path resolved against the service's base address (RFC 3986), or an absolute address on
     *                       one of its hosts
     * @param headers        request headers; values are never printed
     * @param body           the body, empty for none
     * @param timeout        the longest wait for this exchange, at most the service's request timeout; null for that
     * @param idempotencyKey sent in the service's idempotency header (the service must declare one); null for none
     */
    record Request(String method, String target, Map<String, String> headers, byte[] body, Duration timeout,
        String idempotencyKey) {

        private static final Pattern METHOD = Pattern.compile("[A-Z]{1,16}");
        private static final java.util.Set<String> PLATFORM_HEADERS = java.util.Set.of("host", "connection",
            "content-length", "expect", "upgrade", "transfer-encoding", "te", "trailer", "keep-alive");
        /** Keys of {@link ExternalServices#idempotencyKey} and the like: printable, no spaces. */
        public static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,255}");

        public Request {
            Objects.requireNonNull(method, "method must not be null");
            if (!METHOD.matcher(method).matches()) {
                throw new IllegalArgumentException("Not an HTTP method: " + method);
            }
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("A request needs a target");
            }
            Map<String, String> copy = new LinkedHashMap<>();
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (name == null || !ExternalService.HEADER_NAME.matcher(name).matches()) {
                        throw new IllegalArgumentException("Not a header name: " + name);
                    }
                    if (isPlatformHeader(name)) {
                        throw new IllegalArgumentException("Header " + name + " is set by the HTTP client, not by "
                            + "a request");
                    }
                    if (!isHeaderValue(value)) {
                        // The value itself is not repeated: it may be a credential.
                        throw new IllegalArgumentException("Header " + name + " has no value, or characters other "
                            + "than visible ASCII, space and tab");
                    }
                    // One header per name, case ignored: a later value replaces an earlier one.
                    copy.keySet().removeIf(name::equalsIgnoreCase);
                    copy.put(name, value);
                });
            }
            headers = Collections.unmodifiableMap(copy);
            body = body == null ? new byte[0] : body.clone();
            if (timeout != null && !timeout.isPositive()) {
                throw new IllegalArgumentException("A request's timeout must be positive");
            }
            if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
                throw new IllegalArgumentException("Idempotency keys are 1 to 255 characters of letters, digits, "
                    + "'.', '_', ':' and '-'");
            }
        }

        /**
         * Whether the HTTP client sets the header itself ({@code Host}, {@code Content-Length}, hop-by-hop and proxy
         * headers): a request may not.
         */
        public static boolean isPlatformHeader(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return PLATFORM_HEADERS.contains(lower) || lower.startsWith("proxy-");
        }

        /** The values the JDK client accepts: visible ASCII, space and horizontal tab (RFC 9110 field values). */
        public static boolean isHeaderValue(String value) {
            return value != null && value.chars().allMatch(c -> c == '\t' || (c >= 0x20 && c <= 0x7E));
        }

        public static Request of(String method, String target) {
            return new Request(method == null ? null : method.toUpperCase(Locale.ROOT), target, Map.of(), null, null,
                null);
        }

        public static Request get(String target) {
            return of("GET", target);
        }

        public static Request post(String target) {
            return of("POST", target);
        }

        /** Adds a header, replacing one of the same name (case ignored). */
        public Request header(String name, String value) {
            Map<String, String> more = new LinkedHashMap<>(headers);
            if (name != null) {
                more.keySet().removeIf(name::equalsIgnoreCase);
            }
            more.put(name, value);
            return new Request(method, target, more, body, timeout, idempotencyKey);
        }

        public Request body(String contentType, byte[] content) {
            return new Request(method, target, headers, content, timeout, idempotencyKey)
                .header("Content-Type", contentType);
        }

        public Request json(String json) {
            return body("application/json", json.getBytes(StandardCharsets.UTF_8));
        }

        public Request timeout(Duration limit) {
            return new Request(method, target, headers, body, limit, idempotencyKey);
        }

        public Request idempotencyKey(String key) {
            return new Request(method, target, headers, body, timeout, key);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /**
         * The method, where the request goes as far as the first path segment, the header names and the body's size:
         * never values, queries or deeper paths, which may carry identifiers or tokens.
         */
        @Override
        public String toString() {
            return "Request[" + method + " " + masked(target) + ", headers=" + headers.keySet() + ", body="
                + body.length + " bytes" + (timeout == null ? "" : ", timeout=" + timeout) + "]";
        }

        /** {@code https://host:port/first/…} or {@code /first/…}: the origin and the first path segment only. */
        static String masked(String target) {
            String rest = target;
            int cut = indexOfAny(rest, "?#");
            if (cut >= 0) {
                rest = rest.substring(0, cut);
            }
            String origin = "";
            int scheme = rest.indexOf("://");
            if (scheme >= 0 || rest.startsWith("//")) {
                int start = scheme >= 0 ? scheme + 3 : 2;
                int slash = rest.indexOf('/', start);
                String authority = slash < 0 ? rest.substring(start) : rest.substring(start, slash);
                int at = authority.lastIndexOf('@');
                origin = (scheme >= 0 ? rest.substring(0, scheme + 3) : "//")
                    + (at < 0 ? authority : authority.substring(at + 1));
                rest = slash < 0 ? "" : rest.substring(slash);
            }
            boolean leading = rest.startsWith("/");
            String path = leading ? rest.substring(1) : rest;
            int next = path.indexOf('/');
            String first = next < 0 ? path : path.substring(0, next) + "/…";
            return origin + (leading ? "/" : "") + first + (cut >= 0 && next < 0 ? "?…" : "");
        }

        private static int indexOfAny(String text, String characters) {
            for (int i = 0; i < text.length(); i++) {
                if (characters.indexOf(text.charAt(i)) >= 0) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Request r && method.equals(r.method) && target.equals(r.target)
                && headers.equals(r.headers) && java.util.Arrays.equals(body, r.body)
                && Objects.equals(timeout, r.timeout) && Objects.equals(idempotencyKey, r.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method, target, headers, java.util.Arrays.hashCode(body), timeout, idempotencyKey);
        }
    }

    /**
     * One answer.
     *
     * @param status  the HTTP status
     * @param headers response headers, names case-insensitive
     * @param body    the body as received
     */
    record Response(int status, Map<String, List<String>> headers, byte[] body) {

        private static final Pattern CHARSET = Pattern.compile("(?i)charset=\"?([A-Za-z0-9._:-]+)\"?");

        public Response {
            TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (headers != null) {
                headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            }
            headers = Collections.unmodifiableMap(copy);
            body = body == null ? new byte[0] : body.clone();
        }

        /** Whether the status is 2xx. */
        public boolean isSuccess() {
            return status / 100 == 2;
        }

        /** The first value of a header. */
        public Optional<String> header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
        }

        /** The body as text in the charset of its content type, UTF-8 when it names none. */
        public String text() {
            Charset charset = StandardCharsets.UTF_8;
            Optional<String> type = header("Content-Type");
            if (type.isPresent()) {
                Matcher matcher = CHARSET.matcher(type.get());
                try {
                    if (matcher.find() && Charset.isSupported(matcher.group(1))) {
                        charset = Charset.forName(matcher.group(1));
                    }
                } catch (IllegalArgumentException e) {
                    // An illegal charset name from the other side: read it as UTF-8 rather than fail.
                }
            }
            return new String(body, charset);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /** The status and the body's size: headers and body may carry credentials. */
        @Override
        public String toString() {
            return "Response[status=" + status + ", body=" + body.length + " bytes]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Response r && status == r.status && headers.equals(r.headers)
                && java.util.Arrays.equals(body, r.body);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, headers, java.util.Arrays.hashCode(body));
        }
    }
}

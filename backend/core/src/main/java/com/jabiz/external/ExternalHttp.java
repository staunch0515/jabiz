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
                    if (value == null || value.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
                        // The value itself is not repeated: it may be a credential.
                        throw new IllegalArgumentException("Header " + name + " has no value or a line break");
                    }
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

        public Request header(String name, String value) {
            Map<String, String> more = new LinkedHashMap<>(headers);
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

        /** The method, the path without its query, the header names and the body's size: never values. */
        @Override
        public String toString() {
            int query = target.indexOf('?');
            return "Request[" + method + " " + (query < 0 ? target : target.substring(0, query))
                + ", headers=" + headers.keySet() + ", body=" + body.length + " bytes"
                + (timeout == null ? "" : ", timeout=" + timeout) + "]";
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
                if (matcher.find() && Charset.isSupported(matcher.group(1))) {
                    charset = Charset.forName(matcher.group(1));
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

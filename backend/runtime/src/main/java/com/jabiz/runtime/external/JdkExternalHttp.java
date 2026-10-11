package com.jabiz.runtime.external;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalService;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.web.OutboundAddresses;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ExternalHttp} of one service over the JDK's {@link HttpClient} (decision D38). Every request is checked
 * before it is sent: the calling thread may block and runs a blocking step or an external job's handler
 * ({@link ExternalCallScope}), a dry run only calls a service that allows it, a handler only the services of its job
 * type, and the resolved address passes {@link OutboundAddresses} with the service's own hosts. Redirects are never followed. The whole exchange, body included, is bounded by the timeout, and the body
 * by the service's size limit. Each call is observed as {@value PlatformObservations#EXTERNAL_CALL} with the service
 * and the outcome ({@code 2xx} {@code 3xx} {@code 4xx} {@code 5xx} {@code timeout} {@code refused} {@code error}).
 *
 * <p>Failures name the service, the kind and at most the address's origin; never its path or query, a header or a
 * body, which may carry credentials. The JDK's exceptions are not attached for the same reason.
 */
final class JdkExternalHttp implements ExternalHttp, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdkExternalHttp.class);

    private final ExternalService service;
    private final HttpClient client;
    private final PlatformObservations observations;

    JdkExternalHttp(ExternalService service, PlatformObservations observations) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        this.client = HttpClient.newBuilder()
            .connectTimeout(service.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public Response send(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        if (Schedulers.isInNonBlockingThread()) {
            throw new IllegalStateException("External service " + service.name() + " was called on a non-blocking "
                + "thread: call external services from a BlockingStep only");
        }
        ExternalCallScope scope = ExternalCallScope.current().orElseThrow(() -> new IllegalStateException(
            "External service " + service.name() + " was called outside a blocking step: call external services "
                + "from a BlockingStep only"));
        long started = System.nanoTime();
        Response response = observations.outcomeOf(PlatformObservations.EXTERNAL_CALL, "external " + service.name(),
            KeyValues.of("service", service.name()), () -> exchange(scope, request),
            answer -> answer.status() / 100 + "xx", JdkExternalHttp::outcome);
        log.debug("External service {} answered {} to {} in {} ms (step '{}')", service.name(), response.status(),
            request.method(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), scope.stepName());
        return response;
    }

    private static String outcome(RuntimeException failure) {
        if (failure instanceof ExternalCallException external) {
            return switch (external.kind()) {
                case REFUSED -> "refused";
                case TIMEOUT -> "timeout";
                default -> "error";
            };
        }
        return failure instanceof IllegalArgumentException ? "refused" : "error";
    }

    private Response exchange(ExternalCallScope scope, Request request) {
        if (scope.dryRun() && !service.allowInDryRun()) {
            throw refused("not called in a dry run (the service does not declare allowInDryRun)");
        }
        if (!scope.allows(service.name())) {
            throw refused("not among the services of the running external job's type");
        }
        URI target = resolve(request.target());
        String refusal = OutboundAddresses.refusal(target, service.allowedHosts(),
            "the allowed hosts of external service " + service.name());
        if (refusal != null) {
            throw refused(refusal);
        }
        Duration timeout = request.timeout() == null ? service.requestTimeout() : request.timeout();
        if (timeout.compareTo(service.requestTimeout()) > 0) {
            throw new IllegalArgumentException("A request to external service " + service.name()
                + " may wait at most " + service.requestTimeout() + ", not " + timeout);
        }
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(target).timeout(timeout);
        } catch (IllegalArgumentException e) {
            throw refused("the target is not an address the client accepts");
        }
        // The JDK's own refusals repeat the value (a credential, perhaps): they become refusals naming the header.
        request.headers().forEach((name, value) -> withHeader(name, () -> builder.header(name, value)));
        if (request.idempotencyKey() != null) {
            if (service.idempotencyHeader() == null) {
                throw refused("the service declares no idempotency header");
            }
            withHeader(service.idempotencyHeader(),
                () -> builder.setHeader(service.idempotencyHeader(), request.idempotencyKey()));
        }
        if (request.headers().keySet().stream().noneMatch("User-Agent"::equalsIgnoreCase)) {
            builder.header("User-Agent", "jabiz-external");
        }
        byte[] body = request.body();
        try {
            builder.method(request.method(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        } catch (IllegalArgumentException e) {
            throw refused("method " + request.method() + " is not accepted");
        }
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(builder.build(),
            info -> new LimitedBody(service.maxResponseSize(),
                info.headers().firstValueAsLong("Content-Length").orElse(-1)));
        try {
            // The request's own timeout ends with the response's headers; this one bounds the body too.
            HttpResponse<byte[]> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ExternalCallException(service.name(), ExternalCallException.Kind.TIMEOUT,
                "no answer within " + timeout);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExternalCallException(service.name(), ExternalCallException.Kind.UNAVAILABLE, "interrupted");
        } catch (CancellationException e) {
            throw new ExternalCallException(service.name(), ExternalCallException.Kind.UNAVAILABLE, "cancelled");
        } catch (ExecutionException e) {
            throw failed(e.getCause(), timeout);
        }
    }

    private void withHeader(String name, Runnable set) {
        try {
            set.run();
        } catch (IllegalArgumentException e) {
            throw refused("header " + name + " is not accepted");
        }
    }

    private ExternalCallException failed(Throwable cause, Duration timeout) {
        Throwable root = cause;
        while (root != null && !(root instanceof ResponseTooLarge) && !(root instanceof HttpTimeoutException)
            && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof ResponseTooLarge) {
            return new ExternalCallException(service.name(), ExternalCallException.Kind.TOO_LARGE,
                "answer larger than " + service.maxResponseSize() + " bytes");
        }
        if (root instanceof HttpTimeoutException) {
            return new ExternalCallException(service.name(), ExternalCallException.Kind.TIMEOUT,
                "no answer within " + timeout);
        }
        // The kind of failure only: the JDK's messages may repeat the address.
        return new ExternalCallException(service.name(), ExternalCallException.Kind.UNAVAILABLE,
            "connection failed (" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + ")");
    }

    private URI resolve(String target) {
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException e) {
            throw refused("the target is not an address");
        }
        if (!uri.isAbsolute()) {
            return service.baseUrl().resolve(uri);
        }
        // An absolute target stays on the base address's scheme and port: the hosts are allowed, their other ports
        // (an administration interface, a plain-text one) are not.
        URI base = service.baseUrl();
        if (!base.getScheme().equalsIgnoreCase(String.valueOf(uri.getScheme())) || port(uri) != port(base)) {
            throw refused("an absolute target must use the scheme and port of the base address");
        }
        return uri;
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    private ExternalCallException refused(String why) {
        return new ExternalCallException(service.name(), ExternalCallException.Kind.REFUSED, why);
    }

    @Override
    public String toString() {
        return "JdkExternalHttp[service=" + service.name() + "]";
    }

    /** The body cut off by the size limit. */
    private static final class ResponseTooLarge extends IOException {
        ResponseTooLarge() {
            super("response too large", null);
        }
    }

    /** Collects a body of at most {@code limit} bytes; a larger one (declared or received) fails and is cancelled. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;
        private final boolean declaredTooLarge;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(long limit, long declaredLength) {
            this.limit = limit;
            this.declaredTooLarge = declaredLength > limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declaredTooLarge) {
                subscription.cancel();
                result.completeExceptionally(new ResponseTooLarge());
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (bytes.size() + (long) item.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLarge());
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}

package com.jabiz.runtime.context;

import com.jabiz.context.RequestContext;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Access to the {@link RequestContext} carried in the Reactor context of the current pipeline.
 *
 * <p>Alongside the context itself, its request id is stored under {@link #REQUEST_ID_KEY}; with automatic
 * context propagation that key is mirrored into the logging MDC on whatever thread a signal is handled.
 */
public final class RequestContexts {

    /** Reactor context key and MDC key of the request id. */
    public static final String REQUEST_ID_KEY = "requestId";

    private RequestContexts() {}

    /**
     * The context of the calling pipeline. Fails when there is none: platform code never falls back to a
     * made-up identity (docs/design/01-core-vs-runtime.md section 5).
     */
    public static Mono<RequestContext> current() {
        return Mono.deferContextual(view -> view.<RequestContext>getOrEmpty(RequestContext.class)
            .map(Mono::just)
            .orElseGet(() -> Mono.error(new IllegalStateException(
                "No RequestContext in the Reactor context; requests get one from RequestContextWebFilter, "
                    + "other callers must supply one (for example RequestContext.system)"))));
    }

    /**
     * The pipeline with its caller's entry scope left out (decision D64), for the platform's own reads of what a scope
     * does not limit: the open requests of an approval case, the tasks of a source key. The rest of the context stays.
     */
    public static <T> reactor.core.publisher.Flux<T> withoutEntryScope(reactor.core.publisher.Flux<T> pipeline) {
        return pipeline.contextWrite(view -> view.<RequestContext>getOrEmpty(RequestContext.class)
            .filter(request -> request.entryScope() != null)
            .map(request -> put(view, unscoped(request)))
            .orElseGet(() -> Context.of(view)));
    }

    private static RequestContext unscoped(RequestContext request) {
        return new RequestContext(request.actorId(), request.tenantId(), request.locale(), request.requestId(),
            request.roles(), request.permissions(), request.mfaAt(), request.dataPeriod(), request.entry(),
            request.emailVerified());
    }

    /** Adds the context (and its request id) to a Reactor context. */
    public static Context put(ContextView base, RequestContext request) {
        return Context.of(base).put(RequestContext.class, request).put(REQUEST_ID_KEY, request.requestId());
    }
}

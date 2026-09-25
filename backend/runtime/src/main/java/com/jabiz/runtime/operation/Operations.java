package com.jabiz.runtime.operation;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Optional;

/**
 * The operation of the current pipeline, carried in the Reactor context. Writes of temporal entities join the
 * operation they find there; code that starts operations (the dataset commit, reverts, later the process engine)
 * puts it there. A pipeline may also carry an {@link OperationRequest}, which says what an operation started
 * further down should be recorded as.
 */
public final class Operations {

    private Operations() {}

    public static Mono<Optional<Operation>> current() {
        return Mono.deferContextual(view -> Mono.just(view.<Operation>getOrEmpty(Operation.class)));
    }

    public static Context with(ContextView base, Operation operation) {
        return Context.of(base).put(Operation.class, operation);
    }

    public static Mono<Optional<OperationRequest>> requested() {
        return Mono.deferContextual(view -> Mono.just(view.<OperationRequest>getOrEmpty(OperationRequest.class)));
    }

    public static Context withRequest(ContextView base, OperationRequest request) {
        return Context.of(base).put(OperationRequest.class, request);
    }
}

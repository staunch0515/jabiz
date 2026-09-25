package com.jabiz.runtime.context;

import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Hooks;

/**
 * Wires the request context: who the actor is, and how the request id reaches the logs.
 *
 * <p>Automatic context propagation copies the {@link RequestContexts#REQUEST_ID_KEY} entry of the Reactor
 * context into the MDC whenever an operator handles a signal, on whichever thread that happens, so log
 * lines written deep inside a request (R2DBC callbacks, virtual threads) still carry the id.
 */
@Configuration
class RequestContextConfig {

    static final String DEV_PROFILE = "dev";

    static {
        ContextRegistry.getInstance().registerThreadLocalAccessor(RequestContexts.REQUEST_ID_KEY,
            () -> MDC.get(RequestContexts.REQUEST_ID_KEY),
            value -> MDC.put(RequestContexts.REQUEST_ID_KEY, value),
            () -> MDC.remove(RequestContexts.REQUEST_ID_KEY));
        Hooks.enableAutomaticContextPropagation();
    }

    /**
     * Anonymous callers unless development headers are explicitly enabled. Enabling them outside the
     * {@code dev} profile is a configuration error, not something to tolerate silently: the headers let
     * any caller claim any identity.
     */
    @Bean
    ActorResolver actorResolver(Environment environment,
        @Value("${jabiz.dev.actor-headers:false}") boolean devActorHeaders) {
        if (!devActorHeaders) {
            return request -> Actor.ANONYMOUS;
        }
        if (!environment.matchesProfiles(DEV_PROFILE)) {
            throw new IllegalStateException("jabiz.dev.actor-headers=true is only allowed with the '"
                + DEV_PROFILE + "' profile active");
        }
        return new DevHeaderActorResolver();
    }
}

package com.jabiz.runtime.context;

import com.jabiz.context.RequestContext;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Makes the authenticated actor the actor of the request (docs/design/10-security.md section 5): runs after Spring
 * Security's filter chain and replaces the anonymous {@link RequestContext} that {@link RequestContextWebFilter}
 * started with, keeping its request id and language. Requests nobody authenticated stay anonymous.
 */
@Component
@Order(0)
public class AuthenticatedRequestContextWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        RequestContext started = RequestContextWebFilter.of(exchange);
        if (started == null) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(ActorAuthentication.class::isInstance)
            .map(authentication -> Optional.of(authenticated(started, ((ActorAuthentication) authentication).actor())))
            .defaultIfEmpty(Optional.empty())
            .flatMap(authenticated -> authenticated.map(context -> {
                exchange.getAttributes().put(RequestContextWebFilter.ATTRIBUTE, context);
                return chain.filter(exchange).contextWrite(view -> RequestContexts.put(view, context));
            }).orElseGet(() -> chain.filter(exchange)));
    }

    private static RequestContext authenticated(RequestContext started, Actor actor) {
        return new RequestContext(actor.actorId(), actor.tenantId(), started.locale(), started.requestId(),
            actor.roles(), actor.permissions(), actor.mfaAt(), actor.dataPeriod());
    }
}

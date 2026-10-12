package com.jabiz.runtime.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Answers a browser that fetches a Service Worker script ({@code Service-Worker: script}) only for the {@code sw.js} of
 * an SPA that declares a PWA ({@code jabiz.web.spa[i].pwa}, decision D43 item 6); every other such request, the API
 * included, is 404. A second line of defence: a script uploaded to or injected into this origin cannot be registered as
 * a Service Worker, which would see every request of its scope. First of all filters, so that neither the API's
 * authentication nor any other answer can reach such a request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ServiceWorkerScriptGuard implements WebFilter {

    /** The request header a browser sends when fetching a Service Worker script (its value is {@code script}). */
    static final String SERVICE_WORKER = "Service-Worker";

    private final JabizWebProperties properties;

    ServiceWorkerScriptGuard(JabizWebProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (!request.getHeaders().containsHeader(SERVICE_WORKER)
            || properties.serviceWorkerScript(request.getPath().value())) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
    }
}

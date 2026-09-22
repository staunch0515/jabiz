package com.example.app;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
class SpaFallbackFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var req = exchange.getRequest();
        String path = req.getPath().value();
        boolean spaRoute = HttpMethod.GET.equals(req.getMethod())
                           && !path.startsWith("/api")
                           && !path.startsWith("/actuator")
                           && !path.contains(".");
        if (spaRoute) {
            return chain.filter(exchange.mutate()
                .request(req.mutate().path("/index.html").build()).build());
        }
        return chain.filter(exchange);
    }
}
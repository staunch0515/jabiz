package com.jabiz.runtime.proxy;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * Gate of {@code /api/proxy/**} (decision D60), before any security or controller code: only
 * {@code /api/proxy/<proxy>/<process>} of a proxy served without problems exists (404 otherwise, whatever the method),
 * and only {@code GET} and {@code POST} (405). The signature and the process are the controller's.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 12)
public class AppProxyWebFilter implements WebFilter {

    /** Matched as Spring Security and the controllers match paths (decoded, without matrix parameters). */
    private static final PathPattern PROXIES = PathPatternParser.defaultInstance.parse("/api/proxy/**");
    private static final PathPattern PROCESS = PathPatternParser.defaultInstance.parse(AppProxyController.PATH);
    private static final Set<HttpMethod> METHODS = Set.of(HttpMethod.GET, HttpMethod.POST);

    private final AppProxies proxies;
    private final JsonMapper json;

    public AppProxyWebFilter(AppProxies proxies, JsonMapper json) {
        this.proxies = proxies;
        this.json = json;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        if (!PROXIES.matches(path)) {
            return chain.filter(exchange);
        }
        PathPattern.PathMatchInfo match = PROCESS.matchAndExtract(path);
        String proxy = match == null ? null : match.getUriVariables().get("proxy");
        if (proxy == null || proxies.find(proxy).isEmpty()) {
            return problem(exchange, HttpStatus.NOT_FOUND, "Not found");
        }
        if (!METHODS.contains(exchange.getRequest().getMethod())) {
            exchange.getResponse().getHeaders().setAllow(METHODS);
            return problem(exchange, HttpStatus.METHOD_NOT_ALLOWED, "App proxy requests are GET or POST");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> problem(ServerWebExchange exchange, HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(exchange.getRequest().getURI());
        byte[] body = json.writeValueAsBytes(problem);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().getHeaders().setContentLength(body.length);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}

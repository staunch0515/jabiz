package com.jabiz.runtime.publicread;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.context.RequestContextWebFilter;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.web.TokenBucketLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
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

import com.jabiz.runtime.web.ClientAddresses;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gate of {@code /api/public/**} (docs/design/15-public-access.md sections 5 and 6), before any security or
 * controller code: with {@code jabiz.public.enabled=false} every request is answered 404; methods other than
 * {@code GET} and {@code HEAD} are answered 405 and never reach a write path; each client address may make
 * {@code jabiz.public.rate-limit.per-minute} requests (429 {@code RATE_LIMITED} with {@code Retry-After}).
 *
 * <p>The client address comes from {@link ClientAddresses}: forwarded headers count only from trusted proxies
 * (decision D36 item 7), the same address login records keep. It is never logged or tagged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicAccessWebFilter implements WebFilter {

    /**
     * The public API, matched as Spring Security and the controllers match paths (decoded, without matrix
     * parameters), so that no other spelling of it passes the switch and the limit.
     */
    private static final PathPattern PUBLIC = PathPatternParser.defaultInstance.parse("/api/public/**");

    private static final Set<HttpMethod> READ = Set.of(HttpMethod.GET, HttpMethod.HEAD);

    private final PublicProperties properties;
    private final JsonMapper json;
    private final MessageCatalog messages;
    private final TokenBucketLimiter limiter;
    private final PlatformObservations observations;
    private final ClientAddresses clients;

    public PublicAccessWebFilter(PublicProperties properties, JsonMapper json, MessageCatalog messages,
        PlatformObservations observations, ClientAddresses clients) {
        this.properties = properties;
        this.json = json;
        this.messages = messages;
        this.observations = observations;
        this.clients = clients;
        this.limiter = new TokenBucketLimiter(Math.max(1, properties.rateLimit().perMinute()), Duration.ofMinutes(1),
            Math.max(1, properties.rateLimit().maxClients()));
    }

    /** Whether the request belongs to the public API. */
    public static boolean isPublic(ServerWebExchange exchange) {
        return PUBLIC.matches(exchange.getRequest().getPath().pathWithinApplication());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isPublic(exchange)) {
            return chain.filter(exchange);
        }
        if (!properties.on()) {
            return problem(exchange, HttpStatus.NOT_FOUND, "Not found", null);
        }
        if (!READ.contains(exchange.getRequest().getMethod())) {
            exchange.getResponse().getHeaders().setAllow(READ);
            return problem(exchange, HttpStatus.METHOD_NOT_ALLOWED, "Public resources are read-only", null);
        }
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(clients.clientOf(exchange.getRequest()));
        if (!decision.allowed()) {
            observations.event(PlatformObservations.PUBLIC_RATE_LIMITED);
            RateLimitedException limited = new RateLimitedException(
                "Too many requests; try again in " + decision.retryAfterSeconds() + " s",
                decision.retryAfterSeconds());
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                String.valueOf(limited.retryAfterSeconds()));
            return problem(exchange, HttpStatus.TOO_MANY_REQUESTS, limited.getMessage(),
                limited.violations().getFirst());
        }
        return chain.filter(exchange);
    }

    private Mono<Void> problem(ServerWebExchange exchange, HttpStatus status, String detail, Violation violation) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(exchange.getRequest().getURI());
        if (violation != null) {
            RequestContext request = RequestContextWebFilter.of(exchange);
            Locale locale = request != null ? request.locale() : messages.defaultLocale();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", violation.field());
            entry.put("ruleCode", violation.ruleCode());
            entry.put("message", messages.message(violation, locale));
            problem.setProperty("violations", List.of(entry));
        }
        byte[] body = json.writeValueAsBytes(problem);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().getHeaders().setContentLength(body.length);
        if (HttpMethod.HEAD.equals(exchange.getRequest().getMethod())) {
            return exchange.getResponse().setComplete();
        }
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}

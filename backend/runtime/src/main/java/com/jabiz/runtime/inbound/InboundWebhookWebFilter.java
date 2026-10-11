package com.jabiz.runtime.inbound;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.context.RequestContextWebFilter;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.web.ClientAddresses;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gate of {@code /api/inbound/**} (decision D37 item 5; docs/design/15-public-access.md section 6), before any security
 * or controller code: only {@code /api/inbound/<provider>} of a provider served without problems exists (404
 * otherwise, whatever the method), only {@code POST} is allowed (405), and each client address may make the provider's
 * {@code rate-limit.per-minute} requests (429 {@code RATE_LIMITED} with {@code Retry-After}), whatever
 * {@code jabiz.public.enabled} says. The client address comes from {@link ClientAddresses} (forwarded headers count only
 * from trusted proxies); it is never logged or tagged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class InboundWebhookWebFilter implements WebFilter {

    /** Matched as Spring Security and the controllers match paths (decoded, without matrix parameters). */
    private static final PathPattern INBOUND = PathPatternParser.defaultInstance.parse("/api/inbound/**");
    private static final PathPattern PROVIDER = PathPatternParser.defaultInstance.parse("/api/inbound/{provider}");

    private final InboundWebhookChecks providers;
    private final JsonMapper json;
    private final MessageCatalog messages;
    private final PlatformObservations observations;
    private final ClientAddresses clients;
    private final Map<String, TokenBucketLimiter> limiters = new LinkedHashMap<>();

    public InboundWebhookWebFilter(InboundWebhookProperties properties, InboundWebhookChecks providers,
        JsonMapper json, MessageCatalog messages, PlatformObservations observations, ClientAddresses clients) {
        this.providers = providers;
        this.json = json;
        this.messages = messages;
        this.observations = observations;
        this.clients = clients;
        properties.providers().keySet().forEach(name -> providers.served(name).ifPresent(provider ->
            limiters.put(name, new TokenBucketLimiter(provider.rateLimit().perMinute(), Duration.ofMinutes(1),
                provider.rateLimit().maxClients()))));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        if (!INBOUND.matches(path)) {
            return chain.filter(exchange);
        }
        PathPattern.PathMatchInfo match = PROVIDER.matchAndExtract(path);
        String provider = match == null ? null : match.getUriVariables().get("provider");
        TokenBucketLimiter limiter = provider == null || providers.served(provider).isEmpty() ? null
            : limiters.get(provider);
        if (limiter == null) {
            return problem(exchange, HttpStatus.NOT_FOUND, "Not found", null);
        }
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
            exchange.getResponse().getHeaders().setAllow(Set.of(HttpMethod.POST));
            return problem(exchange, HttpStatus.METHOD_NOT_ALLOWED, "Inbound webhooks are POSTed", null);
        }
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(clients.clientOf(exchange.getRequest()));
        if (!decision.allowed()) {
            observations.event(PlatformObservations.INBOUND_RATE_LIMITED);
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

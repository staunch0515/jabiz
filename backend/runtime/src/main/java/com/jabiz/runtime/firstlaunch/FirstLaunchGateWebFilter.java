package com.jabiz.runtime.firstlaunch;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
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
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * While the {@link FirstLaunchGate} is closed, answers every request with 503 {@code FIRST_LAUNCH_IN_PROGRESS} and a
 * {@code Retry-After} (docs/design/23-first-launch.md section 3): pages and API alike, before anything else runs.
 * Only the actuator's health stays reachable, so that an orchestrator sees the process alive.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FirstLaunchGateWebFilter implements WebFilter {

    /** Seconds a client is asked to wait before trying again. */
    static final int RETRY_AFTER_SECONDS = 30;

    private final FirstLaunchGate gate;
    private final MessageCatalog messages;
    private final JsonMapper json;

    public FirstLaunchGateWebFilter(FirstLaunchGate gate, MessageCatalog messages, JsonMapper json) {
        this.gate = gate;
        this.messages = messages;
        this.json = json;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (gate.isOpen() || exchange.getRequest().getPath().value().startsWith("/actuator/health")) {
            return chain.filter(exchange);
        }
        Locale locale = messages.supported(exchange.getRequest().getHeaders().getAcceptLanguageAsLocales().stream()
            .findFirst().orElse(messages.defaultLocale()));
        String detail = messages.find(PlatformErrorCodes.FIRST_LAUNCH_IN_PROGRESS, locale)
            .orElse("The system is being set up for its first launch.")
            .replace("{retryAfter}", String.valueOf(RETRY_AFTER_SECONDS));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problem.setInstance(exchange.getRequest().getURI());
        problem.setProperty("violations", List.of(Map.of("field", "", "ruleCode",
            PlatformErrorCodes.FIRST_LAUNCH_IN_PROGRESS, "message", detail)));
        byte[] body = json.writeValueAsBytes(problem);
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        response.getHeaders().setContentLength(body.length);
        if (HttpMethod.HEAD.equals(exchange.getRequest().getMethod())) {
            return response.setComplete();
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}

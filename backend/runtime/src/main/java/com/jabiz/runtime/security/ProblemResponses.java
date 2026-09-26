package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.context.RequestContextWebFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 401 and 403 answers of Spring Security's filter chain, in the same {@code ProblemDetail} form (with localized
 * {@code violations}) as the answers of the controllers.
 */
class ProblemResponses implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final MessageCatalog messages;
    private final JsonMapper json;

    ProblemResponses(MessageCatalog messages, JsonMapper json) {
        this.messages = messages;
        this.json = json;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return write(exchange, HttpStatus.UNAUTHORIZED, new Violation(null,
            com.jabiz.i18n.PlatformErrorCodes.UNAUTHENTICATED, "Authentication required"));
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return write(exchange, HttpStatus.FORBIDDEN, new Violation(null,
            com.jabiz.i18n.PlatformErrorCodes.PERMISSION_DENIED, "Access denied", Map.of("permission", "-")));
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, Violation violation) {
        RequestContext request = RequestContextWebFilter.of(exchange);
        Locale locale = request != null ? request.locale() : messages.defaultLocale();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", violation.field());
        entry.put("ruleCode", violation.ruleCode());
        entry.put("message", messages.message(violation, locale));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, violation.message());
        problem.setInstance(exchange.getRequest().getURI());
        problem.setProperty("violations", List.of(entry));
        byte[] body = json.writeValueAsBytes(problem);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}

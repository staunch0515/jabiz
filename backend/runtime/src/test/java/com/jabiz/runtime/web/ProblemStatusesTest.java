package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.ScopeUnavailableException;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.IdempotencyConflictException;
import com.jabiz.runtime.PayloadTooLargeException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.context.RequestContextWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The statuses scenario replay reports are the ones the HTTP API answers with. */
class ProblemStatusesTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new MessageCatalog(
        List.of(MessageCatalog.PLATFORM_BUNDLE), List.of(Locale.ENGLISH), Locale.ENGLISH,
        ProblemStatusesTest.class.getClassLoader()));

    @Test
    void statusesAgreeWithTheExceptionHandler() {
        ServerWebExchange exchange = exchange();
        List<Violation> violations = List.of(new Violation("f", "R", "m"));

        ValidationException invalid = new ValidationException(violations);
        assertThat(ProblemStatuses.status(invalid)).isEqualTo(handler.handleValidation(invalid, exchange).getStatus());
        BusinessRuleViolationException rule = new BusinessRuleViolationException(violations);
        assertThat(ProblemStatuses.status(rule)).isEqualTo(handler.handleBusinessRule(rule, exchange).getStatus());
        EntityNotFoundException missing = new EntityNotFoundException("gone");
        assertThat(ProblemStatuses.status(missing)).isEqualTo(handler.handleNotFound(missing).getStatus());
        ConcurrentUpdateException conflict = new ConcurrentUpdateException("stale");
        assertThat(ProblemStatuses.status(conflict)).isEqualTo(handler.handleConcurrentUpdate(conflict).getStatus());
        PermissionDeniedException denied = new PermissionDeniedException("p", "no");
        ProblemDetail deniedProblem = handler.handlePermissionDenied(denied, exchange);
        assertThat(ProblemStatuses.status(denied)).isEqualTo(deniedProblem.getStatus());
        IdempotencyConflictException reused = new IdempotencyConflictException("reused");
        assertThat(ProblemStatuses.status(reused))
            .isEqualTo(handler.handleIdempotencyConflict(reused, exchange).getStatus());
        AuthenticationFailedException unauthenticated = new AuthenticationFailedException("LOGIN_FAILED", "no");
        assertThat(ProblemStatuses.status(unauthenticated))
            .isEqualTo(handler.handleAuthenticationFailed(unauthenticated, exchange).getStatusCode().value());
        PayloadTooLargeException tooLarge = new PayloadTooLargeException(new Violation("file", "FILE_TOO_LARGE", "big",
            Map.of("max", "1 MB")));
        assertThat(ProblemStatuses.status(tooLarge))
            .isEqualTo(handler.handlePayloadTooLarge(tooLarge, exchange).getStatus()).isEqualTo(413);
        RateLimitedException limited = new RateLimitedException("slow down", 7);
        var limitedResponse = handler.handleRateLimited(limited, exchange);
        assertThat(ProblemStatuses.status(limited)).isEqualTo(limitedResponse.getStatusCode().value()).isEqualTo(429);
        assertThat(limitedResponse.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(ProblemStatuses.violations(limited)).singleElement()
            .satisfies(v -> assertThat(v.params()).isEqualTo(Map.of("retryAfter", 7L)));
        assertThat(ProblemStatuses.violations(tooLarge)).extracting(Violation::ruleCode)
            .containsExactly("FILE_TOO_LARGE");
        assertThat(ProblemStatuses.status(new IllegalStateException())).isEqualTo(500);
    }

    @Test
    void violationsAreThoseOfTheResponse() {
        assertThat(ProblemStatuses.violations(new PermissionDeniedException("p", "no")))
            .singleElement().satisfies(v -> {
                assertThat(v.ruleCode()).isEqualTo("PERMISSION_DENIED");
                assertThat(v.params()).isEqualTo(Map.of("permission", "p"));
            });
        assertThat(ProblemStatuses.violations(new ScopeUnavailableException("tenantId", "tenantId")))
            .extracting(Violation::ruleCode).containsExactly("SCOPE_UNAVAILABLE");
        assertThat(ProblemStatuses.status(new ScopeUnavailableException("tenantId", "tenantId")))
            .isEqualTo(403);
        assertThat(ProblemStatuses.violations(new ConcurrentUpdateException("stale"))).isEmpty();
    }

    private static ServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        exchange.getAttributes().put(RequestContextWebFilter.ATTRIBUTE, new RequestContext("a", null,
            Locale.ENGLISH, "r", Set.of(), Set.of()));
        return exchange;
    }
}

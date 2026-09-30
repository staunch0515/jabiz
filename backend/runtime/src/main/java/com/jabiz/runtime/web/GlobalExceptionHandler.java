package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.ScopeUnavailableException;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.resource.InvalidResourceIdException;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.IdempotencyConflictException;
import com.jabiz.runtime.PayloadTooLargeException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.RebaseConflictException;
import com.jabiz.runtime.RevertConflictException;
import com.jabiz.runtime.storage.AppendOnlyViolationException;
import com.jabiz.runtime.context.RequestContextWebFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps domain exceptions to RFC 7807 problem responses.
 *
 * <p>Anything else is answered with a bare 500 problem and logged here, while the request context (and so the
 * request id in the log) is still available; exceptions that already describe an HTTP response (bad input,
 * unknown route, unsupported media type ...) keep that response.
 *
 * <p>Violations are returned as {@code violations[] = {field, ruleCode, message}}, with the message in the
 * language of the request (docs/design/02-metamodel.md section 3.1).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageCatalog messages;

    public GlobalExceptionHandler(MessageCatalog messages) {
        this.messages = messages;
    }

    @ExceptionHandler({EntityNotFoundException.class, ResourceNotFoundException.class})
    ProblemDetail handleNotFound(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(statusOf(ex), ex.getMessage());
    }

    @ExceptionHandler(InvalidResourceIdException.class)
    ProblemDetail handleInvalidResourceId(InvalidResourceIdException ex) {
        return ProblemDetail.forStatusAndDetail(statusOf(ex), ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    ProblemDetail handleValidation(ValidationException ex, ServerWebExchange exchange) {
        return withViolations(statusOf(ex), ex.getMessage(), ex.violations(), exchange);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail handleBusinessRule(BusinessRuleViolationException ex, ServerWebExchange exchange) {
        return withViolations(statusOf(ex), ex.getMessage(), ex.violations(), exchange);
    }

    /** A dataset scope needs a value the caller's context lacks: access is refused, not widened. */
    @ExceptionHandler(ScopeUnavailableException.class)
    ProblemDetail handleScopeUnavailable(ScopeUnavailableException ex, ServerWebExchange exchange) {
        return withViolations(statusOf(ex), ex.getMessage(), ProblemStatuses.violations(ex), exchange);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .body(response.getBody());
        }
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    ProblemDetail handleConcurrentUpdate(ConcurrentUpdateException ex, ServerWebExchange exchange) {
        List<Violation> violations = ProblemStatuses.violations(ex);
        return violations.isEmpty() ? ProblemDetail.forStatusAndDetail(statusOf(ex), ex.getMessage())
            : withViolations(statusOf(ex), ex.getMessage(), violations, exchange);
    }

    /** Later versions changed the same fields (decision D1): lists them so the caller can cancel or change them. */
    @ExceptionHandler(RebaseConflictException.class)
    ProblemDetail handleRebaseConflict(RebaseConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusOf(ex), ex.getMessage());
        problem.setProperty("conflicts", ex.conflicts().stream().map(c -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entityType", c.entityType());
            entry.put("entityId", String.valueOf(c.entityId()));
            entry.put("versionNo", c.versionNo());
            entry.put("effectStartTime", c.effectiveFrom().toString());
            entry.put("processSeqId", c.processSeqId());
            entry.put("fields", c.fields().stream().sorted().toList());
            return entry;
        }).toList());
        return problem;
    }

    /** Later operations block a revert (decision D2): lists them, newest first. */
    @ExceptionHandler(RevertConflictException.class)
    ProblemDetail handleRevertConflict(RevertConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusOf(ex), ex.getMessage());
        problem.setProperty("blockingOperations", ex.blocking().stream().map(b -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("processSeqId", b.processSeqId());
            entry.put("entityType", b.entityType());
            entry.put("entityId", String.valueOf(b.entityId()));
            entry.put("fields", b.fields());
            return entry;
        }).toList());
        return problem;
    }

    /** An idempotency key reused for another process (decision D11). */
    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex, ServerWebExchange exchange) {
        return withViolations(statusOf(ex), ex.getMessage(), ProblemStatuses.violations(ex), exchange);
    }

    /** Signing in or refreshing failed (docs/design/10-security.md section 4); tells no more than the code. */
    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ProblemDetail> handleAuthenticationFailed(AuthenticationFailedException ex,
        ServerWebExchange exchange) {
        return ResponseEntity.status(statusOf(ex)).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .body(withViolations(statusOf(ex), "Authentication failed", ProblemStatuses.violations(ex),
                exchange));
    }

    @ExceptionHandler(PermissionDeniedException.class)
    ProblemDetail handlePermissionDenied(PermissionDeniedException ex, ServerWebExchange exchange) {
        return withViolations(statusOf(ex), ex.getMessage(), ProblemStatuses.violations(ex), exchange);
    }

    /** An upload larger than accepted (docs/design/14-files.md section 5); the rest of the body is not read. */
    @ExceptionHandler(PayloadTooLargeException.class)
    ProblemDetail handlePayloadTooLarge(PayloadTooLargeException ex, ServerWebExchange exchange) {
        return withViolations(statusOf(ex), ex.getMessage(), ProblemStatuses.violations(ex), exchange);
    }

    /** Too many requests of one caller: says when to try again. */
    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException ex, ServerWebExchange exchange) {
        return ResponseEntity.status(statusOf(ex)).header(HttpHeaders.RETRY_AFTER,
                String.valueOf(ex.retryAfterSeconds()))
            .body(withViolations(statusOf(ex), ex.getMessage(), ProblemStatuses.violations(ex), exchange));
    }

    /**
     * The database refused to change an append-only table (decision D5). The platform never asks for that, so this
     * is a defect: logged as an error, answered as a plain 500.
     */
    @ExceptionHandler(AppendOnlyViolationException.class)
    ProblemDetail handleAppendOnlyViolation(AppendOnlyViolationException ex) {
        log.error("SEVERE: append-only guard rejected a write; this is a defect", ex);
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** The status of a domain exception; {@link ProblemStatuses} is the one table of them. */
    private static HttpStatus statusOf(Throwable ex) {
        return HttpStatus.valueOf(ProblemStatuses.status(ex));
    }

    private ProblemDetail withViolations(
        HttpStatus status, String detail, List<Violation> violations, ServerWebExchange exchange
    ) {
        RequestContext request = RequestContextWebFilter.of(exchange);
        Locale locale = request != null ? request.locale() : messages.defaultLocale();
        List<Map<String, Object>> body = violations.stream().map(violation -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", violation.field());
            entry.put("ruleCode", violation.ruleCode());
            entry.put("message", messages.message(violation, locale));
            return entry;
        }).toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("violations", body);
        return problem;
    }
}

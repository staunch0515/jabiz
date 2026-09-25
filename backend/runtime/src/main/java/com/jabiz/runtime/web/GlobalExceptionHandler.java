package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.resource.InvalidResourceIdException;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContextWebFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidResourceIdException.class)
    ProblemDetail handleInvalidResourceId(InvalidResourceIdException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    ProblemDetail handleValidation(ValidationException ex, ServerWebExchange exchange) {
        return withViolations(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.violations(), exchange);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail handleBusinessRule(BusinessRuleViolationException ex, ServerWebExchange exchange) {
        return withViolations(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), ex.violations(), exchange);
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
    ProblemDetail handleConcurrentUpdate(ConcurrentUpdateException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
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

package com.jabiz.runtime.web;

import com.jabiz.dataset.ScopeUnavailableException;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
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
import com.jabiz.runtime.imports.ImportConflictException;
import com.jabiz.runtime.sod.SodConflictException;

import java.util.List;
import java.util.Map;

/**
 * The HTTP status and violations the platform answers a domain exception with, for callers that run processes
 * without HTTP (scenario replay, docs/design/07-quality.md section 3) and must still see what an API client would.
 * {@link GlobalExceptionHandler} answers with these statuses and violations, so both always agree; a new domain
 * exception gets its status here.
 */
public final class ProblemStatuses {

    private ProblemStatuses() {}

    /** The status of the response to {@code error}; 500 for anything that is not a domain exception. */
    public static int status(Throwable error) {
        return switch (error) {
            case ValidationException e -> 400;
            case InvalidResourceIdException e -> 400;
            case AuthenticationFailedException e -> 401;
            case PermissionDeniedException e -> 403;
            case ScopeUnavailableException e -> 403;
            case EntityNotFoundException e -> 404;
            case ResourceNotFoundException e -> 404;
            case ConcurrentUpdateException e -> 409; // also rebase and revert conflicts
            case IdempotencyConflictException e -> 409;
            case BusinessRuleViolationException e -> 422;
            case PayloadTooLargeException e -> 413;
            case RateLimitedException e -> 429;
            default -> 500;
        };
    }

    /** The violations of the response to {@code error}; empty when the response carries none. */
    public static List<Violation> violations(Throwable error) {
        return switch (error) {
            case ValidationException e -> e.violations();
            case BusinessRuleViolationException e -> e.violations();
            case ScopeUnavailableException e -> List.of(new Violation(e.field(), PlatformErrorCodes.SCOPE_UNAVAILABLE,
                e.getMessage(), Map.of("source", e.source())));
            case SodConflictException e -> List.of(new Violation(null, PlatformErrorCodes.SOD_CONFLICT,
                e.getMessage(), Map.of("rule", e.ruleCode())));
            case PermissionDeniedException e -> List.of(new Violation(null, PlatformErrorCodes.PERMISSION_DENIED,
                e.getMessage(), Map.of("permission", e.permission())));
            case IdempotencyConflictException e -> List.of(new Violation(null,
                PlatformErrorCodes.IDEMPOTENCY_KEY_REUSED, e.getMessage()));
            case AuthenticationFailedException e -> List.of(new Violation(null, e.code(), e.getMessage()));
            case ImportConflictException e -> List.of(new Violation(null, e.code(), e.getMessage(), e.params()));
            case PayloadTooLargeException e -> e.violations();
            case RateLimitedException e -> e.violations();
            default -> List.of();
        };
    }
}

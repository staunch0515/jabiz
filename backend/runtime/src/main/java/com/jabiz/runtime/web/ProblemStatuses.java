package com.jabiz.runtime.web;

import com.jabiz.external.ExternalCallException;
import com.jabiz.dataset.ScopeUnavailableException;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.resource.InvalidResourceIdException;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.AuthBusyException;
import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.IdempotencyConflictException;
import com.jabiz.runtime.PayloadTooLargeException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.ProcessBusyException;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.imports.ImportConflictException;
import com.jabiz.runtime.inbound.InboundRejectedException;
import com.jabiz.runtime.process.ProcessInternalException;
import com.jabiz.runtime.security.EmailNotVerifiedException;
import com.jabiz.runtime.security.MfaRequiredException;
import com.jabiz.runtime.security.OidcAccountExistsException;
import com.jabiz.runtime.security.SessionNotFoundException;
import com.jabiz.runtime.security.SignInRefusedException;
import com.jabiz.runtime.sod.SodConflictException;

import org.springframework.dao.QueryTimeoutException;

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
            case InboundRejectedException e -> 400;
            case RateLimitedException e -> 429;
            // Too much asked of the database just now: not the caller's mistake, and not a defect.
            case QueryTimeoutException e -> 503;
            // The queue before a hot lock is full (decision D45): try again later.
            case ProcessBusyException e -> 503;
            // Password hashing is saturated (decision D47): try again later.
            case AuthBusyException e -> 503;
            // Another system did not answer usably (decision D38).
            case ExternalCallException e -> 502;
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
            case MfaRequiredException e -> List.of(new Violation(null, PlatformErrorCodes.MFA_REQUIRED,
                e.getMessage()));
            case EmailNotVerifiedException e -> List.of(new Violation(null, PlatformErrorCodes.EMAIL_NOT_VERIFIED,
                e.getMessage()));
            case ProcessInternalException e -> List.of(new Violation(null, PlatformErrorCodes.PROCESS_INTERNAL,
                e.getMessage(), Map.of("process", e.process())));
            case SignInRefusedException e -> List.of(new Violation(null, PlatformErrorCodes.SIGN_IN_REFUSED,
                e.getMessage()));
            case OidcAccountExistsException e -> List.of(new Violation(null, PlatformErrorCodes.OIDC_ACCOUNT_EXISTS,
                e.getMessage()));
            case SessionNotFoundException e -> List.of(new Violation(null, PlatformErrorCodes.SESSION_NOT_FOUND,
                e.getMessage()));
            case PermissionDeniedException e -> List.of(new Violation(null, PlatformErrorCodes.PERMISSION_DENIED,
                e.getMessage(), Map.of("permission", e.permission())));
            case IdempotencyConflictException e -> List.of(new Violation(null,
                PlatformErrorCodes.IDEMPOTENCY_KEY_REUSED, e.getMessage()));
            case AuthenticationFailedException e -> List.of(new Violation(null, e.code(), e.getMessage()));
            case ImportConflictException e -> List.of(new Violation(null, e.code(), e.getMessage(), e.params()));
            case PayloadTooLargeException e -> e.violations();
            case InboundRejectedException e -> e.violations();
            case RateLimitedException e -> e.violations();
            case ProcessBusyException e -> e.violations();
            case AuthBusyException e -> e.violations();
            case ExternalCallException e -> List.of(new Violation(null, PlatformErrorCodes.EXTERNAL_CALL_FAILED,
                e.getMessage(), Map.of("service", e.service(), "kind", e.kind().name())));
            case QueryTimeoutException e -> List.of(new Violation(null, PlatformErrorCodes.QUERY_TIMEOUT,
                "The query did not finish within its time limit", Map.of()));
            default -> List.of();
        };
    }
}

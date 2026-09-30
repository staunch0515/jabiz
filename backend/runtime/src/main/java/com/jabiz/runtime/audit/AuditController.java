package com.jabiz.runtime.audit;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code GET /api/audit/operations} (docs/design/11-ledger-events-jobs.md section 3): operations by actor, time,
 * process and entity, newest first; {@code GET /api/audit/records} (docs/design/21-audit-retention.md section 1):
 * the audit trail with values. Needs permission {@value #READ}. Malformed filters are reported together (400).
 */
@RestController
@RequestMapping("/api/audit")
class AuditController {

    static final String READ = "audit.read";

    private final AuditService audit;

    AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/operations")
    Mono<Map<String, Object>> operations(
        @RequestParam(required = false) String actorId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String processName,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String entityId,
        @RequestParam(required = false) String offset,
        @RequestParam(required = false) String limit
    ) {
        return RequestContexts.current().flatMap(request -> {
            if (!request.hasPermission(READ)) {
                return Mono.error(new PermissionDeniedException(READ, "Reading the audit trail needs permission "
                    + READ));
            }
            List<Violation> violations = new ArrayList<>();
            AuditQuery query = new AuditQuery(blankToNull(actorId),
                parse("from", from, Instant::parse, violations),
                parse("to", to, Instant::parse, violations),
                blankToNull(processName), blankToNull(entityType),
                parse("entityId", entityId, UUID::fromString, violations),
                bounded("offset", offset, 0, 0, Integer.MAX_VALUE, violations),
                bounded("limit", limit, AuditQuery.DEFAULT_LIMIT, 1, AuditQuery.MAX_LIMIT, violations));
            if (!violations.isEmpty()) {
                return Mono.error(new ValidationException(violations));
            }
            return audit.operations(query);
        });
    }

    /** {@code GET /api/audit/records}: the audit trail with values, newest first (21 section 1). */
    @GetMapping("/records")
    Mono<AuditService.AuditRecordPage> records(
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String entityId,
        @RequestParam(required = false) String actorId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String processName,
        @RequestParam(required = false) String field,
        @RequestParam(required = false) String offset,
        @RequestParam(required = false) String limit
    ) {
        return RequestContexts.current().flatMap(request -> {
            if (!request.hasPermission(READ)) {
                return Mono.error(new PermissionDeniedException(READ, "Reading the audit trail needs permission "
                    + READ));
            }
            List<Violation> violations = new ArrayList<>();
            AuditService.RecordQuery query = new AuditService.RecordQuery(blankToNull(entityType),
                blankToNull(entityId), blankToNull(actorId),
                parse("from", from, Instant::parse, violations),
                parse("to", to, Instant::parse, violations),
                blankToNull(processName), blankToNull(field),
                bounded("offset", offset, 0, 0, Integer.MAX_VALUE, violations),
                bounded("limit", limit, AuditQuery.DEFAULT_LIMIT, 1, AuditQuery.MAX_LIMIT, violations));
            if (!violations.isEmpty()) {
                return Mono.error(new ValidationException(violations));
            }
            return audit.records(query);
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <T> T parse(String name, String raw, Function<String, T> parser, List<Violation> violations) {
        if (blankToNull(raw) == null) {
            return null;
        }
        try {
            return parser.apply(raw.trim());
        } catch (IllegalArgumentException | DateTimeParseException e) {
            violations.add(new Violation(name, PlatformErrorCodes.INVALID_VALUE,
                "'" + raw + "' is not a valid " + name));
            return null;
        }
    }

    private static int bounded(String name, String raw, int fallback, int min, int max, List<Violation> violations) {
        Integer value = parse(name, raw, Integer::valueOf, violations);
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            violations.add(new Violation(name, PlatformErrorCodes.INVALID_VALUE,
                name + " must be between " + min + " and " + max));
            return fallback;
        }
        return value;
    }
}

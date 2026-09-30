package com.jabiz.runtime.audit;

import com.jabiz.context.DataPeriod;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.security.RevealRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * the audit trail with values; {@code GET /api/audit/reveals}: the plain-text displays of masked fields (10 section
 * 13.1). Needs permission {@value #READ}; an actor limited to a data period sees what was recorded within it. Malformed filters are reported together (400).
 */
@RestController
@RequestMapping("/api/audit")
class AuditController {

    static final String READ = "audit.read";

    private final AuditService audit;
    private final RevealRecorder reveals;

    AuditController(AuditService audit, RevealRecorder reveals) {
        this.audit = audit;
        this.reveals = reveals;
    }

    /**
     * {@code GET /api/audit/reveals}: every display of masked fields in plain text, newest first
     * (docs/design/10-security.md section 13.1).
     */
    @GetMapping("/reveals")
    Mono<RevealRecorder.RevealPage> reveals(
        @RequestParam(required = false) String actorId,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String entityId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String offset,
        @RequestParam(required = false) String limit
    ) {
        return RequestContexts.current().flatMap(request -> {
            if (!request.hasPermission(READ)) {
                return Mono.error(new PermissionDeniedException(READ, "Reading the audit trail needs permission "
                    + READ));
            }
            List<Violation> violations = new ArrayList<>();
            RevealRecorder.RevealQuery query = new RevealRecorder.RevealQuery(blankToNull(actorId),
                blankToNull(entityType), blankToNull(entityId),
                later(parse("from", from, Instant::parse, violations), request.dataPeriod()),
                earlier(parse("to", to, Instant::parse, violations), request.dataPeriod()),
                bounded("offset", offset, 0, 0, Integer.MAX_VALUE, violations),
                bounded("limit", limit, AuditQuery.DEFAULT_LIMIT, 1, AuditQuery.MAX_LIMIT, violations));
            if (!violations.isEmpty()) {
                return Mono.error(new ValidationException(violations));
            }
            return reveals.find(query);
        });
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
                later(parse("from", from, Instant::parse, violations), request.dataPeriod()),
                earlier(parse("to", to, Instant::parse, violations), request.dataPeriod()),
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
        @RequestParam(required = false) boolean withApprovals,
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
                later(parse("from", from, Instant::parse, violations), request.dataPeriod()),
                earlier(parse("to", to, Instant::parse, violations), request.dataPeriod()),
                blankToNull(processName), blankToNull(field), withApprovals,
                bounded("offset", offset, 0, 0, Integer.MAX_VALUE, violations),
                bounded("limit", limit, AuditQuery.DEFAULT_LIMIT, 1, AuditQuery.MAX_LIMIT, violations));
            if (withApprovals && (query.entityType() == null || query.entityId() == null)) {
                violations.add(new Violation("withApprovals", PlatformErrorCodes.INVALID_VALUE,
                    "withApprovals needs entityType and entityId"));
            }
            if (!violations.isEmpty()) {
                return Mono.error(new ValidationException(violations));
            }
            return audit.records(query);
        });
    }

    /** {@code GET /api/audit/records/{recordNo}}: one record of the audit trail. */
    @GetMapping("/records/{recordNo}")
    Mono<AuditService.AuditRecordEntry> record(@PathVariable long recordNo) {
        return RequestContexts.current().flatMap(request -> {
            if (!request.hasPermission(READ)) {
                return Mono.error(new PermissionDeniedException(READ, "Reading the audit trail needs permission "
                    + READ));
            }
            return audit.record(recordNo)
                .filter(entry -> request.dataPeriod() == null || request.dataPeriod().contains(entry.recordedTime()))
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(
                "Audit record " + recordNo + " not found")));
        });
    }

    /**
     * The start of a time filter within the actor's data period (docs/design/10-security.md section 13.2): the trail
     * is read by its recording time.
     */
    static Instant later(Instant from, DataPeriod period) {
        if (period == null || period.from() == null) {
            return from;
        }
        return from == null || from.isBefore(period.from()) ? period.from() : from;
    }

    /** The (exclusive) end of a time filter within the actor's data period. */
    static Instant earlier(Instant to, DataPeriod period) {
        if (period == null || period.to() == null) {
            return to;
        }
        return to == null || to.isAfter(period.to()) ? period.to() : to;
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

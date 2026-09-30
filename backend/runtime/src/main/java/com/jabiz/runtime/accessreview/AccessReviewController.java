package com.jabiz.runtime.accessreview;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.security.SecurityPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a reviewer looks at before signing (docs/design/10-security.md section 13.3): {@code GET
 * /api/security/access-reviews} the signed reviews, {@code .../changes?from&to} the security changes of a period from
 * the audit trail, {@code .../conflicts} the segregation-of-duties conflicts now. The access report itself is an
 * issued report ({@value AccessReviews#ACCESS_TEMPLATE}). Needs
 * {@value SecurityPermissions#ACCESS_REVIEW_READ}.
 */
@RestController
@RequestMapping("/api/security/access-reviews")
class AccessReviewController {

    /** Most reviews listed. */
    static final int MAX_REVIEWS = 200;

    private final AccessReviews reviews;

    AccessReviewController(AccessReviews reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    Mono<List<AccessReviews.Review>> list() {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, SecurityPermissions.ACCESS_REVIEW_READ, "Reading access reviews");
            return reviews.list(MAX_REVIEWS);
        });
    }

    @GetMapping("/changes")
    Mono<AccessReviews.Changes> changes(@RequestParam(required = false) String from,
        @RequestParam(required = false) String to) {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, SecurityPermissions.ACCESS_REVIEW_READ, "Reading access reviews");
            List<Violation> violations = new ArrayList<>();
            Instant start = instant("from", from, violations);
            Instant end = instant("to", to, violations);
            if (start != null && end != null && !start.isBefore(end)) {
                violations.add(new Violation("to", PlatformErrorCodes.INVALID_VALUE, "to must be after from"));
            }
            if (!violations.isEmpty()) {
                return Mono.error(new ValidationException(violations));
            }
            return reviews.changes(start, end);
        });
    }

    @GetMapping("/conflicts")
    Mono<AccessReviews.Conflicts> conflicts() {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, SecurityPermissions.ACCESS_REVIEW_READ, "Reading access reviews");
            return reviews.conflicts();
        });
    }

    private static Instant instant(String name, String raw, List<Violation> violations) {
        if (raw == null || raw.isBlank()) {
            violations.add(new Violation(name, PlatformErrorCodes.REQUIRED, name + " is required"));
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            violations.add(new Violation(name, PlatformErrorCodes.INVALID_VALUE, "'" + raw + "' is not a time"));
            return null;
        }
    }
}

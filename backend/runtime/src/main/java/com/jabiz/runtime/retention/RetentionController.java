package com.jabiz.runtime.retention;

import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** {@code GET /api/retention}: the policies and what is past its retention (21 section 3.4). */
@RestController
@RequestMapping("/api/retention")
class RetentionController {

    private final RetentionReport report;

    RetentionController(RetentionReport report) {
        this.report = report;
    }

    @GetMapping
    Mono<RetentionReport.Report> report() {
        return RequestContexts.current().flatMap(request -> request.hasPermission(RetentionPermissions.READ)
            ? report.report()
            : Mono.error(new PermissionDeniedException(RetentionPermissions.READ,
                "Reading the retention report needs permission " + RetentionPermissions.READ)));
    }
}

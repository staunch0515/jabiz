package com.jabiz.runtime.sod;

import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The conflict report (docs/design/18-numbering-approvals-tasks.md section 4.4): every user who holds a permission of
 * each group of an SoD rule, with the permissions and the roles they come from. Holders of {@code *} are always
 * listed ({@code wildcard}), whatever else they hold. Needs {@value ApprovalPermissions#SOD_READ}.
 */
@RestController
@RequestMapping("/api/sod")
class SodController {

    private final SodService sod;

    SodController(SodService sod) {
        this.sod = sod;
    }

    @GetMapping("/conflicts")
    Mono<List<SodService.Conflict>> conflicts() {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, ApprovalPermissions.SOD_READ, "Reading the SoD conflict report");
            return sod.conflicts();
        });
    }
}

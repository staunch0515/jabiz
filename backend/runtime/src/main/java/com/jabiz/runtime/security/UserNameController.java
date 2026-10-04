package com.jabiz.runtime.security;

import com.jabiz.runtime.context.RequestContexts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * The display names of users by id (docs/design/10-security.md section 14), for pages that show who prepared,
 * approved or changed what they display. Only authentication is needed, as for {@code /api/auth/me}: any signed-in
 * user may resolve any user id (ids are not guessable) and gets the display name alone, nothing for an id that is no
 * user's of their tenant.
 */
@RestController
class UserNameController {

    static final String PATH = "/api/users/names";

    record UserNamesResponse(Map<String, String> names) {}

    private final UserNames userNames;

    UserNameController(UserNames userNames) {
        this.userNames = userNames;
    }

    @GetMapping(PATH)
    Mono<UserNamesResponse> userNames(@RequestParam(name = "ids", required = false) List<String> ids) {
        return RequestContexts.current().flatMap(request -> userNames.of(ids == null ? List.of() : ids,
            request.tenantId())).map(UserNamesResponse::new);
    }
}

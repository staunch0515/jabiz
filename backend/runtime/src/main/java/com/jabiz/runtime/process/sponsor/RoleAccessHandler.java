package com.jabiz.process.sponsor;

import com.jabiz.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Checks that the authenticated user holds the required role and that the role is currently in
 * effect (its effective start time has been reached).
 *
 * The check requires a reactive role store, which the application does not provide yet. Until
 * one is wired in, the step fails explicitly instead of granting access.
 */
@Component
public class RoleAccessHandler implements StepHandler<RoleAccessMetadata, LoginContext> {

    @Override
    public Mono<Void> execute(RoleAccessMetadata metadata, LoginContext ctx) {
        return Mono.error(new UnsupportedOperationException(
            "Role checking requires a role store, which is not configured"));
    }
}

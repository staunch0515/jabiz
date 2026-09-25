package com.jabiz.runtime.process.sponsor;

import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Verifies the submitted credentials and loads the user. On success it stores the
 * {@link AuthenticatedUser} in the context and resets the failed-login counter; on failure it
 * increments the counter and signals an error, which aborts the process.
 *
 * The credential check requires a reactive user store, which the application does not provide
 * yet. Until one is wired in, the step fails explicitly instead of letting anyone through.
 */
@Component
public class AuthenticationHandler implements StepHandler<AuthenticationMetadata, LoginContext> {

    @Override
    public Mono<Void> execute(AuthenticationMetadata metadata, LoginContext ctx) {
        return Mono.error(new UnsupportedOperationException(
            "Authentication requires a user store, which is not configured"));
    }
}

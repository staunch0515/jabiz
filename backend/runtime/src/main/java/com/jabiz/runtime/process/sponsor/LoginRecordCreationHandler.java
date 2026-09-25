package com.jabiz.runtime.process.sponsor;

import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Creates the login audit record and stores its id in the context as part of the process
 * output. The record is written together with the process sequence id so that it is covered by
 * change tracing.
 *
 * Writing the record requires a login-record entity definition, which the application does not
 * declare yet. Until one exists, the step fails explicitly.
 */
@Component
public class LoginRecordCreationHandler implements StepHandler<LoginRecordMetadata, LoginContext> {

    @Override
    public Mono<Void> execute(LoginRecordMetadata metadata, LoginContext ctx) {
        return Mono.error(new UnsupportedOperationException(
            "Login record creation requires a login-record entity, which is not defined"));
    }
}

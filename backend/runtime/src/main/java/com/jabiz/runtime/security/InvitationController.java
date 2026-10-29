package com.jabiz.runtime.security;

import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.security.Sensitive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Accepting an invitation over HTTP (docs/design/10-security.md section 15.14; decision D63): anonymous, its link's
 * token being the credential, and rate limited as the other mailed links. 204 once the password is set; 422
 * {@code TOKEN_INVALID} for any link that is not good. Invitations are sent by the processes {@code SEC_USER_INVITE}
 * and {@code SEC_USER_CREATE}, through the process API.
 */
@RestController
class InvitationController {

    static final String CONFIRM = "/api/auth/invitation/confirm";

    record InvitationConfirm(@Sensitive String token, @Sensitive String password) {
        @Override
        public String toString() {
            return "InvitationConfirm[***]";
        }
    }

    private final ProcessExecutor processes;
    private final SignUpRateLimits limits;
    private final ClientAddresses clients;
    private final ProcessDefinition<InvitationProcesses.AcceptInput, SignUpProcesses.Done, AccountContext> accept;

    InvitationController(ProcessExecutor processes, SignUpRateLimits limits, ClientAddresses clients,
        ProcessDefinition<InvitationProcesses.AcceptInput, SignUpProcesses.Done, AccountContext> accept) {
        this.processes = processes;
        this.limits = limits;
        this.clients = clients;
        this.accept = accept;
    }

    /** 204; the user signs in with the new password afterwards (no session is opened here). */
    @PostMapping(CONFIRM)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<ResponseEntity<Void>> confirmInvitation(@RequestBody(required = false) InvitationConfirm request,
        ServerHttpRequest http) {
        return Mono.defer(() -> {
            limits.client(SignUpRateLimits.Kind.LINK, clients.clientOf(http));
            return processes.executeAtEntry(accept, new InvitationProcesses.AcceptInput(
                    request == null ? null : request.token(), request == null ? null : request.password(),
                    AuthController.source(clients, http, null)))
                .thenReturn(ResponseEntity.noContent().<Void>build());
        });
    }
}

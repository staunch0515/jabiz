package com.jabiz.runtime.security;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.secret.SecretSealer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * Revokes a kept token at its provider ({@link IdentityTokens#REVOKE}; decision D67 item 5): opens the sealed token
 * and calls the provider's revocation endpoint. Puts the outcome into the context, never fails the process for the
 * provider's sake: {@value #REVOKED}; {@value #FAILED} when the provider refused or could not be reached, or is no
 * longer configured (tried again later); {@value #SKIPPED} when there is no token or its link exists again. The
 * token is never logged.
 */
@Component
public class RevokeIdentityToken<C extends ProcessContext> implements StepHandler<NoMetadata, C> {

    public static final String REVOKED = "REVOKED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    private static final Logger log = LoggerFactory.getLogger(RevokeIdentityToken.class);

    public static <C extends ProcessContext> StepSpec<NoMetadata, C> spec() {
        return StepSpec.of(RevokeIdentityToken.class, NoMetadata.INSTANCE);
    }

    private final OidcProviders providers;
    private final OidcClient client;
    private final SecretSealer sealer;

    public RevokeIdentityToken(OidcProviders providers, OidcClient client, SecretSealer sealer) {
        this.providers = providers;
        this.client = client;
        this.sealer = sealer;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, C ctx) {
        return Mono.defer(() -> {
            List<EntityInstance> tokens = IdentityTokens.list(ctx, IdentityTokens.TOKENS);
            if (tokens.isEmpty() || !IdentityTokens.list(ctx, IdentityTokens.LINKS).isEmpty()) {
                ctx.put(IdentityTokens.OUTCOME, SKIPPED);
                return Mono.empty();
            }
            EntityInstance token = tokens.getFirst();
            String providerId = token.get("provider");
            OidcProvider provider = providers.find(providerId).orElse(null);
            if (provider == null) {
                log.warn("Cannot revoke a token of identity provider {}: it is no longer configured", providerId);
                ctx.put(IdentityTokens.OUTCOME, FAILED);
                return Mono.empty();
            }
            UUID identityId = com.jabiz.runtime.storage.Rows.uuid(token.get("identityId"));
            // Opening reads no random source, but the key derivation is CPU work: not on an event loop.
            return Mono.fromCallable(() -> sealer.open(token.get("sealedToken"), IdentityTokens.context(identityId)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(refreshToken -> client.revoke(provider, refreshToken))
                .then(Mono.fromRunnable(() -> ctx.put(IdentityTokens.OUTCOME, REVOKED)))
                .onErrorResume(e -> {
                    log.warn("Revoking a token at identity provider {} failed: {}", provider.id(),
                        e.getClass().getSimpleName() + (e instanceof OidcClient.ProviderException ? " "
                            + e.getMessage() : ""));
                    ctx.put(IdentityTokens.OUTCOME, FAILED);
                    return Mono.empty();
                })
                .then();
        });
    }
}

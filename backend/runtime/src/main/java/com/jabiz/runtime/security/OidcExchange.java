package com.jabiz.runtime.security;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * The second half of every round trip through an identity provider (docs/design/10-security.md section 12), for
 * signing in and for linking alike: exchanges the code with the callback the state's entry started with (whatever the
 * request says), then checks the ID token the token endpoint gave (never one sent along by the browser).
 */
@Component
class OidcExchange {

    private final OidcClient client;
    private final Clock clock;

    OidcExchange(OidcClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * What the round trip gave: the identity, and the refresh token to keep where the provider's tokens are revoked
     * when the link goes (Apple, decision D67 item 5; null for every other provider).
     */
    record Exchanged(IdTokenValidator.Identity identity, String refreshToken) {
        @Override
        public String toString() {
            return "Exchanged[" + identity + ", ***]";
        }
    }

    /**
     * The identity the provider vouches for; its name, when the token has none, as the provider posted it with its
     * answer (Apple, the first time only: decision D67 item 3). Fails with {@link IllegalStateException} when the provider no longer has
     * a callback for the state's entry, {@link OidcClient.ProviderException} or {@link
     * IdTokenValidator.InvalidIdTokenException} otherwise.
     */
    Mono<Exchanged> identity(OidcProvider provider, String code, OidcStateStore.Pending pending) {
        return Mono.defer(() -> {
            String redirectUri = provider.providerRedirectUri(pending.entry());
            if (redirectUri == null) {
                return Mono.error(new IllegalStateException("The provider no longer has a callback for entry "
                    + pending.entry()));
            }
            return client.tokens(provider, code, pending.codeVerifier(), redirectUri)
                .flatMap(tokens -> verify(provider, tokens.idToken(), pending.nonceHash())
                    .map(identity -> new Exchanged(identity.name() == null && pending.providedName() != null
                        ? identity.withName(pending.providedName()) : identity,
                        provider.isApple() ? tokens.refreshToken() : null)));
        });
    }

    /** Checks the ID token; reads the provider's keys again once when the token names a key not seen yet. */
    private Mono<IdTokenValidator.Identity> verify(OidcProvider provider, String idToken, String nonceHash) {
        SignedJWT token = IdTokenValidator.parse(idToken);
        if (provider.acceptsHs256() && IdTokenValidator.hs256(token)) {
            // Keyed with the client secret (LINE's web sign-in, decision D61 item 2): no keys to read.
            return Mono.fromCallable(() -> IdTokenValidator.validate(token, new com.nimbusds.jose.jwk.JWKSet(),
                provider.clientSecret(), provider.issuer(), provider.clientId(), nonceHash, clock.instant()));
        }
        return client.keys(provider, false)
            .map(keys -> IdTokenValidator.validate(token, keys, provider.issuer(), provider.clientId(), nonceHash,
                clock.instant()))
            .onErrorResume(IdTokenValidator.UnknownKeyException.class, unknown -> client.keys(provider, true)
                .map(keys -> IdTokenValidator.validate(token, keys, provider.issuer(), provider.clientId(),
                    nonceHash, clock.instant())));
    }
}

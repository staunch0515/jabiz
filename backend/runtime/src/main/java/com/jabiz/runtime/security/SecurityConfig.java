package com.jabiz.runtime.security;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.context.ActorAuthentication;
import com.jabiz.runtime.context.ActorResolver;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.MfaSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Authentication and the coarse access rule of the HTTP API (docs/design/10-security.md; decision D12).
 *
 * <p>Requests authenticate with a bearer access token ({@link JwtService}); in the {@code dev} profile with
 * {@code jabiz.dev.actor-headers=true}, a request without a token may name its actor in headers instead. Every
 * {@code /api/**} path except signing in, refreshing, signing out and the public read API ({@code /api/public/**})
 * requires an authenticated actor (401 otherwise);
 * which actor may do what is checked by each entry point against the permissions the metadata declares (403).
 * Stateless: no session, no CSRF token (no cookies are used), no saved requests.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    static final String BEARER = "Bearer ";

    /** The session endpoints, and the second step of a sign-in (which carries a challenge), open to everyone. */
    static final ServerWebExchangeMatcher PUBLIC = new OrServerWebExchangeMatcher(
        ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, AuthController.LOGIN, AuthController.REFRESH,
            AuthController.LOGOUT, MfaController.CHALLENGE + "/**"),
        // Signing in through an identity provider (docs/design/10-security.md section 12).
        ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, OidcController.BASE + "/providers"),
        ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, OidcController.BASE + "/*/start",
            OidcController.BASE + "/callback"));

    /**
     * Public read access (docs/design/15-public-access.md section 6; decision D17), open to everyone and never
     * authenticated: credentials sent along are ignored, so an expired token cannot break a public page. Which methods
     * and whether the switch is on is decided by {@code PublicAccessWebFilter} before this chain runs.
     */
    static final ServerWebExchangeMatcher PUBLIC_READ = ServerWebExchangeMatchers.pathMatchers("/api/public",
        "/api/public/**");

    @Bean
    JwtService jwtService(Environment environment, Clock clock,
        @Value("${jabiz.security.jwt.secret:}") String secret,
        @Value("${jabiz.security.jwt.access-token-ttl:PT15M}") Duration ttl) {
        return new JwtService(secret(secret, environment.acceptsProfiles(Profiles.of("dev"))), ttl, clock);
    }

    /**
     * The HS256 key, Base64 encoded. Without one the application does not start (default deny); only the dev profile
     * makes up a random key, which invalidates all tokens on restart.
     */
    static byte[] secret(String configured, boolean development) {
        return key(configured, "jabiz.security.jwt.secret", "JABIZ_JWT_SECRET", JwtService.MIN_SECRET_BYTES,
            development);
    }

    /** A Base64 key of at least {@code minBytes}; missing is fatal except in development, which makes one up. */
    static byte[] key(String configured, String property, String variable, int minBytes, boolean development) {
        if (configured == null || configured.isBlank()) {
            if (!development) {
                throw new IllegalStateException(property + " (environment variable " + variable + ") "
                    + "is required: a Base64 key of at least " + minBytes + " bytes");
            }
            log.warn("No {}: using a random key that does not survive a restart (dev only)", property);
            byte[] random = new byte[minBytes];
            new SecureRandom().nextBytes(random);
            return random;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " is not valid Base64");
        }
        if (key.length < minBytes) {
            throw new IllegalStateException(property + " must decode to at least " + minBytes + " bytes");
        }
        return key;
    }

    @Bean
    RefreshTokenStore refreshTokenStore(StorageAdapterRegistry storages, Clock clock, JwtService tokens,
        MfaSettings mfa, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.security.refresh-token-ttl:PT8H}") Duration ttl) {
        // A session idle for longer than its access token plus the idle timeout cannot be refreshed (section 11).
        return new RefreshTokenStore(() -> storages.getEngine(poolRef), ttl, tokens.ttl().plus(mfa.idleTimeout()),
            clock);
    }

    @Bean
    OidcStateStore oidcStateStore(StorageAdapterRegistry storages, Clock clock,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return new OidcStateStore(() -> storages.getEngine(poolRef), clock);
    }

    /**
     * Calls to the identity providers. A plain client: the sign-in around it is observed as {@code jabiz.auth.oidc},
     * and the calls carry no trace headers to the provider.
     */
    @Bean
    OidcClient oidcClient(Clock clock) {
        return new OidcClient(clock);
    }

    @Bean
    MfaSettings mfaSettings(@Value("${jabiz.security.mfa.issuer:jabiz}") String issuer,
        @Value("${jabiz.security.mfa.challenge-ttl:PT5M}") Duration challengeTtl,
        @Value("${jabiz.security.mfa.step-up-max-age:PT10M}") Duration stepUpMaxAge,
        @Value("${jabiz.security.mfa.administration:true}") boolean administration,
        @Value("${jabiz.security.session.idle-timeout:PT15M}") Duration idleTimeout) {
        return new MfaSettings(issuer, challengeTtl, stepUpMaxAge, administration, idleTimeout);
    }

    /**
     * Encrypts TOTP secrets (docs/design/10-security.md section 9). The key comes from {@code JABIZ_MFA_KEY}; without
     * one the application does not start, except in the dev profile, which makes one up (enrolled users then cannot
     * verify after a restart).
     */
    @Bean
    MfaSecretCipher mfaSecretCipher(Environment environment, @Value("${jabiz.security.mfa.key:}") String key) {
        return new MfaSecretCipher(key(key, "jabiz.security.mfa.key", "JABIZ_MFA_KEY",
            MfaSecretCipher.MIN_KEY_BYTES, environment.acceptsProfiles(Profiles.of("dev"))));
    }

    @Bean
    PasswordHasher passwordHasher(@Value("${jabiz.security.password.bcrypt-strength:12}") int strength,
        @Value("${jabiz.security.password.min-length:10}") int minLength) {
        return new PasswordHasher(strength, minLength);
    }

    @Bean
    LoginAttemptPolicy loginAttemptPolicy(@Value("${jabiz.security.login.max-failures:5}") int maxFailures,
        @Value("${jabiz.security.login.lock-duration:PT15M}") Duration lockDuration) {
        return new LoginAttemptPolicy(maxFailures, lockDuration);
    }

    @Bean
    SensitiveDataMasker sensitiveDataMasker(JsonMapper json, EntityDefinitionRegistry entities,
        ObjectProvider<ProcessDefinition<?, ?, ?>> processes,
        @Value("${jabiz.security.sensitive-name-fragments:password,passwd,secret,token,credential}")
        List<String> fragments) {
        return new SensitiveDataMasker(json, entities, processes.orderedStream().toList(), fragments);
    }

    /** Verifies access tokens; development header actors arrive authenticated already. */
    @Bean
    ReactiveAuthenticationManager jabizAuthenticationManager(JwtService tokens) {
        return authentication -> {
            if (authentication instanceof ActorAuthentication authenticated) {
                return Mono.just(authenticated);
            }
            if (authentication instanceof BearerToken bearer) {
                return Mono.fromCallable(() -> (Authentication) new ActorAuthentication(tokens.verify(bearer.token())))
                    .onErrorMap(JwtService.InvalidTokenException.class,
                        e -> new BadCredentialsException(e.getMessage()));
            }
            return Mono.empty();
        };
    }

    @Bean
    SecurityWebFilterChain jabizSecurityFilterChain(ServerHttpSecurity http,
        ReactiveAuthenticationManager jabizAuthenticationManager, ActorResolver devActors, MessageCatalog messages,
        JsonMapper json) {
        ProblemResponses problems = new ProblemResponses(messages, json);
        AuthenticationWebFilter authentication = new AuthenticationWebFilter(jabizAuthenticationManager);
        authentication.setServerAuthenticationConverter(converter(devActors));
        authentication.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(problems));
        authentication.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        // Signing in, refreshing and signing out need no access token, and a stale one sent along must not stop them.
        authentication.setRequiresAuthenticationMatcher(new NegatedServerWebExchangeMatcher(
            new OrServerWebExchangeMatcher(PUBLIC, PUBLIC_READ)));

        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .logout(ServerHttpSecurity.LogoutSpec::disable)
            .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .exceptionHandling(handling -> handling.authenticationEntryPoint(problems).accessDeniedHandler(problems))
            .addFilterAt(authentication, SecurityWebFiltersOrder.AUTHENTICATION)
            .authorizeExchange(exchanges -> exchanges
                .matchers(PUBLIC).permitAll()
                .matchers(PUBLIC_READ).permitAll()
                .pathMatchers("/api", "/api/**").authenticated()
                // The single-page application and health: public.
                .anyExchange().permitAll())
            .build();
    }

    /**
     * {@code Authorization: Bearer <token>} becomes a token to verify; without that header, a development actor named
     * in headers (only when enabled) is taken as authenticated. Anything else is anonymous. An Authorization header
     * of another scheme, or malformed development headers, are refused (401).
     */
    static ServerAuthenticationConverter converter(ActorResolver devActors) {
        return exchange -> Mono.defer(() -> {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
            if (authorization != null) {
                if (!authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
                    || authorization.length() == BEARER.length()) {
                    return Mono.error(new BadCredentialsException("Only bearer access tokens are accepted"));
                }
                return Mono.just(new BearerToken(authorization.substring(BEARER.length()).trim()));
            }
            Actor actor;
            try {
                actor = devActors.resolve(exchange.getRequest());
            } catch (IllegalArgumentException e) {
                return Mono.error(new BadCredentialsException(e.getMessage()));
            }
            return actor == null ? Mono.empty() : Mono.just(new ActorAuthentication(actor));
        });
    }

    /** An access token that has not been verified yet. */
    static final class BearerToken extends UsernamePasswordAuthenticationToken {
        BearerToken(String token) {
            super(null, token);
        }

        String token() {
            return (String) getCredentials();
        }

        @Override
        public String toString() {
            return "BearerToken[***]";
        }
    }
}

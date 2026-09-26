package com.jabiz.runtime.security;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.context.ActorAuthentication;
import com.jabiz.runtime.context.ActorResolver;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.security.LoginAttemptPolicy;
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
 * {@code /api/**} path except signing in, refreshing and signing out requires an authenticated actor (401 otherwise);
 * which actor may do what is checked by each entry point against the permissions the metadata declares (403).
 * Stateless: no session, no CSRF token (no cookies are used), no saved requests.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    static final String BEARER = "Bearer ";

    /** The session endpoints, open to everyone. */
    static final ServerWebExchangeMatcher PUBLIC = ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST,
        AuthController.LOGIN, AuthController.REFRESH, AuthController.LOGOUT);

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
        if (configured == null || configured.isBlank()) {
            if (!development) {
                throw new IllegalStateException("jabiz.security.jwt.secret (environment variable JABIZ_JWT_SECRET) "
                    + "is required: a Base64 key of at least " + JwtService.MIN_SECRET_BYTES + " bytes");
            }
            log.warn("No jabiz.security.jwt.secret: using a random key; tokens do not survive a restart (dev only)");
            byte[] random = new byte[JwtService.MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return random;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("jabiz.security.jwt.secret is not valid Base64");
        }
        if (key.length < JwtService.MIN_SECRET_BYTES) {
            throw new IllegalStateException("jabiz.security.jwt.secret must decode to at least "
                + JwtService.MIN_SECRET_BYTES + " bytes");
        }
        return key;
    }

    @Bean
    RefreshTokenStore refreshTokenStore(StorageAdapterRegistry storages, Clock clock,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.security.refresh-token-ttl:PT8H}") Duration ttl) {
        return new RefreshTokenStore(() -> storages.getEngine(poolRef), ttl, clock);
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
        authentication.setRequiresAuthenticationMatcher(new NegatedServerWebExchangeMatcher(PUBLIC));

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

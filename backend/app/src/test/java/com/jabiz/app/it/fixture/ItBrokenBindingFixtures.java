package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * Values that cannot be bound as the type of their field (ROADMAP 16b fix), found at startup rather than at every
 * request: a fixed scope value that is no UUID for a reference to the platform user, and a sign-in guard loading by a
 * field that cannot hold a user id. Only the {@code broken-binding} profile of {@code PlatformCheckIT} loads them.
 */
@Configuration
@Profile("broken-binding")
class ItBrokenBindingFixtures {

    @Bean
    DatasetDefinition itBrokenFixedScope(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
        return DatasetDefinition.define("urn:jabiz:dataset:it:broken:fixed-uuid", d -> d
            .targetEntityType("ItAccountMark")
            .scope(s -> s.fixed("userRef", "nobody"))
            .permissions("it.read", "it.write")
            .storage(s -> s.connectionPoolRef(pool)));
    }

    @Bean
    SignInGuard itBrokenLoadGuard() {
        return new SignInGuard() {
            @Override
            public List<SignInLoad> loads() {
                return List.of(SignInLoad.of("byVersion", ItBindingFixtures.MARK_DATASET, "rowVersion"));
            }

            @Override
            public SignInDecision check(SignInAttempt attempt) {
                return SignInDecision.ALLOW;
            }
        };
    }
}

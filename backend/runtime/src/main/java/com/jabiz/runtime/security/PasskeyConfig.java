package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** The beans of passkeys and their startup check (decision D71; docs/design/10-security.md section 15.16). */
@Configuration
class PasskeyConfig {

    static final String CATEGORY = "SECURITY";
    static final String PROPERTY = "jabiz.security.passkeys";

    @Bean
    PasskeyVerifier passkeyVerifier(PasskeyProperties properties) {
        return new PasskeyVerifier(properties);
    }

    @Bean
    PasskeyChallenges passkeyChallenges(StorageAdapterRegistry storages, Clock clock, PasskeyProperties properties,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return new PasskeyChallenges(() -> storages.getEngine(poolRef), clock, properties.challengeTtl());
    }

    /**
     * Reported at once: the settings' problems ({@link PasskeyProperties#problems}), and entries that take passkeys
     * while no relying party is configured.
     */
    @Bean
    PlatformCheck passkeyChecks(PasskeyProperties properties, SignInEntries entries) {
        return () -> {
            List<CheckProblem> problems = new ArrayList<>();
            properties.problems().forEach(problem -> problems.add(CheckProblem.error(CATEGORY, PROPERTY, problem)));
            for (SignInEntries.Entry entry : entries.all()) {
                if (entry.passkeys() && !properties.configured()) {
                    problems.add(CheckProblem.error(CATEGORY, SignInEntries.PROPERTY + "." + entry.name() + ".passkeys",
                        "passkeys are on, but " + PROPERTY + ".rp-id and .origins are not set"));
                }
            }
            return problems;
        };
    }
}

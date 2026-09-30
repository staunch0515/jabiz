package com.jabiz.runtime.integrity;

import com.jabiz.integrity.IntegrityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Settings of the integrity seals (docs/design/21-audit-retention.md section 2.4) and the key that signs them.
 *
 * @param maxRows     most rows one seal takes; the rest wait for the next run
 * @param maxProblems most problems a verification keeps in full (all are counted)
 * @param sealCron    when the job {@code jabiz.integrity-seal} runs (Spring cron, UTC)
 * @param verifyCron  when the job {@code jabiz.integrity-verify} runs
 */
public record IntegritySettings(int maxRows, int maxProblems, String sealCron, String verifyCron) {

    public IntegritySettings {
        if (maxRows < 1 || maxProblems < 1) {
            throw new IllegalArgumentException("jabiz.integrity.seal.max-rows and max-problems must be positive");
        }
    }

    @Configuration
    static class Beans {

        private static final Logger log = LoggerFactory.getLogger(IntegritySettings.class);

        /** Known to everyone, hence only for the dev profile: seals made with it prove nothing. */
        static final String DEVELOPMENT_KEY = "jabiz-development-integrity-key-not-a-secret";

        @Bean
        IntegritySettings integritySettings(
            @Value("${jabiz.integrity.seal.max-rows:100000}") int maxRows,
            @Value("${jabiz.integrity.verify.max-problems:1000}") int maxProblems,
            @Value("${jabiz.integrity.seal.cron:0 */5 * * * *}") String sealCron,
            @Value("${jabiz.integrity.verify.cron:0 30 3 * * *}") String verifyCron) {
            return new IntegritySettings(maxRows, maxProblems, sealCron, verifyCron);
        }

        /** The environment variable is read directly too, so that applications need no property of their own. */
        @Bean
        IntegrityKey integrityKey(Environment environment,
            @Value("${jabiz.integrity.key:${JABIZ_INTEGRITY_KEY:}}") String configured) {
            return key(configured, environment.acceptsProfiles(Profiles.of("dev")));
        }

        /**
         * The HMAC key, Base64 encoded. Without one the application does not start (default deny); only the dev
         * profile falls back to a fixed, public key.
         */
        static IntegrityKey key(String configured, boolean development) {
            if (configured == null || configured.isBlank()) {
                if (!development) {
                    throw new IllegalStateException("jabiz.integrity.key (environment variable JABIZ_INTEGRITY_KEY) "
                        + "is required: a Base64 key of at least " + IntegrityKey.MIN_BYTES + " bytes");
                }
                log.warn("No jabiz.integrity.key: sealing with a public development key (dev only)");
                return new IntegrityKey(DEVELOPMENT_KEY.getBytes(StandardCharsets.UTF_8));
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(configured.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("jabiz.integrity.key is not valid Base64");
            }
            if (key.length < IntegrityKey.MIN_BYTES) {
                throw new IllegalStateException("jabiz.integrity.key must decode to at least " + IntegrityKey.MIN_BYTES
                    + " bytes");
            }
            return new IntegrityKey(key);
        }
    }
}

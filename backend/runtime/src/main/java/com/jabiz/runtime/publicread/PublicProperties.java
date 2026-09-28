package com.jabiz.runtime.publicread;

import com.jabiz.query.template.PublicReadChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of public read access ({@code jabiz.public.*}, docs/design/15-public-access.md; decision D17).
 *
 * @param enabled             master switch; off (the default), every {@code /api/public/**} request is answered 404,
 *                            while the startup checks of public datasets and templates still run
 * @param maxTimeout          longest a public template may run
 * @param maxLimit            most rows one public page returns
 * @param defaultCacheSeconds {@code max-age} of public template responses that declare no {@code cacheSeconds}
 * @param fileCacheSeconds    {@code max-age} of public file responses
 * @param fileDecisionTtl     how long the decision whether a file is public is remembered
 * @param fileDecisionEntries most decisions remembered (least recently used dropped first)
 * @param rateLimit           per-client throttling
 */
@ConfigurationProperties("jabiz.public")
public record PublicProperties(Boolean enabled, Duration maxTimeout, Integer maxLimit, Integer defaultCacheSeconds,
    Integer fileCacheSeconds, Duration fileDecisionTtl, Integer fileDecisionEntries, RateLimit rateLimit) {

    public PublicProperties {
        enabled = enabled != null && enabled;
        maxTimeout = maxTimeout == null ? Duration.ofSeconds(2) : maxTimeout;
        maxLimit = maxLimit == null ? 100 : maxLimit;
        defaultCacheSeconds = defaultCacheSeconds == null ? 60 : defaultCacheSeconds;
        fileCacheSeconds = fileCacheSeconds == null ? 300 : fileCacheSeconds;
        fileDecisionTtl = fileDecisionTtl == null ? Duration.ofSeconds(60) : fileDecisionTtl;
        fileDecisionEntries = fileDecisionEntries == null ? 10_000 : fileDecisionEntries;
        rateLimit = rateLimit == null ? new RateLimit(null, null) : rateLimit;
    }

    /** Whether public reads are served at all. */
    public boolean on() {
        return enabled;
    }

    public PublicReadChecks.Limits limits() {
        return new PublicReadChecks.Limits(maxTimeout, maxLimit);
    }

    /**
     * @param perMinute  requests one client address may make per minute
     * @param maxClients client addresses tracked at most (least recently seen dropped first), bounding memory
     */
    public record RateLimit(Integer perMinute, Integer maxClients) {
        public RateLimit {
            perMinute = perMinute == null ? 300 : perMinute;
            maxClients = maxClients == null ? 100_000 : maxClients;
        }
    }
}

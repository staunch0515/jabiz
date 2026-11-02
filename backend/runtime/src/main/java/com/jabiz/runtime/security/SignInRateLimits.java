package com.jabiz.runtime.security;

import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.web.ClientAddresses;
import com.jabiz.runtime.web.TokenBucketLimiter;
import io.micrometer.common.KeyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The limits of the sign-in endpoints (docs/design/10-security.md section 4.1; decision D47 item 1), checked before
 * anything is read. Per kind of request, the buckets that count it:
 * <ul>
 *   <li><b>address and name</b> ({@link Kind#PASSWORD}, {@link Kind#SECOND_FACTOR}): the client address together with
 *       the name the request is about - the user name typed in (trimmed, lower case: names the e-mail lookup takes
 *       alike count alike), or the user the second factor is for. {@code .per-user-address} (20) per period,
 *       burst {@code .user-burst} (10). Guessing one account from one address stops early, while one address serving
 *       many people (an office behind one NAT) keeps signing them in.</li>
 *   <li><b>address</b> (every kind): the client address ({@link ClientAddresses}: forwarding headers count only
 *       through trusted proxies; an IPv6 address counts as its /64). {@code .per-address} (300) per period, burst
 *       {@code .burst} (100). Refreshing and the identity provider's callback name nobody before they are verified,
 *       so this is their only per-client bucket.</li>
 *   <li><b>IPv6 network</b> (every kind, IPv6 only): the address's /48 (a site), {@code .per-network} (1000) per
 *       period, burst {@code .network-burst} (300): rotating through the /64s of a site does not multiply the budget.</li>
 *   <li><b>instance</b> (every kind): {@code .global-per-second} (200) per second, burst as much: a ceiling on what
 *       one instance answers of each kind whatever the addresses.</li>
 * </ul>
 * A request takes a token from every bucket that counts it, or from none when one refuses (429 {@code RATE_LIMITED}
 * with {@code Retry-After}, the longest of the refusing buckets). Every request counts, right or wrong, so a refusal
 * tells nothing about the account. Next to the lock of an account after failed passwords (section 4), not instead of
 * it. In memory, per instance; {@code .enabled=false} turns it off (a gateway that limits already). The period is
 * {@code .period} (one minute).
 */
@Component
public class SignInRateLimits implements PlatformCheck {

    /** Keys kept per limiter; the least recently used are forgotten first. */
    static final int MAX_KEYS = 100_000;
    /** The longest name kept in a key: longer ones are cut (they still count, together). */
    static final int MAX_NAME_LENGTH = 256;
    static final String PREFIX = "jabiz.security.sign-in.rate-limit";

    /** The limits, each positive; {@link #defaults()} gives those of decision D47. */
    public record Settings(int perUserAddress, int userBurst, int perAddress, int burst, int perNetwork,
        int networkBurst, int globalPerSecond, Duration period) {

        public static Settings defaults() {
            return new Settings(20, 10, 300, 100, 1000, 300, 200, Duration.ofMinutes(1));
        }
    }

    /** What a request does; each has buckets of its own, so a busy refresh does not lock signing in out. */
    public enum Kind {
        /** {@code POST /api/auth/login}: counted with the user name typed in. */
        PASSWORD(true),
        /** {@code POST /api/auth/oidc/callback}: nobody is named before the provider's answer is checked. */
        OIDC_CALLBACK(false),
        /**
         * {@code POST /api/auth/challenge/verify} and {@code POST /api/auth/step-up}: counted with the user of the
         * challenge (its signature checked, nothing read) or the signed-in user.
         */
        SECOND_FACTOR(true),
        /** {@code POST /api/auth/refresh}: the refresh token names nobody before it is looked up. */
        REFRESH(false),
        /** {@code POST /api/auth/external-token} (decision D59): nobody is named before the token is checked. */
        EXTERNAL_TOKEN(false),
        /**
         * {@code POST /api/auth/oidc/{id}/form-post} (decision D67): Apple's answer, posted by the browser; nobody is
         * named before the callback.
         */
        OIDC_FORM_POST(false);

        private final boolean named;

        Kind(boolean named) {
            this.named = named;
        }
    }

    private final boolean enabled;
    private final Settings settings;
    private final ClientAddresses clients;
    private final PlatformObservations observations;
    private final Map<Kind, TokenBucketLimiter> byName = new EnumMap<>(Kind.class);
    private final Map<Kind, TokenBucketLimiter> byAddress = new EnumMap<>(Kind.class);
    private final Map<Kind, TokenBucketLimiter> byNetwork = new EnumMap<>(Kind.class);
    private final Map<Kind, TokenBucketLimiter> byInstance = new EnumMap<>(Kind.class);

    @Autowired
    public SignInRateLimits(@Value("${" + PREFIX + ".enabled:true}") boolean enabled,
        @Value("${" + PREFIX + ".per-user-address:20}") int perUserAddress,
        @Value("${" + PREFIX + ".user-burst:10}") int userBurst,
        @Value("${" + PREFIX + ".per-address:300}") int perAddress,
        @Value("${" + PREFIX + ".burst:100}") int burst,
        @Value("${" + PREFIX + ".per-network:1000}") int perNetwork,
        @Value("${" + PREFIX + ".network-burst:300}") int networkBurst,
        @Value("${" + PREFIX + ".global-per-second:200}") int globalPerSecond,
        @Value("${" + PREFIX + ".period:PT1M}") Duration period, ClientAddresses clients,
        PlatformObservations observations) {
        this(enabled, new Settings(perUserAddress, userBurst, perAddress, burst, perNetwork, networkBurst,
            globalPerSecond, period), clients, observations, System::nanoTime);
    }

    /** As above, timed by {@code nanoTime} (tests). */
    public SignInRateLimits(boolean enabled, Settings settings, ClientAddresses clients,
        PlatformObservations observations, LongSupplier nanoTime) {
        this.enabled = enabled;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.observations = observations;
        // Out-of-range settings are the startup check's to report; the buckets keep to what they can work with.
        Duration period = settings.period() == null || settings.period().isNegative() || settings.period().isZero()
            ? Duration.ofMinutes(1) : settings.period();
        for (Kind kind : Kind.values()) {
            byName.put(kind, limiter(settings.userBurst(), settings.perUserAddress(), period, nanoTime));
            byAddress.put(kind, limiter(settings.burst(), settings.perAddress(), period, nanoTime));
            byNetwork.put(kind, limiter(settings.networkBurst(), settings.perNetwork(), period, nanoTime));
            byInstance.put(kind, new TokenBucketLimiter(Math.max(1, settings.globalPerSecond()),
                Math.max(1, settings.globalPerSecond()), Duration.ofSeconds(1), 1, nanoTime));
        }
    }

    private static TokenBucketLimiter limiter(int burst, int rate, Duration period, LongSupplier nanoTime) {
        return new TokenBucketLimiter(Math.max(1, burst), Math.max(1, rate), period, MAX_KEYS, nanoTime);
    }

    /**
     * Counts a request of {@code kind} from {@code request}'s client about {@code subject} (the user name typed in,
     * or the user of a second factor; null when it names nobody); 429 {@code RATE_LIMITED} beyond a limit.
     */
    public void count(Kind kind, ServerHttpRequest request, String subject) {
        if (!enabled) {
            return;
        }
        count(kind, clients.clientOf(request), subject);
    }

    /** As above, from the client address {@code client} (as {@link ClientAddresses} gives it). */
    void count(Kind kind, String client, String subject) {
        if (!enabled) {
            return;
        }
        String address = ClientAddresses.rateLimitKey(Objects.requireNonNullElse(client, "unknown"));
        List<TokenBucketLimiter> limiters = new ArrayList<>(4);
        List<String> keys = new ArrayList<>(4);
        limiters.add(byInstance.get(kind));
        keys.add("all");
        limiters.add(byAddress.get(kind));
        keys.add(address);
        String network = ClientAddresses.networkKey(client);
        if (network != null) {
            limiters.add(byNetwork.get(kind));
            keys.add(network);
        }
        String name = kind.named ? normalizedName(subject) : null;
        if (name != null) {
            limiters.add(byName.get(kind));
            keys.add(address + " " + name);
        }
        long retryAfter = 0;
        for (int i = 0; i < limiters.size(); i++) {
            TokenBucketLimiter.Decision decision = limiters.get(i).peek(keys.get(i));
            if (!decision.allowed()) {
                retryAfter = Math.max(retryAfter, decision.retryAfterSeconds());
            }
        }
        if (retryAfter == 0) {
            for (int i = 0; i < limiters.size(); i++) {
                TokenBucketLimiter.Decision decision = limiters.get(i).tryAcquire(keys.get(i));
                if (!decision.allowed()) {
                    // Taken by a request in between: refused all the same.
                    retryAfter = Math.max(retryAfter, decision.retryAfterSeconds());
                }
            }
        }
        if (retryAfter > 0) {
            if (observations != null) {
                observations.event(PlatformObservations.AUTH_RATE_LIMITED,
                    KeyValues.of("kind", kind.name().toLowerCase(Locale.ROOT)));
            }
            throw new RateLimitedException("Too many sign-in requests; try again in " + retryAfter + " seconds",
                retryAfter);
        }
    }

    /**
     * The name a request is counted under: trimmed and in lower case (the e-mail lookup ignores case, and names that
     * differ in case only count together, which is stricter, never looser); at most {@value #MAX_NAME_LENGTH}
     * characters. Null for none.
     */
    static String normalizedName(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        String name = subject.strip().toLowerCase(Locale.ROOT);
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        positive(problems, "per-user-address", settings.perUserAddress());
        positive(problems, "user-burst", settings.userBurst());
        positive(problems, "per-address", settings.perAddress());
        positive(problems, "burst", settings.burst());
        positive(problems, "per-network", settings.perNetwork());
        positive(problems, "network-burst", settings.networkBurst());
        positive(problems, "global-per-second", settings.globalPerSecond());
        if (settings.period() == null || settings.period().isNegative() || settings.period().isZero()) {
            problems.add(CheckProblem.error("SECURITY", PREFIX + ".period", "must be positive"));
        }
        return problems;
    }

    private static void positive(List<CheckProblem> problems, String name, int value) {
        if (value <= 0) {
            problems.add(CheckProblem.error("SECURITY", PREFIX + "." + name, "must be positive"));
        }
    }
}

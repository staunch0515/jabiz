package com.jabiz.runtime.geo;

import com.jabiz.context.ClientCountry;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The country of a client address (decision D74): the local address database of {@code jabiz.geoip.database}, read at
 * startup and read again (off the request path) when the file changes, so that an update needs no restart. No
 * database configured: every address is unknown. Nothing is saved or logged per address.
 *
 * <p>Its startup check ({@code GEOIP}): a configured file that cannot be read is an error, as is no file where the
 * application requires one ({@code jabiz.geoip.required}); a file older than {@code jabiz.geoip.max-age} (45 days by
 * default), or one with no ranges, is a warning.
 */
@Component
public class ClientCountries implements PlatformCheck {

    private static final Logger log = LoggerFactory.getLogger(ClientCountries.class);

    static final String CATEGORY = "GEOIP";
    static final String DATABASE = "jabiz.geoip.database";
    static final String REQUIRED = "jabiz.geoip.required";
    static final String MAX_AGE = "jabiz.geoip.max-age";
    /** How often at most a lookup looks whether the file changed. */
    static final Duration RECHECK = Duration.ofMinutes(1);

    private record Loaded(IpCountryDatabase database, FileTime modified, String problem) {}

    private final Path path;
    private final boolean required;
    private final Duration maxAge;
    private final Clock clock;
    private final AtomicReference<Loaded> loaded = new AtomicReference<>();
    private final AtomicReference<Instant> checked = new AtomicReference<>(Instant.MIN);
    private final AtomicBoolean reloading = new AtomicBoolean();

    public ClientCountries(@Value("${" + DATABASE + ":}") String database,
        @Value("${" + REQUIRED + ":false}") boolean required,
        @Value("${" + MAX_AGE + ":P45D}") Duration maxAge, Clock clock) {
        this.path = database == null || database.isBlank() ? null : Path.of(database.strip());
        this.required = required;
        this.maxAge = maxAge;
        this.clock = clock;
        loaded.set(path == null ? new Loaded(IpCountryDatabase.EMPTY, null, null) : load(path));
        checked.set(clock.instant());
    }

    private static Loaded load(Path path) {
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            IpCountryDatabase database = IpCountryDatabase.read(path);
            log.info("Address database {}: {} ranges", path, database.size());
            return new Loaded(database, modified, null);
        } catch (IOException | RuntimeException e) {
            log.warn("Address database {} cannot be read: {}", path, e.getMessage());
            return new Loaded(IpCountryDatabase.EMPTY, null, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** The country of a client address literal (as {@code ClientAddresses} gives it); unknown when null. */
    public ClientCountry countryOf(String address) {
        reloadIfChanged();
        return address == null ? ClientCountry.UNKNOWN : loaded.get().database().countryOf(address);
    }

    /** Whether a database is configured. */
    public boolean configured() {
        return path != null;
    }

    /**
     * Looks, at most once a minute, whether the file changed and then reads it on a worker thread: the lookup itself
     * never reads the disk. A file that cannot be read keeps the database read before.
     */
    private void reloadIfChanged() {
        if (path == null) {
            return;
        }
        Instant now = clock.instant();
        Instant last = checked.get();
        if (now.isBefore(last.plus(RECHECK)) || !checked.compareAndSet(last, now) || !reloading.compareAndSet(false, true)) {
            return;
        }
        Mono.fromRunnable(() -> {
                try {
                    FileTime modified = Files.getLastModifiedTime(path);
                    if (!modified.equals(loaded.get().modified())) {
                        Loaded next = load(path);
                        if (next.problem() == null) {
                            loaded.set(next);
                        }
                    }
                } catch (IOException e) {
                    log.warn("Address database {} cannot be read: {}", path, e.getMessage());
                }
            })
            .subscribeOn(Schedulers.boundedElastic())
            .doFinally(signal -> reloading.set(false))
            .subscribe();
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        if (path == null) {
            if (required) {
                problems.add(CheckProblem.error(CATEGORY, DATABASE, "the application requires an address database ("
                    + REQUIRED + "), but none is configured"));
            }
            return problems;
        }
        Loaded current = loaded.get();
        if (current.problem() != null) {
            problems.add(CheckProblem.error(CATEGORY, DATABASE, "the address database " + path
                + " cannot be read: " + current.problem()));
            return problems;
        }
        if (current.database().size() == 0) {
            problems.add(CheckProblem.warning(CATEGORY, DATABASE, "the address database " + path
                + " has no ranges: every address is unknown"));
        }
        if (current.modified() != null
            && current.modified().toInstant().plus(maxAge).isBefore(clock.instant())) {
            problems.add(CheckProblem.warning(CATEGORY, DATABASE, "the address database " + path + " is older than "
                + MAX_AGE + " (" + maxAge + "): update it"));
        }
        return problems;
    }
}

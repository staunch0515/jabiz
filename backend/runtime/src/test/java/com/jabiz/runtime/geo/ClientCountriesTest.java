package com.jabiz.runtime.geo;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.test.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The configured address database and its startup check (decision D74). */
class ClientCountriesTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-07T00:00:00Z"));

    private Path file(Path dir, String csv) throws IOException {
        Path file = dir.resolve("countries.csv");
        Files.writeString(file, csv);
        Files.setLastModifiedTime(file, FileTime.from(clock.instant().minus(Duration.ofDays(1))));
        return file;
    }

    @Test
    void withoutADatabaseEveryAddressIsUnknownAndThatIsFineUnlessRequired() {
        ClientCountries none = new ClientCountries("", false, Duration.ofDays(45), clock);
        assertThat(none.configured()).isFalse();
        assertThat(none.countryOf("192.0.2.1").known()).isFalse();
        assertThat(none.check()).isEmpty();
        assertThat(new ClientCountries(" ", true, Duration.ofDays(45), clock).check())
            .extracting(CheckProblem::severity, CheckProblem::location)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(CheckProblem.Severity.ERROR, "jabiz.geoip.database"));
    }

    @Test
    void looksUpTheConfiguredFile(@TempDir Path dir) throws IOException {
        ClientCountries countries = new ClientCountries(file(dir, "192.0.2.0,192.0.2.255,CN\n").toString(), true,
            Duration.ofDays(45), clock);
        assertThat(countries.countryOf("192.0.2.1").mainlandChina()).isTrue();
        assertThat(countries.countryOf(null).known()).isFalse();
        assertThat(countries.check()).isEmpty();
    }

    @Test
    void aMissingOrBrokenFileIsAnErrorAStaleOrEmptyOneAWarning(@TempDir Path dir) throws IOException {
        assertThat(new ClientCountries(dir.resolve("absent.csv").toString(), false, Duration.ofDays(45), clock)
            .check()).singleElement().satisfies(p -> assertThat(p.severity()).isEqualTo(CheckProblem.Severity.ERROR));
        assertThat(new ClientCountries(file(dir, "nonsense\n").toString(), false, Duration.ofDays(45), clock)
            .check()).singleElement().satisfies(p -> assertThat(p.message()).contains("cannot be read"));
        Path stale = file(dir, "# none\n");
        assertThat(new ClientCountries(stale.toString(), false, Duration.ofHours(1), clock).check())
            .extracting(CheckProblem::severity).containsExactly(CheckProblem.Severity.WARNING,
                CheckProblem.Severity.WARNING);
    }

    @Test
    void readsTheFileAgainWhenItChangesOffTheLookup(@TempDir Path dir) throws IOException {
        Path file = file(dir, "192.0.2.0,192.0.2.255,CN\n");
        ClientCountries countries = new ClientCountries(file.toString(), false, Duration.ofDays(45), clock);
        Files.writeString(file, "192.0.2.0,192.0.2.255,JP\n");
        Files.setLastModifiedTime(file, FileTime.from(clock.instant()));
        // Within a minute of the last look: not yet.
        assertThat(countries.countryOf("192.0.2.1").code()).isEqualTo("CN");
        clock.advance(Duration.ofMinutes(2));
        countries.countryOf("192.0.2.1");
        await().atMost(Duration.ofSeconds(5)).until(() -> "JP".equals(countries.countryOf("192.0.2.1").code()));
        // A broken update keeps the database read before.
        Files.writeString(file, "broken\n");
        Files.setLastModifiedTime(file, FileTime.from(clock.instant().plusSeconds(5)));
        clock.advance(Duration.ofMinutes(2));
        countries.countryOf("192.0.2.1");
        assertThat(countries.countryOf("192.0.2.1").code()).isEqualTo("JP");
    }

    @Test
    void gaugesShowTheRangesAndWhetherTheDatabaseIsStale(@TempDir Path dir) throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ClientCountries("", false, Duration.ofDays(45), clock).meters(registry);
        assertThat(registry.find("jabiz.geoip.stale").gauge()).as("no gauges without a database").isNull();

        ClientCountries fresh = new ClientCountries(file(dir, "192.0.2.0,192.0.2.255,CN\n").toString(), false,
            Duration.ofDays(45), clock);
        fresh.meters(registry);
        assertThat(registry.get("jabiz.geoip.ranges").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("jabiz.geoip.stale").gauge().value()).isEqualTo(0.0);
        clock.advance(Duration.ofDays(45));
        assertThat(registry.get("jabiz.geoip.stale").gauge().value()).as("older than the maximum age").isEqualTo(1.0);

        ClientCountries broken = new ClientCountries(dir.resolve("absent.csv").toString(), false, Duration.ofDays(45),
            clock);
        assertThat(broken.stale()).isTrue();
        assertThat(broken.ranges()).isZero();
    }
}

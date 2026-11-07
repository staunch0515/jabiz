package com.jabiz.quizbuks.usage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** F-C2: the meters are listed once, named in every language, and are exactly those the snapshot's template gives. */
class UsageMetersTest {

    @Test
    void theMetersAreTheSpecsInOrderUniqueAndNamed() {
        assertThat(UsageMeters.codes()).containsExactly("storage.bytes", "storage.files", "assets.count",
            "assets.text-bytes", "content.quizzes", "content.versions", "content.materials", "ai.tokens");
        assertThat(new HashSet<>(UsageMeters.codes())).hasSameSizeAs(UsageMeters.ALL);
        for (UsageMeters.Meter meter : UsageMeters.ALL) {
            assertThat(meter.names()).as(meter.code()).containsOnlyKeys("en", "zh", "ja");
            assertThat(meter.names().values()).as(meter.code()).allMatch(name -> !name.isBlank());
            // Fits the table's column.
            assertThat(meter.code().length()).isLessThanOrEqualTo(32);
        }
    }

    @Test
    void theSnapshotsTemplateGivesExactlyTheListedMeters() throws IOException {
        String sql;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(
            "/queries/qb/system/usage-measure.sql"))) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(UsageMetersCheck.meters(sql)).containsExactlyElementsOf(UsageMeters.codes());
        assertThat(UsageMetersCheck.problems(sql)).isEmpty();
    }

    @Test
    void theCheckReportsAMeterMissingOrNotListed() {
        String missing = "SELECT 'storage.bytes' AS meter -- 'ai.tokens' in a comment is not a meter";
        assertThat(UsageMetersCheck.problems(missing)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains("missing").contains("storage.files"));
        String extra = String.join(" UNION ALL ", UsageMeters.codes().stream()
            .map(code -> "SELECT '" + code + "'").toList()) + " UNION ALL SELECT 'download.bytes'";
        assertThat(UsageMetersCheck.problems(extra)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains("download.bytes"));
        assertThat(UsageMetersCheck.problems(null)).hasSize(1);
        assertThat(UsageMetersCheck.meters("/* 'storage.bytes' */ SELECT 1")).isEmpty();
    }
}

package com.jabiz.runtime.report;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** docs/design/19-reports.md section 4: export settings, the font check and when the header names an effective time. */
class ReportSettingsTest {

    private static ReportSettings settings(List<String> fonts) {
        return new ReportSettings("Acme", "Asia/Tokyo", "en-US", 10, fonts);
    }

    @Test
    void unreadableFontFilesAreReportedAtStartup(@TempDir Path dir) throws Exception {
        Path font = Files.write(dir.resolve("cjk.ttf"), new byte[] {1});
        ReportSettings settings = settings(List.of(font.toString(), " ", dir.resolve("missing.ttf").toString()));

        assertThat(settings.fontPaths()).hasSize(2);
        assertThat(new ReportFontsCheck(settings).check()).extracting(CheckProblem::message)
            .containsExactly("cannot read the font file " + dir.resolve("missing.ttf"));
        assertThat(new ReportFontsCheck(settings(List.of())).check()).isEmpty();
    }

    @Test
    void settingsAreChecked() {
        ReportSettings settings = settings(List.of());
        assertThat(settings.company()).isEqualTo("Acme");
        assertThat(settings.maxRows()).isEqualTo(10);
        assertThat(settings.format().zone().getId()).isEqualTo("Asia/Tokyo");
        assertThat(settings.fonts()).isSameAs(settings.fonts());
        assertThatThrownBy(() -> new ReportSettings("", "UTC", "", 0, List.of()))
            .hasMessageContaining("max-rows");
    }

    @Test
    void theHeaderNamesAnEffectiveTimeOnlyWhenOneWasAskedFor() {
        SemanticKind time = new SemanticKind.Temporal(TemporalRole.EVENT_TIME);
        AdvancedQueryDefinition plain = AdvancedQueryDefinition.define("q", q -> q.returns("one", time)
            .sqlTemplate("SELECT 1"));
        AdvancedQueryDefinition sliced = AdvancedQueryDefinition.define("q", q -> q.returns("one", time)
            .parameter("at", time, false).timeSlice("at", null).sqlTemplate("SELECT 1"));
        AdvancedQueryDefinition knownOnly = AdvancedQueryDefinition.define("q", q -> q.returns("one", time)
            .parameter("seen", time, false).timeSlice(null, "seen").sqlTemplate("SELECT 1"));
        Instant then = Instant.parse("2025-12-31T00:00:00Z");

        assertThat(ReportExporter.asOfAsked(plain, Map.of(), AdvancedQueryExecutor.At.NOW)).isFalse();
        assertThat(ReportExporter.asOfAsked(plain, Map.of(), new AdvancedQueryExecutor.At(then, null))).isTrue();
        assertThat(ReportExporter.asOfAsked(plain, Map.of(), null)).isFalse();
        assertThat(ReportExporter.asOfAsked(sliced, Map.of("at", then.toString()), AdvancedQueryExecutor.At.NOW))
            .isTrue();
        assertThat(ReportExporter.asOfAsked(sliced, Map.of(), AdvancedQueryExecutor.At.NOW)).isFalse();
        assertThat(ReportExporter.asOfAsked(sliced, null, AdvancedQueryExecutor.At.NOW)).isFalse();
        assertThat(ReportExporter.asOfAsked(knownOnly, Map.of("seen", then.toString()), AdvancedQueryExecutor.At.NOW))
            .isFalse();
    }
}

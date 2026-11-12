package com.jabiz.quizbuks.content;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-F11a (docs/quizbuks/plans/impl/F-F11.md section 3.1): the bytes of one import request are the parameter's value,
 * cut to the smallest and to the technical ceiling, and the ceiling stays below what the framework takes in memory.
 */
class QuizBankLimitsTest {

    private static ParamValues values(Object bytes) {
        return new ParamValues(Instant.EPOCH, bytes == null ? Map.of()
            : Map.of(QbParams.CONTENT_IMPORT_BATCH_BYTES, bytes));
    }

    @Test
    void theBytesOfARequestAreTheParametersValueCutToTheLimits() {
        assertThat(QuizBankLimits.importBatchBytes(values(new BigDecimal(1_000_000)))).isEqualTo(1_000_000);
        assertThat(QuizBankLimits.importBatchBytes(values(new BigDecimal("1000000.9")))).isEqualTo(1_000_000);
        assertThat(QuizBankLimits.importBatchBytes(values(new BigDecimal(100))))
            .isEqualTo(ContentLimits.IMPORT_BATCH_BYTES_MIN);
        assertThat(QuizBankLimits.importBatchBytes(values(BigDecimal.ZERO)))
            .isEqualTo(ContentLimits.IMPORT_BATCH_BYTES_MIN);
        assertThat(QuizBankLimits.importBatchBytes(values(new BigDecimal(9_000_000))))
            .isEqualTo(ContentLimits.IMPORT_BATCH_BYTES_TECH);
    }

    @Test
    void aValueNotSetUpYetCountsAsTheDeclaredOne() {
        assertThat(QbParams.defaultOf(QbParams.CONTENT_IMPORT_BATCH_BYTES)).isEqualTo("3145728");
        assertThat(QuizBankLimits.importBatchBytes(values(null))).isEqualTo(3_145_728);
        assertThat(QuizBankLimits.importBatchBytes(null)).isEqualTo(3_145_728);
    }

    @Test
    void theParameterIsOrdinaryAndTheLimitsOrderHolds() {
        assertThat(QbParams.CONTROLLED)
            .doesNotContain(QbParams.CONTENT_IMPORT_BATCH_BYTES);
        assertThat(ContentLimits.IMPORT_BATCH_BYTES_MIN).isLessThan(ContentLimits.IMPORT_BATCH_BYTES_TECH);
        assertThat(Integer.parseInt(QbParams.defaultOf(QbParams.CONTENT_IMPORT_BATCH_BYTES)))
            .isBetween(ContentLimits.IMPORT_BATCH_BYTES_MIN, ContentLimits.IMPORT_BATCH_BYTES_TECH);
    }

    /** A request is refused by the framework above max-in-memory-size; the ceiling leaves room for the envelope. */
    @Test
    void theCeilingIsBelowWhatTheFrameworkTakesInMemory() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        Matcher m = Pattern.compile("max-in-memory-size:\\s*(\\d+)\\s*(MB|KB)?").matcher(yml);
        assertThat(m.find()).isTrue();
        long bytes = Long.parseLong(m.group(1)) * switch (m.group(2) == null ? "" : m.group(2)) {
            case "MB" -> 1_048_576L;
            case "KB" -> 1_024L;
            default -> 1L;
        };
        assertThat((long) ContentLimits.IMPORT_BATCH_BYTES_TECH).isLessThan(bytes);
    }
}

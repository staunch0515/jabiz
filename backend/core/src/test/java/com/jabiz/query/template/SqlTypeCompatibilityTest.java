package com.jabiz.query.template;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTypeCompatibilityTest {

    @Test
    void resultTypes() {
        assertThat(SqlTypeCompatibility.checkResult(BigDecimal.class, "numeric")).isEmpty();
        assertThat(SqlTypeCompatibility.checkResult(BigDecimal.class, "INT8")).isEmpty();
        assertThat(SqlTypeCompatibility.checkResult(String.class, "uuid")).isEmpty();
        assertThat(SqlTypeCompatibility.checkResult(Instant.class, "timestamptz")).isEmpty();
        assertThat(SqlTypeCompatibility.checkResult(LinkedHashMap.class, "jsonb")).isEmpty();
        assertThat(SqlTypeCompatibility.checkResult(LocalDate.class, "date")).isEmpty();
        assertThat(SqlTypeCompatibility.checkResult(LocalDate.class, "timestamptz")).get().asString()
            .contains("timestamptz is not compatible with LocalDate");
        assertThat(SqlTypeCompatibility.checkResult(Instant.class, "numeric")).get().asString()
            .contains("numeric is not compatible with Instant");
        assertThat(SqlTypeCompatibility.checkResult(Boolean.class, "_bool")).get().asString()
            .contains("expects an array");
        assertThat(SqlTypeCompatibility.checkResult(Thread.class, "text")).get().asString()
            .contains("no database type");
    }

    @Test
    void parameterTypes() {
        assertThat(SqlTypeCompatibility.checkParameter(Long.class, false, "numeric")).isEmpty();
        assertThat(SqlTypeCompatibility.checkParameter(UUID.class, true, "_uuid")).isEmpty();
        assertThat(SqlTypeCompatibility.checkParameter(LocalDate.class, false, "date")).isEmpty();
        assertThat(SqlTypeCompatibility.checkParameter(String.class, false, "uuid")).get().asString()
            .contains("uuid is not compatible with String");
        assertThat(SqlTypeCompatibility.checkParameter(String.class, true, "text")).get().asString()
            .contains("is a list");
        assertThat(SqlTypeCompatibility.checkParameter(String.class, true, "_int4")).get().asString()
            .contains("an array of");
    }
}

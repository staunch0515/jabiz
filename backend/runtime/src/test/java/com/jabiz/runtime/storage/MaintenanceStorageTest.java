package com.jabiz.runtime.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceStorageTest {

    @Test
    void takesAnR2dbcUrlAndNamesWhatElseWasGivenWithoutRepeatingIt() {
        assertThat(MaintenanceStorage.urlProblem("r2dbc:postgresql://db:5432/app")).isNull();
        assertThat(MaintenanceStorage.urlProblem("  r2dbc:postgresql://db:5432/app?schema=s ")).isNull();
        // The JDBC URL Flyway takes is the usual mistake (QuizBuks staging, 2026-10-29).
        assertThat(MaintenanceStorage.urlProblem("jdbc:postgresql://db:5432/app"))
            .contains("must be an R2DBC URL like r2dbc:postgresql://", "is a JDBC URL (jdbc:…)")
            .doesNotContain("db:5432/app");
        assertThat(MaintenanceStorage.urlProblem("postgres://user:secret@db/app"))
            .endsWith("is not an R2DBC URL").doesNotContain("secret");
        assertThat(MaintenanceStorage.urlProblem(" ")).endsWith("is empty");
        assertThat(MaintenanceStorage.urlProblem(null)).endsWith("is empty");
    }
}

package com.jabiz.runtime.schema;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaIndexChecksTest {

    @Test
    void anInvalidIndexIsAWarningWordedByWhatItProbablyIs() {
        CheckProblem failed = SchemaIndexChecks.problem("t", "t_a_idx", false);
        assertThat(failed.severity()).isEqualTo(CheckProblem.Severity.WARNING);
        assertThat(failed.message()).contains("failed half way", "DROP INDEX CONCURRENTLY t_a_idx", "flyway repair");

        CheckProblem building = SchemaIndexChecks.problem("t", "t_a_idx", true);
        assertThat(building.severity()).isEqualTo(CheckProblem.Severity.WARNING);
        assertThat(building.message()).contains("probably being built").doesNotContain("DROP INDEX");

        CheckProblem reindex = SchemaIndexChecks.problem("t", "t_a_idx_ccold", false);
        assertThat(reindex.severity()).isEqualTo(CheckProblem.Severity.WARNING);
        assertThat(reindex.message()).contains("REINDEX CONCURRENTLY", "DROP INDEX CONCURRENTLY t_a_idx_ccold");
        assertThat(SchemaIndexChecks.problem("t", "t_a_idx_ccnew", false).message()).contains("REINDEX CONCURRENTLY");
    }
}

package com.jabiz.runtime.schema;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaIndexChecksTest {

    @Test
    void anInvalidIndexIsAnErrorUnlessItIsBeingBuiltOrAReindexTemporary() {
        assertThat(SchemaIndexChecks.classify("t", "t_a_idx", false).severity()).isEqualTo(CheckProblem.Severity.ERROR);
        assertThat(SchemaIndexChecks.classify("t", "t_a_idx", true).severity()).isEqualTo(CheckProblem.Severity.WARNING);
        assertThat(SchemaIndexChecks.classify("t", "t_a_idx_ccnew", false).severity())
            .isEqualTo(CheckProblem.Severity.WARNING);
        assertThat(SchemaIndexChecks.classify("t", "t_a_idx_ccold", false).message())
            .contains("DROP INDEX CONCURRENTLY t_a_idx_ccold");
    }
}

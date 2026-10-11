package com.jabiz.runtime.imports;

import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** An import's rows run as sub-processes of the importing user (decision D41). */
class ImportTargetChecksTest {

    record Row(String sku) {}

    private static ProcessDefinition<Row, Row, ?> target(ProcessAccess access) {
        return ProcessDefinition.single("ROW_" + access, 1, Row.class, Row.class, (in, ctx) -> in)
            .withPermissions("row.write").withAccess(access);
    }

    @Test
    void theTargetIsOpenOrSubProcessOnly() {
        assertThat(ImportChecks.targetProblems("it.import", target(ProcessAccess.OPEN))).isEmpty();
        assertThat(ImportChecks.targetProblems("it.import", target(ProcessAccess.SUB_PROCESS_ONLY))).isEmpty();
        for (ProcessAccess refused : new ProcessAccess[] {ProcessAccess.INTERNAL, ProcessAccess.ENTRY_ONLY}) {
            assertThat(ImportChecks.targetProblems("it.import", target(refused))).as(refused.name()).singleElement()
                .satisfies(problem -> {
                    assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.ERROR);
                    assertThat(problem.category()).isEqualTo("IMPORT");
                    assertThat(problem.message()).contains("ROW_" + refused).contains("open or subProcessOnly()");
                });
        }
    }
}

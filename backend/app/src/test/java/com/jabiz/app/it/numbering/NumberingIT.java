package com.jabiz.app.it.numbering;

import com.jabiz.app.it.fixture.ItNumberingFixtures;
import com.jabiz.app.it.fixture.ItNumberingFixtures.DrawInput;
import com.jabiz.app.it.fixture.ItNumberingFixtures.DrawOutput;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gap-free numbers (docs/design/18-numbering-approvals-tasks.md section 2, decision D23): drawn in the process's
 * transaction, given back when it rolls back, never issued twice under concurrency, and recorded append-only.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class NumberingIT extends PostgresIntegrationTest {

    @Autowired
    ProcessExecutor executor;

    private DrawOutput drawScoped(String scope, boolean fail) {
        return asTestRequest(executor.execute(ItNumberingFixtures.DRAW_SCOPED, new DrawInput(scope, fail, false)))
            .block();
    }

    private static String scope() {
        return "S" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<Long> values(String sequence, String scope) {
        return query("SELECT value_no FROM sys_number_assignment WHERE sequence_name = ? AND scope_key = ?"
            + " ORDER BY value_no", sequence, scope).stream()
            .map(row -> ((Number) row.get("value_no")).longValue()).toList();
    }

    @Test
    void numbersAreConsecutivePerScopeAndFormatted() {
        String a = scope();
        String b = scope();
        assertThat(drawScoped(a, false).number()).isEqualTo("S-" + a + "-0001");
        assertThat(drawScoped(a, false).number()).isEqualTo("S-" + a + "-0002");
        assertThat(drawScoped(b, false).number()).isEqualTo("S-" + b + "-0001");
        assertThat(drawScoped(a, false).number()).isEqualTo("S-" + a + "-0003");
        assertThat(values(ItNumberingFixtures.SCOPED, a)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void aRolledBackProcessGivesItsNumberBack() {
        String s = scope();
        assertThat(drawScoped(s, false).number()).endsWith("-0001");
        assertThatThrownBy(() -> drawScoped(s, true)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> drawScoped(s, true)).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(drawScoped(s, false).number()).endsWith("-0002");
        assertThat(values(ItNumberingFixtures.SCOPED, s)).containsExactly(1L, 2L);
    }

    @Test
    void nothingIsDrawnWhenTheConditionDoesNotHold() {
        String s = scope();
        DrawOutput skipped = asTestRequest(executor.execute(ItNumberingFixtures.DRAW_SCOPED,
            new DrawInput(s, false, true))).block();
        assertThat(skipped.number()).isNull();
        assertThat(drawScoped(s, false).number()).endsWith("-0001");
    }

    @Test
    void concurrentProcessesNeverShareOrSkipANumber() {
        String s = scope();
        List<String> numbers = Flux.range(0, 20)
            .flatMap(i -> asTestRequest(executor.execute(ItNumberingFixtures.DRAW_SCOPED,
                new DrawInput(s, i % 5 == 4, false)))
                .map(result -> result.number())
                .onErrorResume(BusinessRuleViolationException.class, e -> reactor.core.publisher.Mono.empty()), 20)
            .collectList().block();
        // Every fifth process failed after drawing and gave its number back.
        assertThat(numbers).hasSize(16).doesNotHaveDuplicates();
        assertThat(values(ItNumberingFixtures.SCOPED, s)).containsExactlyElementsOf(
            IntStream.rangeClosed(1, 16).mapToObj(Long::valueOf).toList());
    }

    @Test
    void anUnscopedSequenceStartsWhereItIsDeclaredToAndRecordsItsProcess() {
        long before = values(ItNumberingFixtures.PLAIN, "").size();
        String number = asTestRequest(executor.execute(ItNumberingFixtures.DRAW_PLAIN,
            new DrawInput(null, false, false))).block().number();
        assertThat(number).isEqualTo("P-" + (100 + before));

        Map<String, Object> row = query("SELECT a.number, p.process_name FROM sys_number_assignment a"
            + " JOIN op_process p ON p.process_seq_id = a.process_seq_id WHERE a.sequence_name = ? AND a.number = ?",
            ItNumberingFixtures.PLAIN, number).getFirst();
        assertThat(row).containsEntry("process_name", "IT_DRAW_PLAIN");
    }

    @Test
    void issuedNumbersAreNeverUpdatedOrDeleted() {
        SqlStatementLog.STATEMENTS.clear();
        String s = scope();
        drawScoped(s, false);
        drawScoped(s, false);
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches("(?is).*(UPDATE|DELETE FROM)\\s+"
            + "sys_number_assignment.*"));
        assertThatThrownBy(() -> execute("UPDATE sys_number_assignment SET number = 'X' WHERE scope_key = ?", s))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_number_assignment WHERE scope_key = ?", s))
            .hasMessageContaining("append-only");
    }
}

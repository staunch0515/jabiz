package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.app.it.fixture.ItProcessFixtures;
import com.jabiz.app.it.fixture.ItProcessFixtures.OwnerTicketsInput;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reads in a process are not pages (phase 14p, decision D32; docs/design/06-process.md section 2.1): the ticket
 * dataset gives 5 rows to a page, yet a template in a process reads all 7 rows and an entity query all it asks for;
 * more rows than {@code jabiz.process.max-read-rows} (8 here) fail the read rather than give part of the result.
 */
@SpringBootTest(properties = "jabiz.process.max-read-rows=8")
class TemplateReadsIT extends PostgresIntegrationTest {

    @Autowired
    ProcessExecutor executor;

    private static String owner(int tickets) {
        String owner = "o-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < tickets; i++) {
            execute("INSERT INTO it_ticket (f_id, f_title, f_amount, f_status, f_owner, f_created_at) VALUES"
                + " (?, ?, 1, 'OPEN', ?, now())", UUID.randomUUID().toString(), "t" + i, owner);
        }
        return owner;
    }

    private static void refused(ThrowingCallable read) {
        assertThatThrownBy(read)
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .singleElement().satisfies(v -> assertThat(v.ruleCode())
                    .isEqualTo(PlatformErrorCodes.PROCESS_READ_TOO_LARGE)));
    }

    @Test
    void aTemplateReadsMoreThanAPage() {
        assertThat(ItFixtures.TICKET_MAX_QUERY_BATCH).isLessThan(7);
        assertThat(asTestRequest(executor.execute(ItProcessFixtures.TITLES, owner(7))).block()).isEqualTo(7);
        assertThat(asTestRequest(executor.execute(ItProcessFixtures.TITLES, owner(8))).block()).isEqualTo(8);
    }

    @Test
    void aTemplateBeyondTheMaximumIsRefusedNotCut() {
        String owner = owner(9);
        refused(() -> asTestRequest(executor.execute(ItProcessFixtures.TITLES, owner)).block());
    }

    @Test
    void anEntityQueryGetsItsOwnLimitNotAPage() {
        String owner = owner(8);
        assertThat(asTestRequest(executor.execute(ItProcessFixtures.OWNER_TICKETS, new OwnerTicketsInput(owner, 7)))
            .block()).isEqualTo(7);
        assertThat(asTestRequest(executor.execute(ItProcessFixtures.OWNER_TICKETS, new OwnerTicketsInput(owner, 8)))
            .block()).isEqualTo(8);
    }

    /** Asking for more than the maximum is not refused (platform processes ask for thousands); finding more is. */
    @Test
    void anEntityQueryBeyondTheMaximumIsRefusedOnlyWhenThereAreThatManyRows() {
        String few = owner(1);
        assertThat(asTestRequest(executor.execute(ItProcessFixtures.OWNER_TICKETS, new OwnerTicketsInput(few, 1000)))
            .block()).isEqualTo(1);
        String many = owner(9);
        refused(() -> asTestRequest(executor.execute(ItProcessFixtures.OWNER_TICKETS, new OwnerTicketsInput(many, 1000)))
            .block());
    }
}

package com.jabiz.finance.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceRolesTest {

    @Test
    void theNineRolesOfTheRequirements() {
        assertThat(FinanceRoles.all()).extracting(FinanceRoles.Role::code).containsExactly(FinanceRoles.CONTROLLER,
            FinanceRoles.ACCOUNTANT, FinanceRoles.RECEIVABLES_CLERK, FinanceRoles.PAYABLES_CLERK,
            FinanceRoles.APPROVER, FinanceRoles.TREASURER, FinanceRoles.EXECUTIVE, FinanceRoles.EXTERNAL_AUDITOR,
            FinanceRoles.SYSTEM_ADMINISTRATOR);
    }

    /** No role writes the ledger directly (design section 4.6); the administrator posts nothing at all. */
    @Test
    void noRoleWritesTheLedger() {
        for (FinanceRoles.Role role : FinanceRoles.all()) {
            assertThat(role.permissions()).as(role.code())
                .doesNotContain("*", "ledger.post", "ledger.reverse", "ledger.account.write")
                .doesNotHaveDuplicates();
        }
        List<String> administrator = role(FinanceRoles.SYSTEM_ADMINISTRATOR).permissions();
        assertThat(administrator).noneMatch(p -> p.startsWith("fin.journal") || p.startsWith("fin.period")
            || p.startsWith("fin.account") || p.startsWith("approval"));
        // Preparing and approving are apart: the accountant prepares, approving takes another role.
        assertThat(role(FinanceRoles.ACCOUNTANT).permissions()).contains("fin.journal.prepare")
            .doesNotContain("fin.journal.approve", "approval.decide");
        // The clerk records cash and asks for write-offs; approving them is another role's (FIN-AR-012).
        assertThat(role(FinanceRoles.RECEIVABLES_CLERK).permissions()).contains("fin.receipt.record",
            "fin.writeoff.request").doesNotContain("fin.writeoff.approve", "fin.invoice.approve", "approval.decide",
            "fin.receipt.void", "fin.invoice.credit");
        assertThat(role(FinanceRoles.APPROVER).permissions()).contains("fin.writeoff.approve", "fin.invoice.approve")
            .doesNotContain("fin.writeoff.request", "fin.receipt.record");
        assertThat(role(FinanceRoles.EXECUTIVE).permissions()).noneMatch(p -> p.endsWith(".maintain")
            || p.endsWith(".prepare") || p.endsWith(".approve") || p.endsWith(".close"));
    }

    private static FinanceRoles.Role role(String code) {
        return FinanceRoles.all().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }
}

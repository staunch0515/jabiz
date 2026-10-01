package com.jabiz.finance.setup;

import com.jabiz.finance.FinancePermissions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The roles of the finance books (FIN-DOC-01 section 2, docs/finance/00-design.md section 4.6) and the permissions
 * each holds; {@code FIN_SETUP} creates them. Each phase adds the permissions of what it builds. No role holds the
 * ledger's {@code ledger.post}, {@code ledger.reverse} or {@code ledger.account.write}: the general ledger is written
 * only by the finance processes, as their subprocesses.
 */
public final class FinanceRoles {

    public static final String CONTROLLER = "Controller";
    public static final String ACCOUNTANT = "Accountant";
    public static final String RECEIVABLES_CLERK = "ReceivablesClerk";
    public static final String PAYABLES_CLERK = "PayablesClerk";
    public static final String APPROVER = "Approver";
    public static final String TREASURER = "Treasurer";
    public static final String EXECUTIVE = "Executive";
    public static final String EXTERNAL_AUDITOR = "ExternalAuditor";
    public static final String SYSTEM_ADMINISTRATOR = "SystemAdministrator";

    /** A role: its code, its label and its permissions. */
    public record Role(String code, String label, List<String> permissions) {
        public Role {
            permissions = List.copyOf(permissions);
        }
    }

    /** What every finance user reads: the chart, master data and the calendar. */
    private static final List<String> READ_BOOKS = List.of(FinancePermissions.ACCOUNT_READ,
        FinancePermissions.MASTER_READ, FinancePermissions.PERIOD_READ, "ledger.account.read", "task.read");

    /** What reviewers read on top: entries, ledger balances and issued reports. */
    private static final List<String> READ_LEDGER = List.of(FinancePermissions.JOURNAL_READ, "ledger.read",
        "report.archive.read", "approval.read");

    private static final Map<String, Role> ROLES = new LinkedHashMap<>();

    static {
        add(CONTROLLER, "Controller", READ_BOOKS, READ_LEDGER, List.of(
            FinancePermissions.ACCOUNT_MAINTAIN, FinancePermissions.DIMENSION_MAINTAIN, FinancePermissions.FX_MAINTAIN,
            FinancePermissions.PERIOD_MAINTAIN, FinancePermissions.PERIOD_CLOSE, FinancePermissions.JOURNAL_APPROVE,
            FinancePermissions.JOURNAL_CONTROL_EXCEPTION, FinancePermissions.JOURNAL_ATTACH, "approval.decide", "control.propose", "control.publish",
            "sod.read", "report.issue", "audit.read", "operation.read", FinancePermissions.IMPORT,
            FinancePermissions.MIGRATION, FinancePermissions.PAYROLL_MAINTAIN, FinancePermissions.AR_READ,
            FinancePermissions.CUSTOMER_MAINTAIN, FinancePermissions.TAX_MAINTAIN, FinancePermissions.AR_SETTINGS,
            FinancePermissions.CUSTOMER_TAX, FinancePermissions.CUSTOMER_CREDIT, FinancePermissions.INVOICE_PREPARE,
            FinancePermissions.INVOICE_CREDIT, FinancePermissions.RECEIPT_RECORD, FinancePermissions.RECEIPT_VOID,
            FinancePermissions.RECEIPT_ADJUST, FinancePermissions.WRITE_OFF_REQUEST, FinancePermissions.WRITE_OFF_APPROVE,
            FinancePermissions.INVOICE_APPROVE, FinancePermissions.RECURRING_INVOICE_MAINTAIN,
            FinancePermissions.COMPANY_MAINTAIN, FinancePermissions.INVOICE_ISSUE, "document.archive.read",
            "document.send", "document.send.any"));
        add(ACCOUNTANT, "Accountant", READ_BOOKS, READ_LEDGER, List.of(FinancePermissions.JOURNAL_PREPARE,
            FinancePermissions.JOURNAL_ATTACH, FinancePermissions.RECURRING_MAINTAIN, FinancePermissions.IMPORT,
            FinancePermissions.PAYROLL_IMPORT, FinancePermissions.AR_READ, "document.archive.read"));
        add(RECEIVABLES_CLERK, "Receivables clerk", READ_BOOKS, List.of(FinancePermissions.AR_READ,
            FinancePermissions.CUSTOMER_MAINTAIN, FinancePermissions.IMPORT, FinancePermissions.INVOICE_PREPARE,
            FinancePermissions.RECEIPT_RECORD, FinancePermissions.WRITE_OFF_REQUEST,
            FinancePermissions.RECURRING_INVOICE_MAINTAIN, FinancePermissions.INVOICE_ISSUE, "document.archive.read",
            "document.send"));
        add(PAYABLES_CLERK, "Payables clerk", READ_BOOKS, List.of());
        add(APPROVER, "Approver", READ_BOOKS, READ_LEDGER, List.of(FinancePermissions.JOURNAL_APPROVE,
            "approval.decide", FinancePermissions.WRITE_OFF_APPROVE, FinancePermissions.INVOICE_APPROVE,
            FinancePermissions.AR_READ));
        add(TREASURER, "Treasurer", READ_BOOKS, READ_LEDGER, List.of(FinancePermissions.FX_MAINTAIN,
            FinancePermissions.IMPORT));
        add(EXECUTIVE, "Executive", READ_BOOKS, READ_LEDGER, List.of(FinancePermissions.AR_READ));
        add(EXTERNAL_AUDITOR, "External auditor", READ_BOOKS, READ_LEDGER, List.of("audit.read", "operation.read",
            FinancePermissions.AR_READ, "document.archive.read"));
        add(SYSTEM_ADMINISTRATOR, "System administrator", List.of(FinancePermissions.SETUP,
            "security.user.read", "security.user.write", "security.user.create", "security.user.password",
            "security.user.unlock", "security.user.mfa-reset", "security.user.identity.write",
            "security.role.read", "security.role.write", "security.user-role.read", "security.user-role.write",
            "security.menu.read", "security.menu.write", "security.login-record.read",
            "security.access-review.read"));
    }

    @SafeVarargs
    private static void add(String code, String label, List<String>... groups) {
        List<String> permissions = java.util.Arrays.stream(groups).flatMap(List::stream).distinct().toList();
        ROLES.put(code, new Role(code, label, permissions));
    }

    /** All roles, in the order of the requirements. */
    public static List<Role> all() {
        return List.copyOf(ROLES.values());
    }

    private FinanceRoles() {}
}

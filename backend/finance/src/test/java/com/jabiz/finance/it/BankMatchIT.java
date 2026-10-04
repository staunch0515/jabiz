package com.jabiz.finance.it;

import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.ReceiptProcesses;
import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.bank.BankEntryProcesses;
import com.jabiz.finance.bank.MatchEntities;
import com.jabiz.finance.bank.MatchProcesses;
import com.jabiz.finance.bank.StatementProcesses;
import com.jabiz.finance.bank.StatementEntities;
import com.jabiz.finance.bank.TransferProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching the operating account's January statement to the books (ROADMAP F5b; FIN-BK-004…006): the receipts
 * RCPT-0001…0003 and the check outstanding at the cutover proposed and accepted in bulk, two transfers matched by hand
 * to one bank debit, the fee and the interest made into BANK-FEE-2601 and BANK-INT-2601 and matched at once, a match
 * undone and made again with both kept, and what may not be matched refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BankMatchIT extends FinanceItSupport {

    private static boolean loaded;
    private static final Map<String, String> LINES = new LinkedHashMap<>();

    private String controller;
    private String accountant;

    @BeforeEach
    void books() {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        if (loaded) {
            return;
        }
        loaded = true;
        openReceivables();
        String treasurer = inRoles("treasurer", FinanceRoles.TREASURER);
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "OPERATING", "bankName",
            "Lakeside National Bank", "glAccount", "1010", "routingNumber", "111000025",
            "companyAccountNumber", "000123456789"));
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "SAVINGS", "bankName", "Lakeside National Bank",
            "glAccount", "1050", "routingNumber", "111000025", "companyAccountNumber", "000987654321"));
        importCsv("finance.bank_opening_items", as("migrator", "fin.migration", "fin.import"),
            "date,reference,description,amount\n2025-12-28,CHK-1045,Check 1045,-3200.00\n", "commit", null,
            Map.of("bankCode", "OPERATING", "statementBalance", "253200.00"), 200);
        String fileId = upload(accountant, "fin.bank.statement",
            sampleText("bank-statement-2026-01.csv").getBytes(StandardCharsets.UTF_8), "statement.csv", "text/csv");
        post("/api/imports/finance.bank_statement/commit", accountant, Map.of("fileId", fileId, "params",
            Map.of("bankCode", "OPERATING"))).expectStatus().isOk();
        find(StatementEntities.LINE_DATASET, "bankCode", "OPERATING")
            .forEach(line -> LINES.put((String) line.get("bankReference"), (String) line.get("lineId")));
        // The January receipts (FIN-EXP-02).
        String clerk = inRoles("ar-clerk", FinanceRoles.RECEIVABLES_CLERK);
        for (String[] r : new String[][] {{"C100", "2026-01-05", "32475.00", "INV-1001"},
            {"C200", "2026-01-16", "24025.00", "INV-1002"}, {"C300", "2026-01-25", "20000.00", "INV-1003"}}) {
            String invoiceId = (String) find(InvoiceEntities.INVOICE_DATASET, "invoiceNo", r[3]).getFirst()
                .get("invoiceId");
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("customerCode", r[0]);
            receipt.put("receiptDate", r[1]);
            receipt.put("amount", r[2]);
            receipt.put("method", "ACH");
            receipt.put("bankAccount", "1010");
            receipt.put("applications", List.of(Map.of("invoiceId", invoiceId, "amount", r[2])));
            ok(ReceiptProcesses.RECORD, clerk, receipt);
        }
        // Two transfers to savings the bank debited as one, 15,000.00 on the 15th.
        for (String amount : List.of("10000.00", "5000.00")) {
            ok(TransferProcesses.POST, treasurer, Map.of("fromBank", "OPERATING", "toBank", "SAVINGS",
                "amount", amount, "sentDate", "2026-01-15", "receivedDate", "2026-01-15"));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> proposals() {
        return (List<Map<String, Object>>) ok(MatchProcesses.PROPOSE, accountant, Map.of("bankCode", "OPERATING"))
            .get("proposals");
    }

    private List<Map<String, Object>> bookItems() {
        return report(MatchProcesses.BOOK_ITEMS, accountant, Map.of("bankCode", "OPERATING"));
    }

    private Map<String, Object> book(String documentNo) {
        return bookItems().stream().filter(i -> documentNo.equals(i.get("documentNo"))).findFirst().orElseThrow();
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void theReceiptsAndTheOutstandingCheckAreProposedWithTheirReasonsAndAcceptedTogether() {
        List<Map<String, Object>> proposals = proposals();
        assertThat(proposals).extracting(p -> p.get("bankReference") + " " + ((List<Map<String, Object>>)
            p.get("items")).stream().map(i -> (String) i.get("documentNo")).toList())
            .containsExactly("BNK-0001 [CHK-1045]", "BNK-0002 [RCPT-0001]", "BNK-0005 [RCPT-0002]",
                "BNK-0008 [RCPT-0003]");
        assertThat(proposals.get(1).get("reasons")).isEqualTo(List.of("amount equal", "same day",
            "name ACME ROBOTICS"));
        // Nothing is written by proposing.
        assertThat(find(MatchEntities.MATCH_DATASET, "bankCode", "OPERATING")).isEmpty();

        List<Map<String, Object>> accepted = proposals.stream().map(p -> Map.<String, Object>of(
            "lineId", p.get("lineId"), "items", ((List<Map<String, Object>>) p.get("items")).stream()
                .map(i -> Map.of("kind", i.get("kind"), "id", i.get("id"))).toList())).toList();
        // Something matching does not propose is not accepted: the confidence and reasons are matching's.
        Map<String, Object> wrong = Map.of("lineId", proposals.get(1).get("lineId"), "items",
            ((Map<String, Object>) accepted.get(2)).get("items"));
        assertThat(refused(MatchProcesses.ACCEPT, accountant, Map.of("bankCode", "OPERATING", "proposals",
            List.of(accepted.get(0), wrong)), 422)).isEqualTo(MatchProcesses.NOT_PROPOSED);
        assertThat(find(MatchEntities.MATCH_DATASET, "bankCode", "OPERATING")).isEmpty();
        assertThat(ok(MatchProcesses.ACCEPT, accountant, Map.of("bankCode", "OPERATING", "proposals", accepted)))
            .containsEntry("matched", 4);
        assertThat(find(MatchEntities.MATCH_DATASET, "bankCode", "OPERATING")).hasSize(4)
            .allSatisfy(m -> assertThat(m).containsEntry("method", "AUTO").containsEntry("actor", "accountant"));
        assertThat(history()).filteredOn(h -> "BNK-0002".equals(h.get("statementItems"))).singleElement()
            .satisfies(h -> {
                assertThat(amount(h.get("confidence"))).isEqualByComparingTo("85");
                assertThat(h.get("reason")).isEqualTo("amount equal; same day; name ACME ROBOTICS");
            });
        // Matched, they are no longer open, nor proposed again.
        assertThat(proposals()).isEmpty();
        assertThat(report(MatchProcesses.STATEMENT_ITEMS, accountant, Map.of("bankCode", "OPERATING")))
            .extracting(l -> l.get("bankReference")).containsExactlyInAnyOrder("BNK-0003", "BNK-0004", "BNK-0006",
                "BNK-0007", "BNK-0009", "BNK-0010");
        // Accepting one of them again is refused: the whole bulk with it.
        assertThat(refused(MatchProcesses.ACCEPT, accountant, Map.of("bankCode", "OPERATING", "proposals",
            accepted), 422)).isEqualTo(MatchProcesses.NOT_PROPOSED);
    }

    @Test
    @Order(2)
    void twoTransfersAreMatchedByHandToTheOneDebitOfTheirDay() {
        List<Map<String, Object>> transfers = bookItems().stream()
            .filter(i -> String.valueOf(i.get("documentNo")).startsWith("TRF-")).toList();
        assertThat(transfers).hasSize(2);
        List<Map<String, Object>> refs = transfers.stream().map(i -> Map.<String, Object>of("kind", i.get("refKind"),
            "id", i.get("refId"))).toList();
        // Refused: totals that differ, many to many, the same item twice, a line not open.
        assertThat(refused(MatchProcesses.MATCH, accountant, Map.of("bankCode", "OPERATING",
            "lineIds", List.of(LINES.get("BNK-0004")), "items", refs.subList(0, 1)), 422))
            .isEqualTo(MatchProcesses.UNEQUAL);
        assertThat(refused(MatchProcesses.MATCH, accountant, Map.of("bankCode", "OPERATING",
            "lineIds", List.of(LINES.get("BNK-0004"), LINES.get("BNK-0006")), "items", refs), 422))
            .isEqualTo(MatchProcesses.MANY_TO_MANY);
        assertThat(refused(MatchProcesses.MATCH, accountant, Map.of("bankCode", "OPERATING",
            "lineIds", List.of(LINES.get("BNK-0002")), "items", refs), 422)).isEqualTo(MatchProcesses.NOT_OPEN);
        assertThat(refused(MatchProcesses.MATCH, accountant, Map.of("bankCode", "SAVINGS",
            "lineIds", List.of(LINES.get("BNK-0004")), "items", refs), 422)).isEqualTo(MatchProcesses.NOT_OPEN);
        run(MatchProcesses.MATCH, as("clerk", "fin.bank.activity.read"), Map.of("bankCode", "OPERATING",
            "lineIds", List.of(LINES.get("BNK-0004")), "items", refs)).expectStatus().isForbidden();

        Map<String, Object> match = ok(MatchProcesses.MATCH, accountant, Map.of("bankCode", "OPERATING",
            "lineIds", List.of(LINES.get("BNK-0004")), "items", refs, "reason", "Two sweeps debited together"));
        assertThat(amount(match.get("amount"))).isEqualByComparingTo("-15000.00");
        assertThat(bookItems()).extracting(i -> i.get("documentNo")).doesNotContain(
            transfers.get(0).get("documentNo"), transfers.get(1).get("documentNo"));
    }

    @Test
    @Order(3)
    void theFeeAndTheInterestBecomeTheirEntriesAndAreMatchedAtOnce() {
        ok(BankEntryProcesses.RULE_SAVE, controller, Map.of("ruleCode", "FEE", "keywords", "service fee",
            "direction", "PAYMENT", "account", "6800", "documentPrefix", "BANK-FEE", "description",
            "Account service fee"));
        ok(BankEntryProcesses.RULE_SAVE, controller, Map.of("ruleCode", "INT", "keywords", "interest, loc",
            "direction", "PAYMENT", "account", "7100", "documentPrefix", "BANK-INT", "description",
            "Line of credit interest"));
        assertThat(refused(BankEntryProcesses.RULE_SAVE, controller, Map.of("ruleCode", "BAD", "keywords", "x",
            "direction", "PAYMENT", "account", "1200", "documentPrefix", "BAD"), 422))
            .isEqualTo(BankEntryProcesses.WRONG_ACCOUNT);
        run(BankEntryProcesses.RULE_SAVE, accountant, Map.of("ruleCode", "X", "keywords", "x", "direction", "ANY",
            "account", "6800", "documentPrefix", "X")).expectStatus().isForbidden();

        // FIN-BK-005 acceptance 1: the rules pick the account; the entries are posted and matched.
        Map<String, Object> fee = ok(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId",
            LINES.get("BNK-0009")));
        assertThat(fee).containsEntry("entryNo", "BANK-FEE-2601");
        Map<String, Object> interest = ok(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId",
            LINES.get("BNK-0010")));
        assertThat(interest).containsEntry("entryNo", "BANK-INT-2601");
        assertThat(postingLines("BANK-FEE-2601")).containsExactlyInAnyOrderEntriesOf(Map.of(
            "6800", new BigDecimal("45.00"), "1010", new BigDecimal("-45.00")));
        assertThat(postingLines("BANK-INT-2601")).containsExactlyInAnyOrderEntriesOf(Map.of(
            "7100", new BigDecimal("300.00"), "1010", new BigDecimal("-300.00")));
        assertThat(history()).filteredOn(h -> "ENTRY".equals(h.get("method")))
            .extracting(h -> h.get("statementItems") + " = " + h.get("bookItems"))
            .containsExactlyInAnyOrder("BNK-0009 = BANK-FEE-2601", "BNK-0010 = BANK-INT-2601");
        assertThat(report(MatchProcesses.STATEMENT_ITEMS, accountant, Map.of("bankCode", "OPERATING")))
            .extracting(l -> l.get("bankReference")).containsExactlyInAnyOrder("BNK-0003", "BNK-0006", "BNK-0007");
        // Once matched, a line cannot make a second entry.
        assertThat(refused(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId", LINES.get("BNK-0009")), 422))
            .isEqualTo(BankEntryProcesses.NOT_OPEN);
        // A line no rule takes needs an account, which only who keeps the rules chooses.
        assertThat(refused(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId", LINES.get("BNK-0006")), 422))
            .isEqualTo(BankEntryProcesses.NO_RULE);
        assertThat(refused(BankEntryProcesses.FROM_LINE, accountant, Map.of("lineId", LINES.get("BNK-0006"),
            "account", "6800"), 422)).isEqualTo(BankEntryProcesses.FREE_ACCOUNT);
    }

    @Test
    @Order(4)
    void aMatchUndoneAndMadeAgainKeepsBothInItsHistory() {
        Map<String, Object> accepted = history().stream().filter(h -> "BNK-0002".equals(h.get("statementItems"))
            && "MATCH".equals(h.get("action"))).findFirst().orElseThrow();
        // Undone: the deposit and RCPT-0001 are open again.
        Map<String, Object> undo = ok(MatchProcesses.UNMATCH, accountant, Map.of("matchId", accepted.get("matchId"),
            "reason", "Checking the remittance"));
        assertThat(undo).containsEntry("matchId", accepted.get("matchId"));
        Map<String, Object> receipt = book("RCPT-0001");
        assertThat(report(MatchProcesses.STATEMENT_ITEMS, accountant, Map.of("bankCode", "OPERATING")))
            .extracting(l -> l.get("bankReference")).contains("BNK-0002");
        assertThat(refused(MatchProcesses.UNMATCH, accountant, Map.of("matchId", accepted.get("matchId"),
            "reason", "Again"), 422)).isEqualTo(MatchProcesses.UNDONE);
        assertThat(refused(MatchProcesses.UNMATCH, accountant, Map.of("matchId", undo.get("undoId"),
            "reason", "An undo"), 422)).isEqualTo(MatchProcesses.NOT_FOUND);
        // Made again, by hand, by the controller.
        ok(MatchProcesses.MATCH, controller, Map.of("bankCode", "OPERATING", "lineIds",
            List.of(LINES.get("BNK-0002")), "items", List.of(Map.of("kind", receipt.get("refKind"),
                "id", receipt.get("refId"))), "reason", "Remittance confirmed"));
        // FIN-BK-006 acceptance 1: both actions, their people and times (the tests' clock stands still, so the
        // undo is told by what it undoes).
        List<Map<String, Object>> deposit = history().stream()
            .filter(h -> "BNK-0002".equals(h.get("statementItems"))).toList();
        assertThat(deposit).extracting(h -> h.get("action") + " " + h.get("method") + " " + h.get("actor") + " "
            + h.get("bookItems")).containsExactlyInAnyOrder("MATCH AUTO accountant RCPT-0001",
                "UNMATCH null accountant RCPT-0001", "MATCH MANUAL controller RCPT-0001");
        assertThat(deposit).allSatisfy(h -> assertThat(h.get("actionTime")).isNotNull());
        assertThat(deposit).filteredOn(h -> "UNMATCH".equals(h.get("action"))).singleElement()
            .satisfies(h -> assertThat(h).containsEntry("reversesMatchId", accepted.get("matchId"))
                .containsEntry("reason", "Checking the remittance"));
    }

    private List<Map<String, Object>> history() {
        return report("finance.bank.match_history", accountant, Map.of("bankCode", "OPERATING"));
    }

    /**
     * ROADMAP F11c: a statement of more lines than a dataset writes by default (100) is imported whole, and as many
     * proposals are accepted at once; found by the performance test, whose statements are thousands of lines.
     */
    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void aStatementOfMoreThanAHundredLinesIsImportedAndMatchedAtOnce() {
        int count = 150;
        ok(AccountProcesses.CREATE, controller(), Map.of("accountCode", "1060", "accountName", "Cash Lockbox",
            "financialType", "ASSET", "normalBalance", "DEBIT", "statementLine", "Cash and cash equivalents",
            "controlClass", "BANK"));
        ok(BankAccountProcesses.SAVE, inRoles("treasurer", FinanceRoles.TREASURER), Map.of("bankCode", "LOCKBOX",
            "bankName", "Lakeside National Bank", "glAccount", "1060", "routingNumber", "111000025",
            "companyAccountNumber", "000555000111"));
        ok(StatementProcesses.OPENING_ITEMS, as("migrator", "fin.migration", "fin.import", "fin.bank.activity.read"),
            Map.of("bankCode", "LOCKBOX", "statementBalance", "0.00", "items", List.of()));
        String clerk = inRoles("ar-clerk", FinanceRoles.RECEIVABLES_CLERK);
        StringBuilder csv = new StringBuilder("date,bank_reference,description,amount\n"
            + "2026-01-01,OPENING,OPENING LEDGER BALANCE,0.00\n");
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i <= count; i++) {
            String day = String.format("2026-01-%02d", 1 + i % 28);
            BigDecimal amount = new BigDecimal(100 + i).add(new BigDecimal(i % 100).movePointLeft(2));
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("customerCode", "C100");
            receipt.put("receiptDate", day);
            receipt.put("amount", amount.toPlainString());
            receipt.put("method", "ACH");
            receipt.put("reference", "LBX-" + i);
            receipt.put("bankAccount", "1060");
            receipt.put("applications", List.of());
            ok(ReceiptProcesses.RECORD, clerk, receipt);
            csv.append(day).append(",LBX-").append(i).append(",DEPOSIT LBX-").append(i).append(',')
                .append(amount.toPlainString()).append('\n');
            total = total.add(amount);
        }
        csv.append("2026-01-31,CLOSING,CLOSING LEDGER BALANCE,").append(total.toPlainString()).append('\n');
        String fileId = upload(accountant, "fin.bank.statement", csv.toString().getBytes(StandardCharsets.UTF_8),
            "lockbox.csv", "text/csv");
        post("/api/imports/finance.bank_statement/commit", accountant, Map.of("fileId", fileId, "params",
            Map.of("bankCode", "LOCKBOX"))).expectStatus().isOk();
        assertThat(find(StatementEntities.LINE_DATASET, "bankCode", "LOCKBOX")).hasSize(count);

        List<Map<String, Object>> accepted = ((List<Map<String, Object>>) ok(MatchProcesses.PROPOSE, accountant,
            Map.of("bankCode", "LOCKBOX")).get("proposals")).stream().map(p -> Map.<String, Object>of(
                "lineId", p.get("lineId"), "items", ((List<Map<String, Object>>) p.get("items")).stream()
                    .map(i -> Map.of("kind", i.get("kind"), "id", i.get("id"))).toList())).toList();
        assertThat(accepted).hasSize(count);
        assertThat(ok(MatchProcesses.ACCEPT, accountant, Map.of("bankCode", "LOCKBOX", "proposals", accepted)))
            .containsEntry("matched", count);
        assertThat(find(MatchEntities.MATCH_DATASET, "bankCode", "LOCKBOX")).hasSize(count);
    }

    @Test
    @Order(6)
    void theMatchTablesAreOnlyInsertedInto() {
        assertOnlyInserted("fi_bank_match_version", "fi_bank_match_item_version", "fi_bank_entry_version",
            "fi_bank_entry_rule_version");
    }
}

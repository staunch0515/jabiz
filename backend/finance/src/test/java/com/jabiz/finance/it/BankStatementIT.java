package com.jabiz.finance.it;

import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.bank.BankEntities;
import com.jabiz.finance.bank.BankSettingsProcesses;
import com.jabiz.finance.bank.StatementEntities;
import com.jabiz.finance.bank.StatementProcesses;
import com.jabiz.finance.bank.TransferEntities;
import com.jabiz.finance.bank.TransferProcesses;
import com.jabiz.finance.calc.StatementCheck;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bank accounts, transfers, the cutover's outstanding items and statements (ROADMAP F5a; FIN-BK-001…003,
 * FIN-DI-001): the operating account on 1010 and the savings account on 1050, masked but to the treasury; a transfer
 * from savings to operating, the same day and through the in-transit account; CHK-1045 outstanding at the cutover;
 * and the January statement imported as CSV, then again as CSV, BAI2 and camt.053, adding nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BankStatementIT extends FinanceItSupport {

    /** The schema lives as long as the class: the books and the bank accounts are set up once. */
    private static boolean loaded;

    private String controller;
    private String treasurer;
    private String accountant;
    private String migrator;

    @BeforeEach
    void books() {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        treasurer = inRoles("treasurer", FinanceRoles.TREASURER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        migrator = as("migrator", "fin.migration", "fin.import", "fin.bank.activity.read");
        if (loaded) {
            return;
        }
        loaded = true;
        openBooks();
        importCsv("finance.opening_balances", controller, sampleText("opening-balances.csv"), "commit", null, null,
            200);
        // The sample chart has no in-transit account (F5 plan decision D1): the controller adds one.
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "1090", "accountName", "Cash in Transit",
            "financialType", com.jabiz.finance.gl.AccountTypes.fromChart("Asset"), "normalBalance",
            com.jabiz.finance.gl.AccountTypes.normalBalanceFromChart("D"), "statementLine",
            "Cash and cash equivalents"));
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "OPERATING", "bankName",
            "Lakeside National Bank", "glAccount", "1010", "routingNumber", "111000025",
            "companyAccountNumber", "000123456789", "statementFormat", "CSV"));
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "SAVINGS", "bankName", "Lakeside National Bank",
            "glAccount", "1050", "routingNumber", "111000025", "companyAccountNumber", "000987654321"));
    }

    // ---- FIN-BK-001: one bank account per cash account, numbers masked -------------------------------------------

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void eachCashAccountHasOneBankAccountWhoseNumberOnlyTheTreasurySees() {
        assertThat(find(BankEntities.BANK_ACCOUNT_DATASET, "glAccount", "1010")).singleElement()
            .satisfies(bank -> assertThat(bank).containsEntry("bankCode", "OPERATING")
                .containsEntry("statementFormat", "CSV"));
        assertThat(find(BankEntities.BANK_ACCOUNT_DATASET, "glAccount", "1050")).singleElement()
            .satisfies(bank -> assertThat(bank).containsEntry("bankCode", "SAVINGS"));
        // Read, the number is masked for everyone; the treasury reveals it one value at a time, on the record.
        Object operatingId = null;
        for (String reader : List.of(accountant, treasurer)) {
            Map<String, Object> page = post("/api/datasets/" + BankEntities.BANK_ACCOUNT_DATASET + "/query",
                reader, Map.of("filters", List.of(Map.of("field", "bankCode", "op", "eq", "value", "OPERATING")),
                    "limit", 5)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
            Map<String, Object> item = ((List<Map<String, Object>>) page.get("items")).getFirst();
            assertThat(((Map<String, Object>) item.get("attributes")).get("accountNumber")).isEqualTo("****6789");
            operatingId = item.get("id");
        }
        post("/api/datasets/" + BankEntities.BANK_ACCOUNT_DATASET + "/reveal", accountant,
            Map.of("id", operatingId, "field", "accountNumber")).expectStatus().isForbidden();
        assertThat(post("/api/datasets/" + BankEntities.BANK_ACCOUNT_DATASET + "/reveal", treasurer,
            Map.of("id", operatingId, "field", "accountNumber")).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody()).containsEntry("value", "000123456789");
        assertThat(refused(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "PAYROLL", "bankName",
            "Lakeside National Bank", "glAccount", "1010", "routingNumber", "111000025",
            "companyAccountNumber", "000555555555"), 422)).isEqualTo(BankAccountProcesses.ACCOUNT_TAKEN);
        assertThat(refused(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "OPERATING",
            "statementFormat", "OFX"), 422)).isEqualTo(BankAccountProcesses.INVALID_FORMAT);
    }

    // ---- FIN-BK-002: transfers ---------------------------------------------------------------------------------------

    @Test
    @Order(2)
    void aTransferBetweenDaysPassesThroughTheInTransitAccountOnceTheSettingsNameOne() {
        Map<String, Object> later = Map.of("fromBank", "SAVINGS", "toBank", "OPERATING", "amount", "10000.00",
            "sentDate", "2026-01-12");
        // Without an in-transit account money cannot be on its way.
        assertThat(refused(TransferProcesses.POST, treasurer, later, 422)).isEqualTo(TransferProcesses.NO_IN_TRANSIT);
        assertThat(refused(BankSettingsProcesses.SET, controller, Map.of("inTransitAccount", "1010"), 422))
            .isEqualTo(BankSettingsProcesses.WRONG_ACCOUNT);
        assertThat(refused(BankSettingsProcesses.SET, controller, Map.of("inTransitAccount", "6800"), 422))
            .isEqualTo(BankSettingsProcesses.WRONG_ACCOUNT);
        run(BankSettingsProcesses.SET, treasurer, Map.of("inTransitAccount", "1090")).expectStatus().isForbidden();
        assertThat(ok(BankSettingsProcesses.SET, controller, Map.of("inTransitAccount", "1090")))
            .containsEntry("changed", true);
        // Setting only the window keeps the in-transit account.
        ok(BankSettingsProcesses.SET, controller, Map.of("matchWindowDays", 3));
        assertThat(find(BankEntities.SETTINGS_DATASET, "settingsKey", "BANK")).singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("inTransitAccount", "1090");
                assertThat(amount(row.get("matchWindowDays"))).isEqualByComparingTo("3");
                assertThat(amount(row.get("staleCheckDays"))).isEqualByComparingTo("90");
            });

        // Sent on the 12th, not yet received: in transit.
        Map<String, Object> sent = ok(TransferProcesses.POST, treasurer, later);
        String number = (String) sent.get("transferNo");
        assertThat(sent).containsEntry("status", TransferEntities.IN_TRANSIT);
        assertThat(find(TransferEntities.TRANSFER_DATASET, "transferNo", number)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("inTransitAccount", "1090"));
        assertThat(postingLines(number)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "1090", new BigDecimal("10000.00"), "1050", new BigDecimal("-10000.00")));
        // Received on the 13th: the money is in operating.
        assertThat(refused(TransferProcesses.RECEIVE, treasurer, Map.of("transferId", sent.get("transferId"),
            "receivedDate", "2026-01-11"), 422)).isEqualTo(TransferProcesses.DATES);
        assertThat(ok(TransferProcesses.RECEIVE, treasurer, Map.of("transferId", sent.get("transferId"),
            "receivedDate", "2026-01-13"))).containsEntry("status", TransferEntities.COMPLETED);
        assertThat(postingLines(number)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "1010", new BigDecimal("10000.00"), "1050", new BigDecimal("-10000.00")));
        assertThat(refused(TransferProcesses.RECEIVE, treasurer, Map.of("transferId", sent.get("transferId"),
            "receivedDate", "2026-01-14"), 422)).isEqualTo(TransferProcesses.WRONG_STATUS);
        // Entered in error: both entries reversed.
        assertThat(ok(TransferProcesses.VOID, treasurer, Map.of("transferId", sent.get("transferId"),
            "voidDate", "2026-01-14", "reason", "Entered twice"))).containsEntry("status", TransferEntities.VOID);
        assertThat(postingLines(number)).isEmpty();
        assertThat(refused(TransferProcesses.VOID, treasurer, Map.of("transferId", sent.get("transferId"),
            "voidDate", "2026-01-15", "reason", "Again"), 422)).isEqualTo(TransferProcesses.WRONG_STATUS);
    }

    @Test
    @Order(3)
    void fiftyThousandFromSavingsToOperatingTheSameDayIsOneEntry() {
        Map<String, Object> transfer = ok(TransferProcesses.POST, treasurer, Map.of("fromBank", "savings",
            "toBank", "OPERATING", "amount", "50000.00", "sentDate", "2026-01-10", "receivedDate", "2026-01-10",
            "description", "Funding for payroll"));
        assertThat(transfer).containsEntry("status", TransferEntities.COMPLETED);
        assertThat((List<?>) transfer.get("glNos")).hasSize(1);
        // FIN-BK-002 acceptance 1: 1050 down and 1010 up by 50,000.00, in one transaction.
        assertThat(postingLines((String) transfer.get("transferNo"))).containsExactlyInAnyOrderEntriesOf(Map.of(
            "1010", new BigDecimal("50000.00"), "1050", new BigDecimal("-50000.00")));
        assertThat(find(TransferEntities.TRANSFER_DATASET, "transferNo", transfer.get("transferNo")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("fromBank", "SAVINGS")
                .containsEntry("toBank", "OPERATING").containsEntry("preparedBy", "treasurer"));

        assertThat(refused(TransferProcesses.POST, treasurer, Map.of("fromBank", "OPERATING", "toBank",
            "OPERATING", "amount", "1.00", "sentDate", "2026-01-10"), 422)).isEqualTo(TransferProcesses.SAME_BANK);
        assertThat(refused(TransferProcesses.POST, treasurer, Map.of("fromBank", "NOPE", "toBank",
            "OPERATING", "amount", "1.00", "sentDate", "2026-01-10"), 422)).isEqualTo(TransferProcesses.UNKNOWN_BANK);
        assertThat(refused(TransferProcesses.POST, treasurer, Map.of("fromBank", "SAVINGS", "toBank",
            "OPERATING", "amount", "1.00", "sentDate", "2026-01-10", "receivedDate", "2026-01-09"), 422))
            .isEqualTo(TransferProcesses.DATES);
        run(TransferProcesses.POST, accountant, Map.of("fromBank", "SAVINGS", "toBank", "OPERATING", "amount", "1.00",
            "sentDate", "2026-01-10")).expectStatus().isForbidden();
    }

    // ---- FIN-DI-001: outstanding items at the cutover ------------------------------------------------------------------

    @Test
    @Order(4)
    void theCutoversOutstandingCheckBridgesTheStatementToTheOpeningBalance() {
        // Before the cutover is brought over, an account's statements have nothing to follow on from.
        assertThat(statementCsv(sampleText("bank-statement-2026-01.csv"), 422).toString())
            .contains(StatementProcesses.NO_CUTOVER);
        String items = "date,reference,description,amount\n2025-12-28,CHK-1045,Check 1045 to Precision Parts Co.,"
            + "-3200.00\n";
        // 253,000.00 - 3,200.00 is not the opening 250,000.00: refused whole, with the difference.
        Map<String, Object> refused = importCsv("finance.bank_opening_items", migrator, items, "commit", null,
            Map.of("bankCode", "OPERATING", "statementBalance", "253000.00"), 422);
        assertThat(refused.toString()).contains(StatementProcesses.OPENING_TOTAL, "249800.00", "250000.00");
        Map<String, Object> late = importCsv("finance.bank_opening_items", migrator,
            items + "2026-01-02,CHK-1046,Too late,-1.00\n", "commit", null,
            Map.of("bankCode", "OPERATING", "statementBalance", "253199.00"), 422);
        assertThat(late.toString()).contains(StatementProcesses.OPENING_ITEM);

        Map<String, Object> report = importCsv("finance.bank_opening_items", migrator, items, "commit", null,
            Map.of("bankCode", "OPERATING", "statementBalance", "253200.00"), 200);
        assertThat(report).containsEntry("committed", true);
        assertThat(find(StatementEntities.OPENING_DATASET, "bankCode", "OPERATING")).singleElement()
            .satisfies(opening -> {
                assertThat(opening).containsEntry("cutoverDate", "2025-12-31");
                assertThat(amount(opening.get("statementBalance"))).isEqualByComparingTo("253200.00");
                assertThat(amount(opening.get("bookBalance"))).isEqualByComparingTo("250000.00");
            });
        assertThat(find(StatementEntities.OPENING_ITEM_DATASET, "bankCode", "OPERATING")).singleElement()
            .satisfies(item -> {
                assertThat(item).containsEntry("reference", "CHK-1045").containsEntry("itemDate", "2025-12-28");
                assertThat(amount(item.get("amount"))).isEqualByComparingTo("-3200.00");
            });
        assertThat(refused(StatementProcesses.OPENING_ITEMS, migrator, Map.of("bankCode", "OPERATING",
            "statementBalance", "253200.00", "items", List.of()), 422)).isEqualTo(StatementProcesses.OPENING_DONE);
        // The savings account has nothing outstanding: its statement balance is its book balance.
        assertThat(ok(StatementProcesses.OPENING_ITEMS, migrator, Map.of("bankCode", "SAVINGS",
            "statementBalance", "100000.00", "items", List.of()))).containsEntry("items", 0);
    }

    // ---- FIN-BK-003: statements --------------------------------------------------------------------------------------

    @Test
    @Order(5)
    void theJanuaryStatementIsStoredOnceWhateverItsLayoutAndHowOftenItIsSent() {
        String csv = sampleText("bank-statement-2026-01.csv");
        Map<String, String> operating = Map.of("bankCode", "OPERATING");
        // Lines that miss the closing balance: refused whole (acceptance 3).
        Map<String, Object> wrong = statementCsv(csv.replace("-45.00,BANK-FEE-2601", "-55.00,BANK-FEE-2601"), 422);
        assertThat(issues(wrong)).contains("0:" + StatementCheck.NOT_BALANCED);
        assertThat(find(StatementEntities.STATEMENT_DATASET, "bankCode", "OPERATING")).isEmpty();

        // Acceptance 1: ten lines, the closing balance 256,555.00 checked.
        Map<String, Object> report = statementCsv(csv, 200);
        assertThat(report).containsEntry("committed", true).containsEntry("units", 1);
        assertThat(find(StatementEntities.STATEMENT_DATASET, "bankCode", "OPERATING")).singleElement()
            .satisfies(statement -> {
                assertThat(statement).containsEntry("fromDate", "2026-01-01").containsEntry("toDate", "2026-01-31")
                    .containsEntry("format", "CSV");
                assertThat(amount(statement.get("openingBalance"))).isEqualByComparingTo("253200.00");
                assertThat(amount(statement.get("closingBalance"))).isEqualByComparingTo("256555.00");
                assertThat(amount(statement.get("lineCount"))).isEqualByComparingTo("10");
            });
        List<Map<String, Object>> lines = find(StatementEntities.LINE_DATASET, "bankCode", "OPERATING");
        assertThat(lines).hasSize(10).extracting(l -> l.get("bankReference") + " " + amount(l.get("amount"))
            .toPlainString()).contains("BNK-0001 -3200.00", "BNK-0003 -32300.00", "BNK-0010 -300.00");

        // Acceptance 2: the same file again is refused as imported, and nothing is added.
        statementCsv(csv, 409);
        // The same statement as BAI2 and camt.053: recorded already, nothing added, and the user is told.
        for (String[] other : new String[][] {
            {"finance.bank_statement_bai2", "statement-2026-01.bai2", "text/plain"},
            {"finance.bank_statement_camt053", "statement-2026-01.camt053.xml", "application/xml"}}) {
            Map<String, Object> again = importFile(other[0], accountant, resource(other[1]), other[1], other[2],
                "commit", operating, 422);
            assertThat(again.toString()).as(other[0]).contains(StatementProcesses.RECORDED, "recorded already");
        }
        assertThat(find(StatementEntities.LINE_DATASET, "bankCode", "OPERATING")).hasSize(10);
        assertThat(find(StatementEntities.STATEMENT_DATASET, "bankCode", "OPERATING")).hasSize(1);
    }

    @Test
    @Order(6)
    void theBai2AndCamtLayoutsReadTheSameStatementForAnotherAccount() {
        // The savings account's statements come as camt.053; the file names the operating account: refused.
        Map<String, Object> wrongAccount = importFile("finance.bank_statement_camt053", accountant,
            resource("statement-2026-01.camt053.xml"), "s.xml", "application/xml", "commit",
            Map.of("bankCode", "SAVINGS"), 422);
        assertThat(wrongAccount.toString()).contains(StatementProcesses.WRONG_ACCOUNT);
        // Its own statement, by BAI2: opens at the cutover's 100,000.00.
        String bai2 = new String(resource("statement-2026-01.bai2"), StandardCharsets.US_ASCII)
            .replace("000123456789", "000987654321");
        String savings = "01,LAKESIDE,NORTHWIND,260201,0800,1,,,2/\n02,NORTHWIND,111000025,1,260131,2400,USD,2/\n"
            + "03,000987654321,USD,010,10000000,,Z,015,10012500,,Z/\n"
            + "16,354,12500,V,260131,,SAV-0001,,INTEREST PAID\n49,20025000,3/\n98,20025000,1,5/\n99,20025000,1,7/\n";
        Map<String, Object> report = importFile("finance.bank_statement_bai2", accountant,
            savings.getBytes(StandardCharsets.US_ASCII), "savings.bai2", "text/plain", "commit",
            Map.of("bankCode", "SAVINGS"), 200);
        assertThat(report).containsEntry("committed", true);
        assertThat(find(StatementEntities.STATEMENT_DATASET, "bankCode", "SAVINGS")).singleElement()
            .satisfies(statement -> assertThat(statement).containsEntry("format", "BAI2")
                .containsEntry("fromDate", "2026-01-31"));
        // Statements are recorded in order: with March recorded, February is refused.
        String march = "01,LAKESIDE,NORTHWIND,260401,0800,1,,,2/\n02,NORTHWIND,111000025,1,260331,2400,USD,2/\n"
            + "03,000987654321,USD,010,10012500,,Z,015,10025000,,Z/\n"
            + "16,354,12500,Z,SAV-0003,,INTEREST PAID\n49,20050000,3/\n98,20050000,1,5/\n99,20050000,1,7/\n";
        importFile("finance.bank_statement_bai2", accountant, march.getBytes(StandardCharsets.US_ASCII), "m.bai2",
            "text/plain", "commit", Map.of("bankCode", "SAVINGS"), 200);
        String february = march.replace("260401", "260301").replace("260331", "260228").replace("SAV-0003", "SAV-0002");
        Map<String, Object> late = importFile("finance.bank_statement_bai2", accountant,
            february.getBytes(StandardCharsets.US_ASCII), "f.bai2", "text/plain", "commit",
            Map.of("bankCode", "SAVINGS"), 422);
        assertThat(late.toString()).contains(StatementProcesses.OUT_OF_ORDER);
        // The operating account's file under savings: not its account.
        Map<String, Object> other = importFile("finance.bank_statement_bai2", accountant,
            bai2.replace("000987654321", "000123456789").getBytes(StandardCharsets.US_ASCII), "op.bai2", "text/plain",
            "commit", Map.of("bankCode", "SAVINGS"), 422);
        assertThat(other.toString()).contains(StatementProcesses.WRONG_ACCOUNT);
    }

    @Test
    @Order(7)
    void aStatementThatDoesNotFollowThePreviousOneOrOverlapsItIsRefused() {
        String february = "date,bank_reference,description,amount\n2026-02-01,OPENING,OPENING LEDGER BALANCE,"
            + "%s\n2026-02-05,BNK-0011,DEPOSIT ACME ROBOTICS INC,51135.00\n2026-02-28,CLOSING,CLOSING LEDGER BALANCE,"
            + "%s\n";
        // A statement missing in between: the opening is not January's closing.
        Map<String, Object> gap = statementCsv(String.format(february, "250000.00", "301135.00"), 422);
        assertThat(gap.toString()).contains(StatementProcesses.GAP);
        // A January line sent again with February.
        Map<String, Object> repeated = statementCsv(String.format(february, "256555.00", "304490.00").replace("2026-02-28,CLOSING",
                "2026-02-06,BNK-0009,ACCOUNT SERVICE FEE,-3200.00\n2026-02-28,CLOSING"), 422);
        assertThat(repeated.toString()).contains(StatementProcesses.LINE_STORED, "BNK-0009");
        // Days January covers.
        Map<String, Object> overlap = statementCsv(
            String.format(february, "256555.00", "307690.00").replace("2026-02-01,OPENING", "2026-01-31,OPENING"), 422);
        assertThat(overlap.toString()).contains(StatementProcesses.OVERLAP);
        // February as it should be.
        assertThat(statementCsv(String.format(february, "256555.00", "307690.00"), 200))
            .containsEntry("committed", true);
        assertThat(find(StatementEntities.STATEMENT_DATASET, "bankCode", "OPERATING")).hasSize(2);
        // Only who imports statements does.
        Map<String, Object> refused = importFile("finance.bank_statement", as("clerk", "fin.import"),
            "x".getBytes(StandardCharsets.UTF_8), "x.csv", "text/csv", "commit", Map.of("bankCode", "OPERATING"), 403);
        assertThat(refused).isNotNull();
    }

    @Test
    @Order(8)
    void theBankTablesAreOnlyInsertedInto() {
        assertOnlyInserted("fi_bank_settings_version", "fi_bank_transfer_version", "fi_bank_statement_version",
            "fi_statement_line_version", "fi_bank_opening_version", "fi_bank_opening_item_version",
            "fi_bank_account_version");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private Map<String, Object> statementCsv(String csv, int expectedStatus) {
        return importFile("finance.bank_statement", accountant, csv.getBytes(StandardCharsets.UTF_8), "statement.csv",
            "text/csv", "commit", Map.of("bankCode", "OPERATING"), expectedStatus);
    }

    private static byte[] resource(String name) {
        try {
            return Files.readAllBytes(Path.of("src/test/resources/bank").resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Uploads a file under the statement policy and previews or commits it; returns the import report (or the
     * problem when the call is refused before any report).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> importFile(String importId, String authorization, byte[] content, String name,
        String type, String mode, Map<String, ?> params, int expectedStatus) {
        if (expectedStatus == 403) {
            var exchange = post("/api/imports/" + importId + "/" + mode, authorization,
                Map.of("fileId", "00000000-0000-0000-0000-000000000000", "params", params)).expectBody(MAP)
                .returnResult();
            assertThat(exchange.getStatus().value()).isEqualTo(403);
            return exchange.getResponseBody();
        }
        String fileId = upload(authorization, "fin.bank.statement", content, name, type);
        var exchange = post("/api/imports/" + importId + "/" + mode, authorization,
            Map.of("fileId", fileId, "params", params)).expectBody(MAP).returnResult();
        assertThat(exchange.getStatus().value()).as(importId + " " + mode + " answered " + exchange.getResponseBody())
            .isEqualTo(expectedStatus);
        Map<String, Object> answer = exchange.getResponseBody();
        return answer.containsKey("report") ? (Map<String, Object>) answer.get("report") : answer;
    }
}

package com.jabiz.finance.it;

import com.jabiz.finance.gl.Csv;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance integration tests call the API as users would, with real access tokens holding the permissions of the
 * finance roles. The test classes share nothing: each has its own schema, and within a class every test uses data of
 * its own, since the temporal tables cannot be cleaned up.
 */
public abstract class FinanceItSupport extends PostgresIntegrationTest {

    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    protected static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    /** The requirements' sample company, read in tests only (backend/finance/CLAUDE.md section 1). */
    protected static final Path SAMPLE_COMPANY = Path.of("../../docs/finance-requirements/sample-company");

    @Autowired
    ApplicationContext context;

    @Autowired
    protected JwtService tokens;

    protected WebTestClient client;

    @BeforeEach
    protected void client() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en").build();
    }

    protected String as(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    /** A token for a user holding the permissions of finance roles, as {@code FIN_SETUP} creates them. */
    protected String inRoles(String actor, String... roles) {
        List<String> wanted = List.of(roles);
        return as(actor, com.jabiz.finance.setup.FinanceRoles.all().stream()
            .filter(role -> wanted.contains(role.code())).flatMap(role -> role.permissions().stream()).distinct()
            .toArray(String[]::new));
    }

    /** A controller of the books: may maintain accounts, periods and master data. */
    protected String controller() {
        return as("controller", "fin.account.read", "fin.account.maintain", "fin.master.read",
            "fin.dimension.maintain", "fin.fx.maintain", "fin.period.read", "fin.period.maintain",
            "fin.period.close");
    }

    protected WebTestClient.ResponseSpec run(String process, String authorization, Object input) {
        return post("/api/processes/" + process + "/latest", authorization, input);
    }

    /** Runs a process that must succeed; returns its output. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> ok(String process, String authorization, Object input) {
        var exchange = run(process, authorization, input).expectBody(MAP).returnResult();
        assertThat(exchange.getStatus().value()).as(process + " answered " + exchange.getResponseBody())
            .isEqualTo(200);
        return (Map<String, Object>) exchange.getResponseBody().get("output");
    }

    /**
     * Closes a period as its people would (FIN-PC-005): the close is started, every manual task done by a holder of
     * its permission, and the period closed by a controller; its automatic checks must pass. Returns the close.
     */
    protected Map<String, Object> closePeriod(String periodKey) {
        return closePeriod(periodKey, new String[0]);
    }

    /**
     * {@link #closePeriod(String)} without the checklist's items of the codes given: the controller makes them
     * inactive first, for books a test keeps only partly (say, a bank account it never reconciles).
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> closePeriod(String periodKey, String... without) {
        String closer = inRoles("closer", com.jabiz.finance.setup.FinanceRoles.CONTROLLER,
            com.jabiz.finance.setup.FinanceRoles.ACCOUNTANT);
        for (String code : without) {
            Map<String, Object> item = new LinkedHashMap<>(find(com.jabiz.finance.close.CloseEntities
                .TEMPLATE_DATASET, "taskCode", code).getFirst());
            item.put("active", false);
            ok("FIN_CLOSE_TEMPLATE_SAVE", closer, item);
        }
        Map<String, Object> checklist = ok("FIN_CLOSE_START", closer, Map.of("periodKey", periodKey));
        for (Map<String, Object> task : (List<Map<String, Object>>) checklist.get("tasks")) {
            if ("MANUAL".equals(task.get("kind")) && "OPEN".equals(task.get("status"))) {
                ok("FIN_CLOSE_TASK_COMPLETE", closer, Map.of("taskId", task.get("taskId")));
            }
        }
        return ok("FIN_PERIOD_CLOSE", closer, Map.of("periodKey", periodKey));
    }

    /** Runs a process that must be refused with {@code status}; returns the first violation's rule code. */
    @SuppressWarnings("unchecked")
    protected String refused(String process, String authorization, Object input, int status) {
        var exchange = run(process, authorization, input).expectBody(MAP).returnResult();
        Map<String, Object> problem = exchange.getResponseBody();
        assertThat(exchange.getStatus().value()).as(process + " answered " + problem).isEqualTo(status);
        List<Map<String, Object>> violations = (List<Map<String, Object>>) problem.get("violations");
        return violations == null || violations.isEmpty() ? String.valueOf(problem.get("title"))
            : (String) violations.getFirst().get("ruleCode");
    }

    /** A write through a dataset's generic API that must be refused; returns the first violation's rule code. */
    @SuppressWarnings("unchecked")
    protected String commitRefused(String dataset, String authorization, Map<String, Object> change) {
        Map<String, Object> problem = post("/api/datasets/" + dataset + "/commit", authorization,
            Map.of("changes", List.of(change))).expectStatus().is4xxClientError().expectBody(MAP).returnResult()
            .getResponseBody();
        return (String) ((List<Map<String, Object>>) problem.get("violations")).getFirst().get("ruleCode");
    }

    protected WebTestClient.ResponseSpec post(String path, String authorization, Object body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, authorization).bodyValue(body).exchange();
    }

    protected WebTestClient.ResponseSpec get(String path, String authorization) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, authorization).exchange();
    }

    /** The current instances of a dataset matching {@code field = value}, as the API returns them. */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> find(String dataset, String field, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filters", List.of(Map.of("field", field, "op", "eq", "value", value)));
        body.put("limit", 500);
        Map<String, Object> page = post("/api/datasets/" + dataset + "/query", as("reader", "*"), body)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> (Map<String, Object>) item.get("attributes")).toList();
    }

    /** One instance read by its key through a dataset, as the API returns its attributes; empty when absent. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> read(String dataset, Object id) {
        var exchange = get("/api/datasets/" + dataset + "/entities/" + id, as("reader", "*")).expectBody(MAP)
            .returnResult();
        return exchange.getStatus().value() == 404 ? Map.of()
            : (Map<String, Object>) exchange.getResponseBody().get("attributes");
    }

    private static final String BOUNDARY = "finance-it-boundary";

    /**
     * Uploads one file under a policy; returns its id. The multipart body is built by hand: the client's own writer
     * draws its boundary from a blocking random source, which BlockHound would report.
     */
    protected String upload(String authorization, String policy, byte[] content, String name, String type) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
            + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> uploaded = client.post().uri("/api/files?policy=" + policy)
            .header(HttpHeaders.AUTHORIZATION, authorization)
            .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
            .bodyValue(body.toByteArray())
            .exchange().expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
        return String.valueOf(uploaded.get("fileId"));
    }

    /**
     * Uploads a CSV file under the finance import policy and previews or commits it as {@code importId}; returns the
     * import report. A commit refused for problems answers 422: the report is the one carried by the problem.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> importCsv(String importId, String authorization, String csv, String mode,
        Map<String, Object> mapping, Map<String, Object> params, int expectedStatus) {
        String fileId = upload(authorization, "fin.import", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            importId + ".csv", "text/csv");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileId", fileId);
        if (mapping != null) {
            body.put("mapping", mapping);
        }
        if (params != null) {
            body.put("params", params);
        }
        var exchange = post("/api/imports/" + importId + "/" + mode, authorization, body).expectBody(MAP)
            .returnResult();
        assertThat(exchange.getStatus().value()).as(importId + " " + mode + " answered " + exchange.getResponseBody())
            .isEqualTo(expectedStatus);
        Map<String, Object> answer = exchange.getResponseBody();
        return answer.containsKey("report") ? (Map<String, Object>) answer.get("report") : answer;
    }

    /** The problems of an import report as "row:code" (row 0 for the whole file). */
    @SuppressWarnings("unchecked")
    protected static List<String> issues(Map<String, Object> report) {
        return ((List<Map<String, Object>>) report.get("issues")).stream()
            .map(issue -> (issue.get("row") == null ? 0 : issue.get("row")) + ":" + issue.get("code")).toList();
    }

    /** A sample company file as it is on disk. */
    protected static String sampleText(String file) {
        try {
            return Files.readString(SAMPLE_COMPANY.resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs an SQL template with its parameters; returns all its rows (up to 500). */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> report(String template, String authorization, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("limit", 500);
        var exchange = post("/api/queries/" + template, authorization, body).expectBody(MAP).returnResult();
        assertThat(exchange.getStatus().value()).as(template + " answered " + exchange.getResponseBody())
            .isEqualTo(200);
        return (List<Map<String, Object>>) exchange.getResponseBody().get("items");
    }

    /** Runs an SQL template with its parameters on the books as recorded at {@code knownAt}; all its rows. */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> reportKnownAt(String template, String authorization,
        Map<String, Object> params, String knownAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("knownAt", knownAt);
        body.put("limit", 500);
        var exchange = post("/api/queries/" + template, authorization, body).expectBody(MAP).returnResult();
        assertThat(exchange.getStatus().value()).as(template + " answered " + exchange.getResponseBody())
            .isEqualTo(200);
        return (List<Map<String, Object>>) exchange.getResponseBody().get("items");
    }

    /**
     * The report trial balance (finance.report.trial_balance, read from the period balance snapshots) beside the
     * ledger's (finance.gl.trial_balance, summed from the entries), with the same {@code through}, {@code knownAt},
     * {@code adjustments} and {@code closingEntries} (ROADMAP F9 decision D1): every account's closing balance the
     * same; with neither period 13 nor closing entries left out, its opening the ledger's on the day before
     * {@code from}. Returns the report's rows by account.
     */
    protected Map<String, Map<String, Object>> sameAsEntries(String authorization, String from, String through,
        Map<String, Object> options) {
        Map<String, Object> params = new LinkedHashMap<>(options);
        params.put("through", through);
        Map<String, Map<String, Object>> ledger = byAccount(report("finance.gl.trial_balance", authorization, params));
        if (from != null) {
            params.put("from", from);
        }
        Map<String, Map<String, Object>> books = byAccount(report("finance.report.trial_balance", authorization,
            params));
        assertThat(books.keySet()).isEqualTo(ledger.keySet());
        ledger.forEach((code, row) -> assertThat(amount(books.get(code).get("closing"))).as(code + " closing")
            .isEqualByComparingTo(amount(row.get("balance"))));
        if (from != null && !options.containsKey("adjustments") && !options.containsKey("closingEntries")) {
            Map<String, Object> before = new LinkedHashMap<>(options);
            before.put("through", java.time.LocalDate.parse(from).minusDays(1).toString());
            byAccount(report("finance.gl.trial_balance", authorization, before)).forEach((code, row) ->
                assertThat(amount(books.get(code).get("opening"))).as(code + " opening")
                    .isEqualByComparingTo(amount(row.get("balance"))));
        }
        return books;
    }

    private static Map<String, Map<String, Object>> byAccount(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byCode = new TreeMap<>();
        rows.forEach(r -> byCode.put((String) r.get("accountCode"), r));
        return byCode;
    }

    /** An amount as the API returns it (a JSON number, or null), for exact comparison. */
    protected static java.math.BigDecimal amount(Object value) {
        return value == null ? null : new java.math.BigDecimal(String.valueOf(value)).setScale(2);
    }

    protected static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    /** The rows of one of the sample company's files, by column name. */
    protected static List<Map<String, String>> sample(String file) {
        try {
            List<String> lines = Files.readAllLines(SAMPLE_COMPANY.resolve(file)).stream()
                .filter(line -> !line.isBlank()).toList();
            List<String> header = Csv.fields(lines.getFirst());
            List<Map<String, String>> rows = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                List<String> fields = Csv.fields(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), i < fields.size() ? fields.get(i) : "");
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A draft invoice or credit memo as {@code FIN_INVOICE_SAVE} takes it. */
    protected static Map<String, Object> invoiceInput(String customer, String date, String kind,
        List<Map<String, Object>> lines) {
        return invoiceInput(customer, date, kind, lines, null);
    }

    protected static Map<String, Object> invoiceInput(String customer, String date, String kind,
        List<Map<String, Object>> lines, String taxCode) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerCode", customer);
        input.put("invoiceDate", date);
        if (kind != null) {
            input.put("kind", kind);
        }
        if (taxCode != null) {
            input.put("taxCode", taxCode);
        }
        input.put("lines", lines);
        return input;
    }

    protected static Map<String, Object> invoiceLine(String description, String quantity, String price,
        String account, String taxCode) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("unitPrice", price);
        if (account != null) {
            line.put("revenueAccount", account);
        }
        if (taxCode != null) {
            line.put("taxCode", taxCode);
        }
        return line;
    }

    /** The control accounts of the sample company: its chart file does not mark them (FIN-GL-005). */
    protected static final Map<String, String> SAMPLE_CONTROL = Map.of("1010", "BANK", "1050", "BANK", "1200", "AR",
        "2000", "AP", "1500", "FA_COST", "1510", "FA_COST", "1520", "FA_COST", "1590", "FA_ACCUM");

    /** Enters the sample company's 36 accounts through FIN_ACCOUNT_CREATE, as a controller would. */
    protected void loadSampleChart() {
        for (Map<String, String> row : sample("chart-of-accounts.csv")) {
            Map<String, Object> input = new java.util.HashMap<>();
            input.put("accountCode", row.get("code"));
            input.put("accountName", row.get("name"));
            input.put("financialType", com.jabiz.finance.gl.AccountTypes.fromChart(row.get("type")));
            input.put("normalBalance", com.jabiz.finance.gl.AccountTypes.normalBalanceFromChart(
                row.get("normal_balance")));
            input.put("statementLine", row.get("statement_line"));
            input.put("controlClass", SAMPLE_CONTROL.get(row.get("code")));
            ok("FIN_ACCOUNT_CREATE", controller(), input);
        }
    }

    /**
     * Books ready for journal entries: the sample chart, fiscal year 2026 with its adjustment period, the finance
     * roles, the journal approval rule (proposed by the administrator, published by a controller) and two
     * departments.
     */
    protected void openBooks() {
        openBooksHolding();
    }

    /**
     * {@link #openBooks()}, publishing every rule {@code FIN_SETUP} proposed but those of the codes given; returns
     * the held changes by rule code, for a test that publishes them later.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, String> openBooksHolding(String... held) {
        loadSampleChart();
        ok("FIN_FISCAL_YEAR_CREATE", controller(), Map.of("fiscalYear", 2026, "adjustmentPeriod", true));
        Map<String, Object> setup = ok("FIN_SETUP", as("sysadmin", "fin.setup"), Map.of());
        Map<String, String> holding = new LinkedHashMap<>();
        ((Map<String, String>) setup.get("proposedChanges")).forEach((code, changeId) -> {
            if (List.of(held).contains(code)) {
                holding.put(code, changeId);
            } else {
                ok("CONTROL_CHANGE_PUBLISH", as("controller-2", "control.publish"), Map.of("changeId", changeId));
            }
        });
        for (String department : List.of("ADMIN", "SALES")) {
            post("/api/datasets/" + com.jabiz.finance.gl.GlEntities.DEPARTMENT_DATASET + "/commit",
                as("controller", "fin.dimension.maintain"), Map.of("changes", List.of(Map.of("action", "INSERT",
                    "attributes", Map.of("departmentCode", department, "departmentName", department,
                        "active", true))))).expectStatus().isOk();
        }
        return holding;
    }

    /**
     * Books open for receivables (as {@link ReceivablesIT} has them): {@link #openBooks()}, the euro and its rates,
     * the opening balances, the sample tax codes and customers, an unapplied cash and a sales discount account, the
     * receivables settings and the legacy open items INV-1001 to INV-1003.
     */
    protected void openReceivables() {
        openReceivablesHolding();
    }

    /** {@link #openReceivables()}, holding the setup's changes of the rule codes given, as {@link #openBooksHolding}. */
    protected Map<String, String> openReceivablesHolding(String... held) {
        Map<String, String> holding = openBooksHolding(held);
        String controller = inRoles("controller", com.jabiz.finance.setup.FinanceRoles.CONTROLLER);
        post("/api/datasets/" + com.jabiz.finance.gl.GlEntities.CURRENCY_DATASET + "/commit", controller(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("currencyCode", "EUR",
                "currencyName", "Euro", "minorUnits", 2, "active", true))))).expectStatus().isOk();
        importCsv("finance.fx_rates", controller, sampleText("fx-rates.csv"), "commit",
            Map.of("columns", Map.of("rateDate", "date", "rate", "eur_usd"),
                "constants", Map.of("fromCurrency", "EUR", "toCurrency", "USD")), null, 200);
        importCsv("finance.opening_balances", controller, sampleText("opening-balances.csv"), "commit", null, null,
            200);
        importCsv("finance.tax_codes", controller, sampleText("tax-codes.csv"), "commit", null,
            Map.of("ratesFrom", "2025-01-01"), 200);
        importCsv("finance.customers", controller, sampleText("customers.csv"), "commit", null, null, 200);
        // The sample chart has no unapplied cash or sales discount account (design Q4): the controller adds them.
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "1250", "accountName", "Unapplied Cash",
            "financialType", com.jabiz.finance.gl.AccountTypes.fromChart("Liability"), "normalBalance",
            com.jabiz.finance.gl.AccountTypes.normalBalanceFromChart("C"), "statementLine", "Accrued liabilities",
            "clearing", true));
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "4950", "accountName", "Sales Discounts",
            "financialType", com.jabiz.finance.gl.AccountTypes.fromChart("Revenue"), "normalBalance",
            com.jabiz.finance.gl.AccountTypes.normalBalanceFromChart("D"), "statementLine", "Revenue"));
        ok("FIN_AR_SETTINGS_SET", controller, Map.of("receivableAccount", "1200", "allowanceAccount", "1210",
            "returnsAccount", "4900", "salesTaxAccount", "2200", "discountAccount", "4950",
            "unappliedCashAccount", "1250", "lossRateCurrent", "1", "lossRate1", "5"));
        importCsv("finance.open_receivables", controller, sampleText("open-receivables.csv"), "commit", null, null,
            200);
        return holding;
    }

    /**
     * The company's profile as the controller keeps it: the sample gives the name and the bank (20-sample-company
     * section 1), the address and remittance details are made up for the tests.
     */
    protected void companyProfile() {
        ok("FIN_COMPANY_PROFILE_SET", inRoles("controller", com.jabiz.finance.setup.FinanceRoles.CONTROLLER),
            Map.of("legalName", "Northwind Components, Inc.", "street", "500 Congress Avenue", "city", "Austin",
                "state", "TX", "postalCode", "78701", "country", "United States", "phone", "+1 512 555 0100",
                "email", "billing@northwind.example", "remittance", "ACH or wire to Lakeside National Bank, "
                    + "account 000123456789, routing 111000025.\nPlease quote the invoice number."));
    }

    /** The PDF of an issued document exactly as kept. */
    protected byte[] documentPdf(String runId, String authorization) {
        return get("/api/documents/runs/" + runId + "/pdf", authorization).expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
    }

    /** The text of a PDF, as a reader would copy it. */
    protected static String pdfText(byte[] pdf) {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The platform's open export ({@code POST /api/exports/data}) as it comes: the ZIP's bytes. */
    protected byte[] exportZip(String authorization, Map<String, Object> request) {
        return post("/api/exports/data", authorization, request).expectStatus().isOk().expectBody(byte[].class)
            .returnResult().getResponseBody();
    }

    /** The files of a ZIP by their paths. */
    protected static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
            new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    protected static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The finance tables are append-only: no UPDATE or DELETE ever succeeded on them (the statistics count none),
     * and each carries the platform's guard trigger.
     */
    protected static void assertOnlyInserted(String... tables) {
        for (String table : tables) {
            List<Map<String, Object>> stats = query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table);
            assertThat(stats).as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table + " guard").isNotEmpty();
        }
    }

    /** The ledger's lines of a posted entry: account code to signed amount, debits positive. */
    protected static Map<String, BigDecimal> ledgerLines(String journalNo) {
        Map<String, BigDecimal> lines = new TreeMap<>();
        for (Map<String, Object> row : query("""
            SELECT DISTINCT a.account_code, e.entry_id::text AS entry, e.direction, e.amount
            FROM fi_journal_version j
            JOIN ledger_entry_version e ON e.transaction_id = j.transaction_id
            JOIN ledger_account_version a ON a.account_id = e.account_id
            WHERE j.journal_no = ? AND j.transaction_id IS NOT NULL""", journalNo)) {
            BigDecimal amount = ((BigDecimal) row.get("amount")).setScale(2);
            lines.merge((String) row.get("account_code"),
                "DEBIT".equals(row.get("direction")) ? amount : amount.negate(), BigDecimal::add);
        }
        return lines;
    }

    /**
     * The ledger's lines of everything posted for a subledger document ({@code FinPosting.documentNo}): account code
     * to signed amount, debits positive; a void nets the document out.
     */
    protected static Map<String, BigDecimal> postingLines(String documentNo) {
        Map<String, BigDecimal> lines = new TreeMap<>();
        for (Map<String, Object> row : query("""
            SELECT DISTINCT a.account_code, e.entry_id::text AS entry, e.direction, e.amount
            FROM fi_posting_version p
            JOIN ledger_entry_version e ON e.transaction_id = p.transaction_id
            JOIN ledger_account_version a ON a.account_id = e.account_id
            WHERE p.document_no = ?""", documentNo)) {
            BigDecimal amount = ((BigDecimal) row.get("amount")).setScale(2);
            lines.merge((String) row.get("account_code"),
                "DEBIT".equals(row.get("direction")) ? amount : amount.negate(), BigDecimal::add);
        }
        lines.values().removeIf(v -> v.signum() == 0);
        return lines;
    }

    /**
     * FIN-EXP-02's journal rows whose document matches {@code document} (a regular expression):
     * "2100 15,000.00; 1010 (15,000.00)", amounts in parentheses being credits.
     */
    protected static Map<String, Map<String, BigDecimal>> expectedDocuments(String document) throws IOException {
        return expectedDocuments(document, "journal");
    }

    /** As {@link #expectedDocuments(String)}, of documents of the kind given ("invoice", "credit memo"). */
    protected static Map<String, Map<String, BigDecimal>> expectedDocuments(String document, String kind)
        throws IOException {
        Pattern row = Pattern.compile("^\\| [0-9-]+ \\| (" + document + ") \\| " + kind
            + " \\| [^|]* \\| ([^|]+) \\|");
        Pattern part = Pattern.compile("(\\d{4}) (\\(?)([0-9,]+\\.\\d{2})\\)?");
        Map<String, Map<String, BigDecimal>> journals = new TreeMap<>();
        for (String text : Files.readAllLines(SAMPLE_COMPANY.resolveSibling("21-expected-results.md"))) {
            Matcher m = row.matcher(text);
            if (m.find()) {
                Map<String, BigDecimal> lines = new TreeMap<>();
                Matcher p = part.matcher(m.group(2));
                while (p.find()) {
                    BigDecimal amount = new BigDecimal(p.group(3).replace(",", ""));
                    lines.put(p.group(1), p.group(2).isEmpty() ? amount : amount.negate());
                }
                journals.put(m.group(1), lines);
            }
        }
        return journals;
    }

    /**
     * The rows of a table of {@code 21-expected-results.md} in the section of {@code expected} ("FIN-EXP-11"): each
     * row's cells, trimmed, the header and its rule left out.
     */
    protected static List<String[]> expectedRows(String expected) throws IOException {
        List<String[]> rows = new java.util.ArrayList<>();
        boolean inSection = false;
        boolean header = true;
        for (String text : Files.readAllLines(SAMPLE_COMPANY.resolveSibling("21-expected-results.md"))) {
            if (text.startsWith("## ")) {
                inSection = text.contains("(" + expected + ")");
                header = true;
                continue;
            }
            if (!inSection || !text.startsWith("| ")) {
                continue;
            }
            if (header || text.startsWith("|---")) {
                header = false;
                continue;
            }
            String[] cells = text.split("\\|", -1);
            String[] trimmed = new String[cells.length - 2];
            for (int i = 1; i < cells.length - 1; i++) {
                trimmed[i - 1] = cells[i].trim();
            }
            rows.add(trimmed);
        }
        return rows;
    }

    /** FIN-EXP-01 as {@code 21-expected-results.md} writes it: account, debit, credit. */
    protected static Map<String, BigDecimal[]> expectedOpeningTrialBalance() throws IOException {
        Map<String, BigDecimal[]> expected = new LinkedHashMap<>();
        for (String[] row : expectedRows("FIN-EXP-01")) {
            if (row[0].matches("\\d{4}")) {
                expected.put(row[0], new BigDecimal[] {money(row[2]), money(row[3])});
            }
        }
        return expected;
    }

    /** An amount of the expected results: 1,234.56, empty as zero, a dash as zero. */
    protected static BigDecimal money(String cell) {
        String text = cell.trim().replace(",", "");
        return text.isEmpty() || "—".equals(text) ? BigDecimal.ZERO : new BigDecimal(text);
    }

    /** The trial balance on the day: account to balance, debits positive, summaries and accounts at zero left out. */
    protected Map<String, BigDecimal> ledgerBalances(String day) {
        Map<String, BigDecimal> balances = new TreeMap<>();
        for (Map<String, Object> row : report("finance.gl.trial_balance", as("reader", "ledger.read"),
            Map.of("through", day))) {
            BigDecimal balance = amount(row.get("debit")).subtract(amount(row.get("credit")));
            if (balance.signum() != 0 && !Boolean.TRUE.equals(row.get("summary"))) {
                balances.put((String) row.get("accountCode"), balance);
            }
        }
        return balances;
    }
}

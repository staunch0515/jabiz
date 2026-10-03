package com.jabiz.finance.config;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A configuration package (FIN-SC-005; ROADMAP F10d): the chart of accounts, the tax jurisdictions, rates and codes,
 * the latest version of each statement layout and the report settings of one environment, as one JSON text with its
 * SHA-256. The text is written the same way for the same configuration (fields in a fixed order, lists sorted by
 * their codes, no spaces), so its hash names the configuration; the environment that receives it checks the hash on
 * the text as given before reading it.
 */
public record ConfigPackage(String format, String source, Instant exportedAt, List<Account> accounts,
    List<Jurisdiction> jurisdictions, List<Rate> rates, List<TaxCode> taxCodes, List<Layout> layouts,
    ReportSettings reportSettings) {

    public static final String FORMAT = "jabiz-finance-config/1";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    /** An account: its finance classification and, from the ledger, its name, parent, summary flag and state. */
    public record Account(String accountCode, String accountName, String financialType, String normalBalance,
        String statementLine, String cashFlowClass, String controlClass, boolean clearing, String requiredDimension,
        String parentCode, boolean summary, boolean active) {
        public Account {
            required(accountCode, "accountCode");
            required(financialType, "financialType of " + accountCode);
        }
    }

    public record Jurisdiction(String jurisdictionCode, String jurisdictionName, String level, String state,
        boolean active) {
        public Jurisdiction {
            required(jurisdictionCode, "jurisdictionCode");
        }
    }

    /** A rate from a day; the day before the next rate of the jurisdiction ends it. */
    public record Rate(String jurisdictionCode, LocalDate effectiveFrom, BigDecimal ratePercent) {
        public Rate {
            required(jurisdictionCode, "jurisdictionCode of a rate");
            required(effectiveFrom, "effectiveFrom of a rate of " + jurisdictionCode);
            required(ratePercent, "ratePercent of a rate of " + jurisdictionCode);
        }
    }

    /** @param jurisdictions a taxable code's jurisdictions, in order */
    public record TaxCode(String taxCode, String description, String kind, String reason, String state,
        List<String> jurisdictions, boolean certificateRequired, String chargeCode, boolean active) {
        public TaxCode {
            required(taxCode, "taxCode");
            required(kind, "kind of " + taxCode);
            jurisdictions = jurisdictions == null ? List.of() : List.copyOf(jurisdictions);
        }
    }

    public record Layout(String layoutCode, String statement, String title, List<Row> rows) {
        public Layout {
            required(layoutCode, "layoutCode");
            required(statement, "statement of " + layoutCode);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record Row(String lineCode, String label, String kind, String accounts, int sign, boolean detail,
        boolean omitZero, String noteAccounts) {
        public Row {
            required(lineCode, "lineCode of a layout row");
        }
    }

    /** The accounts behind the statement of cash flows and the note schedules, each as code ranges. */
    public record ReportSettings(String interestAccounts, String interestPayableAccounts, String incomeTaxAccounts,
        String incomeTaxPayableAccounts, String receivablesAccounts, String accruedAccounts, String debtAccounts) {}

    public ConfigPackage {
        accounts = sorted(accounts, Comparator.comparing(Account::accountCode));
        jurisdictions = sorted(jurisdictions, Comparator.comparing(Jurisdiction::jurisdictionCode));
        rates = sorted(rates, Comparator.comparing(Rate::jurisdictionCode).thenComparing(Rate::effectiveFrom));
        taxCodes = sorted(taxCodes, Comparator.comparing(TaxCode::taxCode));
        layouts = sorted(layouts, Comparator.comparing(Layout::layoutCode));
    }

    /** The package's text: the same configuration always gives the same text. */
    public String text() {
        return JSON.writeValueAsString(this);
    }

    /** SHA-256 (hex) of a package's text as given, its line ends made LF and its ends trimmed. */
    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The text a form may have changed only in its line ends and surrounding blanks, as it was written. */
    public static String normalized(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    /** Reads a package's text; fails with {@link IllegalArgumentException} when it is not one. */
    public static ConfigPackage read(String text) {
        ConfigPackage read;
        try {
            read = JSON.readValue(normalized(text), ConfigPackage.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not a configuration package: " + e.getOriginalMessage(), e);
        }
        if (read == null) {
            throw new IllegalArgumentException("Not a configuration package: empty");
        }
        if (!FORMAT.equals(read.format())) {
            throw new IllegalArgumentException("Not a configuration package of format " + FORMAT + ": "
                + read.format());
        }
        if (read.reportSettings() == null) {
            throw new IllegalArgumentException("The package has no report settings");
        }
        return read;
    }

    /** A value without which the package cannot be read; a constructor's failure is the reader's refusal. */
    private static void required(Object value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("The package has no " + what);
        }
    }

    private static <T> List<T> sorted(List<T> items, Comparator<T> order) {
        if (items == null) {
            return List.of();
        }
        if (items.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("A package list holds an empty item");
        }
        return items.stream().sorted(order).toList();
    }
}

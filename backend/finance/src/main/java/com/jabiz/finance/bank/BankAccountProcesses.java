package com.jabiz.finance.bank;

import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BankNumbers;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.MfaRequirement;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code FIN_BANK_ACCOUNT_SAVE}: a company bank account, new or changed, kept by the treasurer with a second factor
 * (F4 plan decisions D3, D7). Its ledger account is a {@code BANK} control account in the account's currency; the
 * routing number has a valid check digit; a masked account number is never taken as a new one, and an existing
 * account keeps its number unless a new one is given. A ledger account belongs to one bank account only (FIN-BK-001):
 * its reconciliation is that account's.
 */
public final class BankAccountProcesses {

    public static final String SAVE = "FIN_BANK_ACCOUNT_SAVE";

    public static final String MISSING = "FIN_BANK_MISSING_VALUE";
    public static final String WRONG_ACCOUNT = "FIN_BANK_WRONG_ACCOUNT";
    public static final String UNKNOWN_CURRENCY = "FIN_BANK_UNKNOWN_CURRENCY";
    public static final String INVALID_ROUTING = "FIN_BANK_ROUTING";
    public static final String INVALID_NUMBER = "FIN_BANK_ACCOUNT_NUMBER";
    public static final String ACCOUNT_TAKEN = "FIN_BANK_ACCOUNT_TAKEN";
    public static final String INVALID_FORMAT = "FIN_BANK_STATEMENT_FORMAT";

    /**
     * @param companyAccountNumber the account number (a distinctive name: {@code @Sensitive} masks it everywhere);
     *                             an existing account keeps its number when absent
     * @param achCompanyId         the company identification the bank assigned for ACH files
     * @param achCompanyName       the company name as ACH files carry it, up to 16 characters
     * @param nextCheckNo          the number of the next check of the stock
     * @param statementFormat      {@code CSV}, {@code BAI2} or {@code CAMT053}: the layout its statements come in
     */
    public record BankInput(@NotBlank @Size(max = 20) String bankCode, @Size(max = 100) String bankName,
        @Size(max = 20) String glAccount, @Size(max = 3) String currency, @Size(max = 20) String routingNumber,
        @Sensitive @Size(max = 40) String companyAccountNumber,
        @Size(max = 10) @Pattern(regexp = "[A-Za-z0-9 ]*") String achCompanyId,
        @Size(max = 16) @Pattern(regexp = "[A-Za-z0-9 .,&-]*") String achCompanyName,
        @Min(1) @Max(9_999_999_999L) Long nextCheckNo, Boolean active, @Size(max = 10) String statementFormat) {

        @Override
        public String toString() {
            return "BankInput[bankCode=" + bankCode + ", bankName=" + bankName + ", glAccount=" + glAccount
                + ", currency=" + currency + ", routingNumber=" + routingNumber + ", companyAccountNumber=***"
                + ", achCompanyId=" + achCompanyId + ", achCompanyName=" + achCompanyName + ", nextCheckNo="
                + nextCheckNo + ", active=" + active + ", statementFormat=" + statementFormat + "]";
        }
    }

    public record BankOutput(String bankAccountId, String bankCode, boolean created, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String BANKS = "banks";
    static final String ACCOUNTS = "accounts";
    static final String CURRENCIES = "currencies";
    static final String SAME_ACCOUNT = "sameAccount";

    public static final ProcessDefinition<BankInput, BankOutput, ProcessContext> SAVE_PROCESS =
        ProcessDefinition.define(SAVE, 1, BankInput.class, BankOutput.class, ProcessContext.class, pb -> pb
            .description("Creates or changes a company bank account that payments are made from.")
            .permissions(FinancePermissions.BANK_MAINTAIN)
            .requiresMfa(MfaRequirement.ALWAYS)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, BankOutput.class))
            .step("Load the bank account", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET,
                ctx -> eq("bankCode", code(input(ctx).bankCode())), BANKS))
            .step("Load the ledger account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> eq("accountCode", trim(input(ctx).glAccount())), ACCOUNTS))
            .step("Load the currency", QueryEntities.of(GlEntities.CURRENCY_DATASET,
                ctx -> eq("currencyCode", code(input(ctx).currency())), CURRENCIES))
            .step("Look for another bank account of the ledger account", QueryEntities.of(
                BankEntities.BANK_ACCOUNT_DATASET, ctx -> {
                    String glAccount = trim(input(ctx).glAccount());
                    // Two at most: the account itself and another.
                    return EntityQuery.builder().where(new QueryPredicate.In("glAccount",
                        glAccount == null ? List.of() : List.of(glAccount))).limit(2).build();
                }, SAME_ACCOUNT))
            .compute("Save the bank account", (metadata, ctx) -> save(ctx)));

    @SuppressWarnings("unchecked")
    static void save(ProcessContext ctx) {
        BankInput input = input(ctx);
        String bankCode = code(input.bankCode());
        List<EntityInstance> banks = (List<EntityInstance>) ctx.get(BANKS);
        EntityInstance current = banks == null || banks.isEmpty() ? null : banks.getFirst();
        if (current == null) {
            for (var required : new Object[][] {{"bankName", input.bankName()}, {"glAccount", input.glAccount()},
                {"routingNumber", input.routingNumber()}, {"companyAccountNumber", input.companyAccountNumber()}}) {
                if (required[1] == null || required[1].toString().isBlank()) {
                    ctx.reject(new Violation((String) required[0], MISSING, "A new bank account needs " + required[0],
                        Map.of("field", required[0])));
                }
            }
        }
        String glAccount = trim(input.glAccount());
        if (glAccount != null) {
            List<EntityInstance> accounts = (List<EntityInstance>) ctx.get(ACCOUNTS);
            EntityInstance account = accounts == null || accounts.isEmpty() ? null : accounts.getFirst();
            if (account == null || !"BANK".equals(account.get("controlClass"))) {
                ctx.reject(new Violation("glAccount", WRONG_ACCOUNT, "A bank account's ledger account is a BANK "
                    + "control account; " + glAccount + " is not", Map.of("accountCode", glAccount)));
            }
        }
        if (glAccount != null && list(ctx, SAME_ACCOUNT).stream()
            .anyMatch(other -> !bankCode.equals(other.get("bankCode")))) {
            ctx.reject(new Violation("glAccount", ACCOUNT_TAKEN, "Ledger account " + glAccount + " is another bank "
                + "account's", Map.of("accountCode", glAccount)));
        }
        String format = code(input.statementFormat());
        if (format != null && !List.of(BankEntities.CSV, BankEntities.BAI2, BankEntities.CAMT053).contains(format)) {
            ctx.reject(new Violation("statementFormat", INVALID_FORMAT, "A statement format is CSV, BAI2 or CAMT053",
                Map.of("value", format)));
        }
        String currency = code(input.currency());
        List<EntityInstance> currencies = (List<EntityInstance>) ctx.get(CURRENCIES);
        if (currency != null && (currencies == null
            || currencies.stream().noneMatch(c -> Boolean.TRUE.equals(c.get("active"))))) {
            ctx.reject(new Violation("currency", UNKNOWN_CURRENCY, "There is no active currency " + currency,
                Map.of("currency", currency)));
        }
        String routing = input.routingNumber() == null ? null : BankNumbers.compact(input.routingNumber().trim());
        if (routing != null && !BankNumbers.validRouting(routing)) {
            ctx.reject(new Violation("routingNumber", INVALID_ROUTING, "A routing number is nine digits with a valid "
                + "check digit", Map.of()));
        }
        String number = null;
        if (input.companyAccountNumber() != null && !input.companyAccountNumber().isBlank()) {
            number = BankNumbers.compact(input.companyAccountNumber().trim());
            if (MaskStyle.looksMasked(input.companyAccountNumber().trim()) || !BankNumbers.validAccount(number)) {
                ctx.reject(new Violation("companyAccountNumber", INVALID_NUMBER, "An account number is 4 to 17 digits, "
                    + "entered whole", Map.of()));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        put(values, "bankName", trim(input.bankName()));
        put(values, "glAccount", glAccount);
        put(values, "currency", currency == null && current == null ? "USD" : currency);
        put(values, "routingNumber", routing);
        put(values, "accountNumber", number);
        if (input.achCompanyId() != null) {
            values.put("achCompanyId", trim(input.achCompanyId()));
        }
        if (input.achCompanyName() != null) {
            values.put("achCompanyName", trim(input.achCompanyName()));
        }
        if (input.nextCheckNo() != null) {
            values.put("nextCheckNo", BigDecimal.valueOf(input.nextCheckNo()));
        }
        if (format != null) {
            values.put("statementFormat", format);
        }
        if (input.active() != null || current == null) {
            values.put("active", !Boolean.FALSE.equals(input.active()));
        }
        if (current == null) {
            values.put("bankCode", bankCode);
            Object id = ctx.changes().insert(BankEntities.BANK_ACCOUNT, values);
            ctx.put(OUTPUT, new BankOutput(String.valueOf(id), bankCode, true, true));
            return;
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            Object stored = current.get(field);
            boolean same = stored instanceof BigDecimal a && value instanceof BigDecimal b ? a.compareTo(b) == 0
                : Objects.equals(stored == null ? null : stored.toString(), value == null ? null : value.toString());
            if (!same) {
                changes.put(field, value);
            }
        });
        if (!changes.isEmpty()) {
            ctx.changes().update(BankEntities.BANK_ACCOUNT, current.id(), current.version(), changes);
        }
        ctx.put(OUTPUT, new BankOutput(String.valueOf(current.id()), bankCode, false, !changes.isEmpty()));
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private static void put(Map<String, Object> values, String field, Object value) {
        if (value != null) {
            values.put(field, value);
        }
    }

    static EntityQuery eq(String field, String value) {
        if (value == null) {
            return EntityQuery.builder().where(new QueryPredicate.In(field, List.of())).limit(1).build();
        }
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, value)).limit(1).build();
    }

    static String code(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BankInput input(ProcessContext ctx) {
        return ctx.get(INPUT, BankInput.class);
    }

    static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private BankAccountProcesses() {}
}

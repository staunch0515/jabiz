package com.jabiz.finance.config;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.report.CashFlowProcesses;
import com.jabiz.finance.report.StatementEntities;
import com.jabiz.finance.report.StatementProcesses;
import com.jabiz.finance.tax.TaxProcesses;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.GeneratedFileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Configuration promotion (FIN-SC-005; ROADMAP F10 decision D10, F10d), from a test environment to production:
 * <ul>
 *   <li>{@code FIN_CONFIG_EXPORT}: the chart of accounts, the tax jurisdictions, rates and codes, the latest version
 *       of each statement layout and the report settings as one package, kept as a generated file with its
 *       SHA-256;</li>
 *   <li>{@code FIN_CONFIG_IMPORT_PROPOSE}: in the receiving environment, the package's text and the SHA-256 handed
 *       over with it; the hash is checked, the differences listed and kept with the package;</li>
 *   <li>{@code FIN_CONFIG_IMPORT_PUBLISH}: another person applies it, the differences found again against the
 *       environment as it is then, each change through the process that makes it by hand (accounts, tax, layouts,
 *       settings), so the same rules hold; one refused change refuses them all. Who published it and when is
 *       recorded, and each change is in the audit trail;</li>
 *   <li>{@code FIN_CONFIG_IMPORT_WITHDRAW}: a proposal not published is withdrawn.</li>
 * </ul>
 * Accounts and rates this environment has and the package lacks are listed, never removed (F10d, as decided).
 */
public final class ConfigProcesses {

    public static final String EXPORT = "FIN_CONFIG_EXPORT";
    public static final String PROPOSE = "FIN_CONFIG_IMPORT_PROPOSE";
    public static final String PUBLISH = "FIN_CONFIG_IMPORT_PUBLISH";
    public static final String WITHDRAW = "FIN_CONFIG_IMPORT_WITHDRAW";

    public static final String HASH_MISMATCH = "FIN_CONFIG_HASH_MISMATCH";
    public static final String INVALID = "FIN_CONFIG_PACKAGE_INVALID";
    public static final String NO_CHANGES = "FIN_CONFIG_NO_CHANGES";
    public static final String NOT_FOUND = "FIN_CONFIG_IMPORT_NOT_FOUND";
    public static final String NOT_PROPOSED = "FIN_CONFIG_IMPORT_NOT_PROPOSED";
    public static final String SAME_PERSON = "FIN_CONFIG_SAME_PERSON";

    /** At most this many rows of each kind are read (the reading datasets' batch): more is refused rather than cut. */
    public static final int CAP = 20_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** @param source what the receiving environment calls this one ("test") */
    public record ExportInput(@NotBlank @Size(max = 100) String source) {}

    public record ExportOutput(String fileId, String fileName, String sha256, int bytes, int accounts,
        int jurisdictions, int rates, int taxCodes, int layouts) {}

    /**
     * @param packageText the package's text, as exported; kept out of the operation's summary for its size
     * @param sha256      the hash handed over with it, outside the system
     */
    public record ProposeInput(@NotNull @Sensitive @Size(max = ConfigEntities.MAX_PACKAGE) String packageText,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256) {}

    public record ProposeOutput(String importId, String source, String packageHash, long changes,
        List<ConfigDiff.Difference> differences) {}

    public record ImportId(@NotBlank String importId) {}

    public record PublishOutput(String importId, String status, long changes, List<ConfigDiff.Difference> applied,
        String publishedBy, Instant publishedAt) {}

    public record WithdrawOutput(String importId, String status) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String FIN_ACCOUNTS = "finAccounts";
    static final String LEDGER_ACCOUNTS = "ledgerAccounts";
    static final String JURISDICTIONS = "jurisdictions";
    static final String RATES = "rates";
    static final String CODES = "codes";
    static final String LAYOUTS = "layouts";
    static final String ROWS = "rows";
    static final String SETTINGS = "settings";
    static final String IMPORTS = "imports";
    static final String ARCHIVE_INPUT = "archiveInput";
    static final String ARCHIVED = "archived";
    static final String PACKAGE = "package";
    static final String DIFF = "diff";

    public static final ProcessDefinition<ExportInput, ExportOutput, ProcessContext> EXPORT_PROCESS =
        ProcessDefinition.define(EXPORT, 1, ExportInput.class, ExportOutput.class, ProcessContext.class, pb ->
            loadConfiguration(pb
                .description("Exports the chart of accounts, tax, statement layouts and report settings as a package.")
                .permissions(FinancePermissions.CONFIG_EXPORT)
                .contextFactory(ConfigProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, ExportOutput.class)))
                .compute("Write the package", (metadata, ctx) -> writePackage(ctx))
                .step("Keep it as a file", CallProcess.when(ctx -> ctx.contains(ARCHIVE_INPUT),
                    GeneratedFileProcesses.ARCHIVE, 1, ctx -> ctx.get(ARCHIVE_INPUT), ARCHIVED))
                .compute("Answer", (metadata, ctx) -> answerExport(ctx)));

    public static final ProcessDefinition<ProposeInput, ProposeOutput, ProcessContext> PROPOSE_PROCESS =
        ProcessDefinition.define(PROPOSE, 1, ProposeInput.class, ProposeOutput.class, ProcessContext.class, pb ->
            loadConfiguration(pb
                .description("Proposes a configuration package from another environment, with its differences.")
                .permissions(FinancePermissions.CONFIG_PROMOTE)
                .contextFactory(ConfigProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, ProposeOutput.class)))
                .compute("Check the package and find its differences", (metadata, ctx) -> propose(ctx)));

    public static final ProcessDefinition<ImportId, PublishOutput, ProcessContext> PUBLISH_PROCESS =
        ProcessDefinition.define(PUBLISH, 1, ImportId.class, PublishOutput.class, ProcessContext.class, pb ->
            loadConfiguration(pb
                .description("Applies a proposed configuration package; someone other than its proposer.")
                .permissions(FinancePermissions.CONFIG_PROMOTE)
                .actsOn(ConfigEntities.IMPORT, "importId",
                    a -> a.whenField("status", ConfigEntities.PROPOSED))
                .contextFactory(ConfigProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, PublishOutput.class))
                .step("Load the proposal", QueryEntities.of(ConfigEntities.IMPORT_DATASET,
                    ctx -> byId(ctx.get(INPUT, ImportId.class).importId()), IMPORTS)))
                .compute("Find the differences again", (metadata, ctx) -> checkPublish(ctx))
                // Parents before sub-accounts, accounts before what names them, taxable codes before the codes
                // charged with them: each step makes what the next ones read.
                .step("Open the new accounts", CallProcess.forEach(AccountProcesses.CREATE, 1,
                    ctx -> plan(ctx).accountsCreated(), null))
                .step("Change the accounts", CallProcess.forEach(AccountProcesses.UPDATE, 1,
                    ctx -> plan(ctx).accountsChanged(), null))
                .step("Deactivate accounts", CallProcess.forEach(AccountProcesses.DEACTIVATE, 1,
                    ctx -> plan(ctx).accountsDeactivated(), null))
                .step("Reactivate accounts", CallProcess.forEach(AccountProcesses.REACTIVATE, 1,
                    ctx -> plan(ctx).accountsReactivated(), null))
                .step("Save the jurisdictions", CallProcess.forEach(TaxProcesses.JURISDICTION_SAVE, 1,
                    ctx -> plan(ctx).jurisdictions(), null))
                .step("Set the rates", CallProcess.forEach(TaxProcesses.RATE_SET, 1, ctx -> plan(ctx).rates(), null))
                .step("Save the tax codes", CallProcess.forEach(TaxProcesses.CODE_SAVE, 1,
                    ctx -> plan(ctx).taxCodes(), null))
                .step("Publish the layouts", CallProcess.forEach(StatementProcesses.PUBLISH, 1,
                    ctx -> plan(ctx).layouts(), null))
                .step("Set the report settings", CallProcess.when(ctx -> ctx.contains(DIFF)
                    && plan(ctx).reportSettings() != null, CashFlowProcesses.SETTINGS_SET, 1,
                    ctx -> plan(ctx).reportSettings(), null))
                .compute("Record the publication", (metadata, ctx) -> recordPublish(ctx)));

    public static final ProcessDefinition<ImportId, WithdrawOutput, ProcessContext> WITHDRAW_PROCESS =
        ProcessDefinition.define(WITHDRAW, 1, ImportId.class, WithdrawOutput.class, ProcessContext.class, pb -> pb
            .description("Withdraws a configuration package proposed and not published.")
            .permissions(FinancePermissions.CONFIG_PROMOTE)
            .actsOn(ConfigEntities.IMPORT, "importId", a -> a.whenField("status", ConfigEntities.PROPOSED))
            .contextFactory(ConfigProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, WithdrawOutput.class))
            .step("Load the proposal", QueryEntities.of(ConfigEntities.IMPORT_DATASET,
                ctx -> byId(ctx.get(INPUT, ImportId.class).importId()), IMPORTS))
            .compute("Withdraw it", (metadata, ctx) -> withdraw(ctx)));

    /** The steps reading this environment's configuration. */
    private static <I, O> ProcessDefinitionBuilder<I, O, ProcessContext> loadConfiguration(
        ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        return pb
            .step("Load the finance accounts", QueryEntities.of(ConfigEntities.READ_ACCOUNTS, ctx -> all(), FIN_ACCOUNTS))
            .step("Load the ledger accounts", QueryEntities.of(ConfigEntities.READ_LEDGER_ACCOUNTS, ctx -> all(),
                LEDGER_ACCOUNTS))
            .step("Load the jurisdictions", QueryEntities.of(ConfigEntities.READ_JURISDICTIONS, ctx -> all(),
                JURISDICTIONS))
            .step("Load the rates", QueryEntities.of(ConfigEntities.READ_RATES, ctx -> all(), RATES))
            .step("Load the tax codes", QueryEntities.of(ConfigEntities.READ_CODES, ctx -> all(), CODES))
            .step("Load the layouts", QueryEntities.of(ConfigEntities.READ_LAYOUTS, ctx -> all(), LAYOUTS))
            .step("Load the layout rows", QueryEntities.of(ConfigEntities.READ_ROWS, ctx -> all(), ROWS))
            .step("Load the report settings", QueryEntities.of(ConfigEntities.READ_SETTINGS, ctx -> all(),
                SETTINGS));
    }

    // ---- export ----------------------------------------------------------------------------------------------------

    static void writePackage(ProcessContext ctx) {
        ConfigPackage current = current(ctx, ctx.get(INPUT, ExportInput.class).source().trim(), ctx.opTime());
        if (current == null) {
            return;
        }
        byte[] content = current.text().getBytes(StandardCharsets.UTF_8);
        String fileName = "finance-config-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(ctx.opTime().atOffset(ZoneOffset.UTC)) + ".json";
        ctx.put(PACKAGE, current);
        ctx.put(ARCHIVE_INPUT, new GeneratedFileProcesses.ArchiveInput(fileName, "application/json", content,
            List.of(FinancePermissions.CONFIG_EXPORT), null, null));
    }

    static void answerExport(ProcessContext ctx) {
        if (!ctx.contains(ARCHIVED)) {
            return;
        }
        ConfigPackage exported = ctx.get(PACKAGE, ConfigPackage.class);
        GeneratedFileProcesses.ArchiveOutput archived = ctx.get(ARCHIVED, GeneratedFileProcesses.ArchiveOutput.class);
        ctx.put(OUTPUT, new ExportOutput(archived.fileId(),
            ctx.get(ARCHIVE_INPUT, GeneratedFileProcesses.ArchiveInput.class).fileName(), archived.sha256(),
            archived.size(), exported.accounts().size(), exported.jurisdictions().size(), exported.rates().size(),
            exported.taxCodes().size(), exported.layouts().size()));
    }

    // ---- propose ---------------------------------------------------------------------------------------------------

    static void propose(ProcessContext ctx) {
        ProposeInput input = ctx.get(INPUT, ProposeInput.class);
        String hash = ConfigPackage.sha256(input.packageText());
        if (!hash.equalsIgnoreCase(input.sha256())) {
            ctx.reject(new Violation("sha256", HASH_MISMATCH, "The package's SHA-256 is " + hash
                + ", not the one handed over with it", Map.of("sha256", hash)));
            return;
        }
        ConfigPackage wanted = read(ctx, input.packageText());
        ConfigPackage current = current(ctx, "here", null);
        if (wanted == null || current == null) {
            return;
        }
        ConfigDiff diff = diff(ctx, wanted, current);
        if (diff == null) {
            return;
        }
        if (diff.changes() == 0) {
            ctx.reject(new Violation("packageText", NO_CHANGES, "The package changes nothing here", Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("source", wanted.source() == null || wanted.source().isBlank() ? "(unnamed)"
            : truncate(wanted.source().trim(), 100));
        values.put("exportedAt", wanted.exportedAt());
        values.put("packageHash", hash);
        values.put("packageText", ConfigPackage.normalized(input.packageText()));
        values.put("differences", JSON.writeValueAsString(diff.differences()));
        values.put("changes", BigDecimal.valueOf(diff.changes()));
        values.put("status", ConfigEntities.PROPOSED);
        values.put("proposedBy", ctx.request().actorId());
        values.put("proposedAt", ctx.opTime());
        Object id = ctx.changes().insert(ConfigEntities.IMPORT, values);
        ctx.put(OUTPUT, new ProposeOutput(String.valueOf(id), (String) values.get("source"), hash, diff.changes(),
            diff.differences()));
    }

    // ---- publish ---------------------------------------------------------------------------------------------------

    static void checkPublish(ProcessContext ctx) {
        EntityInstance proposal = proposal(ctx);
        if (proposal == null) {
            return;
        }
        if (Objects.equals(ctx.request().actorId(), proposal.get("proposedBy"))) {
            ctx.reject(new Violation("importId", SAME_PERSON, "The package was proposed by "
                + proposal.get("proposedBy") + "; another person publishes it", Map.of()));
            return;
        }
        ConfigPackage wanted = read(ctx, proposal.get("packageText"));
        ConfigPackage current = current(ctx, "here", null);
        if (wanted == null || current == null) {
            return;
        }
        ConfigDiff diff = diff(ctx, wanted, current);
        if (diff == null) {
            return;
        }
        if (diff.plan().isEmpty()) {
            ctx.reject(new Violation("importId", NO_CHANGES, "The package changes nothing here any more; withdraw it",
                Map.of()));
            return;
        }
        ctx.put(DIFF, diff);
    }

    static void recordPublish(ProcessContext ctx) {
        if (!ctx.contains(DIFF)) {
            return;
        }
        EntityInstance proposal = proposal(ctx);
        ConfigDiff diff = ctx.get(DIFF, ConfigDiff.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", ConfigEntities.PUBLISHED);
        values.put("publishedBy", ctx.request().actorId());
        values.put("publishedAt", ctx.opTime());
        values.put("differences", JSON.writeValueAsString(diff.differences()));
        values.put("changes", BigDecimal.valueOf(diff.changes()));
        ctx.changes().update(ConfigEntities.IMPORT, proposal.id(), proposal.version(), values);
        ctx.put(OUTPUT, new PublishOutput(String.valueOf(proposal.id()), ConfigEntities.PUBLISHED, diff.changes(),
            diff.differences(), ctx.request().actorId(), ctx.opTime()));
    }

    static void withdraw(ProcessContext ctx) {
        EntityInstance proposal = proposal(ctx);
        if (proposal == null) {
            return;
        }
        ctx.changes().update(ConfigEntities.IMPORT, proposal.id(), proposal.version(), Map.of(
            "status", ConfigEntities.WITHDRAWN, "withdrawnBy", ctx.request().actorId(), "withdrawnAt", ctx.opTime()));
        ctx.put(OUTPUT, new WithdrawOutput(String.valueOf(proposal.id()), ConfigEntities.WITHDRAWN));
    }

    /** The proposal asked for, while proposed; refused otherwise. */
    @SuppressWarnings("unchecked")
    private static EntityInstance proposal(ProcessContext ctx) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(IMPORTS);
        if (found == null || found.isEmpty()) {
            ctx.reject(new Violation("importId", NOT_FOUND, "There is no configuration package "
                + ctx.get(INPUT, ImportId.class).importId(), Map.of()));
            return null;
        }
        EntityInstance proposal = found.getFirst();
        if (!ConfigEntities.PROPOSED.equals(proposal.get("status"))) {
            ctx.reject(new Violation("importId", NOT_PROPOSED, "The configuration package is "
                + proposal.get("status"), Map.of("status", (Object) proposal.get("status"))));
            return null;
        }
        return proposal;
    }

    static ConfigDiff.Plan plan(ProcessContext ctx) {
        return ctx.contains(DIFF) ? ctx.get(DIFF, ConfigDiff.class).plan() : EMPTY;
    }

    private static final ConfigDiff.Plan EMPTY = new ConfigDiff.Plan(List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), null);

    // ---- this environment's configuration --------------------------------------------------------------------------

    /** The configuration read by the loading steps, as a package; null (refused) when a list reached the cap. */
    @SuppressWarnings("unchecked")
    static ConfigPackage current(ProcessContext ctx, String source, Instant exportedAt) {
        for (String key : List.of(FIN_ACCOUNTS, LEDGER_ACCOUNTS, JURISDICTIONS, RATES, CODES, LAYOUTS, ROWS)) {
            if (((List<EntityInstance>) ctx.get(key)).size() >= CAP) {
                ctx.reject(new Violation("configuration", INVALID, "More than " + (CAP - 1) + " " + key
                    + " to read", Map.of()));
                return null;
            }
        }
        Map<Object, EntityInstance> ledger = new HashMap<>();
        for (EntityInstance a : (List<EntityInstance>) ctx.get(LEDGER_ACCOUNTS)) {
            ledger.put(String.valueOf(a.id()), a);
        }
        List<ConfigPackage.Account> accounts = new ArrayList<>();
        for (EntityInstance f : (List<EntityInstance>) ctx.get(FIN_ACCOUNTS)) {
            EntityInstance l = ledger.get(String.valueOf((Object) f.get("ledgerAccountId")));
            if (l == null) {
                continue;
            }
            EntityInstance parent = l.get("parentId") == null ? null
                : ledger.get(String.valueOf((Object) l.get("parentId")));
            accounts.add(new ConfigPackage.Account(f.get("accountCode"), l.get("accountName"), f.get("financialType"),
                f.get("normalBalance"), f.get("statementLine"), f.get("cashFlowClass"), f.get("controlClass"),
                bool(f.get("clearing")), f.get("requiredDimension"), parent == null ? null : parent.get("accountCode"),
                bool(l.get("summary")), !Boolean.FALSE.equals(l.get("enabled"))));
        }
        List<ConfigPackage.Jurisdiction> jurisdictions = ((List<EntityInstance>) ctx.get(JURISDICTIONS)).stream()
            .map(j -> new ConfigPackage.Jurisdiction(j.get("jurisdictionCode"), j.get("jurisdictionName"),
                j.get("level"), j.get("state"), !Boolean.FALSE.equals(j.get("active")))).toList();
        List<ConfigPackage.Rate> rates = ((List<EntityInstance>) ctx.get(RATES)).stream()
            .map(r -> new ConfigPackage.Rate(r.get("jurisdictionCode"), r.<LocalDate>get("effectiveFrom"),
                r.<BigDecimal>get("ratePercent"))).toList();
        List<ConfigPackage.TaxCode> codes = ((List<EntityInstance>) ctx.get(CODES)).stream()
            .map(c -> new ConfigPackage.TaxCode(c.get("taxCode"), c.get("description"), c.get("kind"), c.get("reason"),
                c.get("state"), c.get("jurisdictions") == null ? List.of()
                    : Arrays.asList(((String) c.get("jurisdictions")).split(",")),
                bool(c.get("certificateRequired")), c.get("chargeCode"), !Boolean.FALSE.equals(c.get("active"))))
            .toList();
        // Each layout's latest version, its rows in order.
        Map<String, EntityInstance> latest = new LinkedHashMap<>();
        for (EntityInstance l : (List<EntityInstance>) ctx.get(LAYOUTS)) {
            EntityInstance known = latest.get((String) l.get("layoutCode"));
            if (known == null || number(l.get("version")) > number(known.get("version"))) {
                latest.put(l.get("layoutCode"), l);
            }
        }
        Map<String, List<EntityInstance>> rowsByLayout = ((List<EntityInstance>) ctx.get(ROWS)).stream()
            .collect(Collectors.groupingBy(r -> String.valueOf((Object) r.get("layoutId"))));
        List<ConfigPackage.Layout> layouts = latest.values().stream().map(l -> new ConfigPackage.Layout(
            l.get("layoutCode"), l.get("statement"), l.get("title"),
            rowsByLayout.getOrDefault(String.valueOf(l.id()), List.of()).stream()
                .sorted(Comparator.comparingInt(r -> number(r.get("seq"))))
                .map(r -> new ConfigPackage.Row(r.get("lineCode"), r.get("label"), r.get("kind"), r.get("accounts"),
                    number(r.get("sign")), bool(r.get("detail")), bool(r.get("omitZero")), r.get("noteAccounts")))
                .toList())).toList();
        List<EntityInstance> settings = (List<EntityInstance>) ctx.get(SETTINGS);
        EntityInstance s = settings.stream().filter(e -> StatementEntities.SETTINGS_KEY.equals(e.get("settingsKey")))
            .findFirst().orElse(null);
        ConfigPackage.ReportSettings reportSettings = s == null
            ? new ConfigPackage.ReportSettings(null, null, null, null, null, null, null)
            : new ConfigPackage.ReportSettings(s.get("interestAccounts"), s.get("interestPayableAccounts"),
                s.get("incomeTaxAccounts"), s.get("incomeTaxPayableAccounts"), s.get("receivablesAccounts"),
                s.get("accruedAccounts"), s.get("debtAccounts"));
        return new ConfigPackage(ConfigPackage.FORMAT, source, exportedAt, accounts, jurisdictions, rates, codes,
            layouts, reportSettings);
    }

    private static ConfigPackage read(ProcessContext ctx, String text) {
        try {
            return ConfigPackage.read(text);
        } catch (IllegalArgumentException e) {
            ctx.reject(new Violation("packageText", INVALID, truncate(e.getMessage(), 500), Map.of()));
            return null;
        }
    }

    private static ConfigDiff diff(ProcessContext ctx, ConfigPackage wanted, ConfigPackage current) {
        ConfigDiff diff = ConfigDiff.of(wanted, current);
        List<String> problems = new ArrayList<>(diff.problems());
        problems.addAll(invalidInputs(diff.plan()));
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                ctx.reject(new Violation("packageText", INVALID, truncate(problem, 500), Map.of()));
            }
            return null;
        }
        return diff;
    }

    /**
     * What the processes' inputs would refuse: a process called from another does not check its input, so the
     * package's are checked here, as a form or an import would have them checked (FIN_SETUP-made inputs are the
     * processes' own).
     */
    static List<String> invalidInputs(ConfigDiff.Plan plan) {
        List<String> problems = new ArrayList<>();
        check(problems, "account", plan.accountsCreated(), AccountProcesses.AccountInput::accountCode);
        check(problems, "account", plan.accountsChanged(), AccountProcesses.AccountChange::accountCode);
        check(problems, "jurisdiction", plan.jurisdictions(), TaxProcesses.JurisdictionInput::jurisdictionCode);
        check(problems, "rate", plan.rates(), r -> r.jurisdictionCode() + "@" + r.effectiveFrom());
        check(problems, "tax code", plan.taxCodes(), TaxProcesses.TaxCodeInput::taxCode);
        check(problems, "layout", plan.layouts(), StatementProcesses.PublishInput::layoutCode);
        if (plan.reportSettings() != null) {
            check(problems, "report settings", List.of(plan.reportSettings()), x -> "REPORTS");
        }
        return problems;
    }

    private static <T> void check(List<String> problems, String what, List<T> inputs,
        java.util.function.Function<T, String> key) {
        for (T input : inputs) {
            VALIDATOR.validate(input).stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .forEach(v -> problems.add(what + " " + key.apply(input) + ": " + v.getPropertyPath() + " "
                    + v.getMessage()));
        }
    }

    private static final jakarta.validation.Validator VALIDATOR;

    static {
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation
            .buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    /** The differences kept with a proposal (for its pages and tests). */
    public static List<ConfigDiff.Difference> differences(String json) {
        return JSON.readValue(json, new TypeReference<List<ConfigDiff.Difference>>() {});
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static EntityQuery all() {
        return EntityQuery.builder().limit(CAP).build();
    }

    private static EntityQuery byId(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            // Not an id: the nil one finds nothing, and the proposal is refused as unknown.
            uuid = new UUID(0, 0);
        }
        return EntityQuery.builder().where(new QueryPredicate.Eq("importId", uuid)).limit(1).build();
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private ConfigProcesses() {}
}

package com.jabiz.quizbuks.setup;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.country.CountryData;
import com.jabiz.quizbuks.country.QbCountries;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.param.ParamProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.MfaRequirement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code QB_SETUP} prepares a QuizBuks installation (docs/quizbuks/plans/Q1-skeleton.md): the roles of
 * {@link QbRoles} with their permissions, the ledger accounts of {@link QbLedger}, the countries of
 * {@link CountryData} and the business parameters of {@link QbParams}. It can run again at any time: it adds what is
 * missing (a later phase's permissions, an account or a parameter added since) and never changes or removes what
 * exists, so what administrators granted or set stays. Granting permissions is platform administration, so it asks
 * for a second factor like the platform's own security processes.
 */
public final class QbSetupProcesses {

    public static final String SETUP = "QB_SETUP";

    public record SetupInput() {}

    /**
     * @param rolesCreated     roles that did not exist
     * @param permissionsAdded permissions granted to existing or new roles
     * @param accountsOpened   codes of the ledger accounts opened
     * @param countriesAdded   countries that did not exist
     * @param paramsCreated    keys of the business parameters declared
     */
    public record SetupOutput(List<String> rolesCreated, int permissionsAdded, List<String> accountsOpened,
        int countriesAdded, List<String> paramsCreated) {}

    static final String ROLES = "roles";
    static final String GRANTS = "grants";
    static final String ACCOUNTS = "accounts";
    static final String PARAMS = "params";
    static final String COUNTRIES = "countries";
    static final String OPEN = "open";
    static final String CREATE = "create";
    static final String OUTPUT = "output";

    public static final ProcessDefinition<SetupInput, SetupOutput, ProcessContext> PROCESS =
        ProcessDefinition.define(SETUP, 1, SetupInput.class, SetupOutput.class, ProcessContext.class, pb -> pb
            .description("Creates the QuizBuks roles, ledger accounts, countries and business parameters; adds what "
                + "is missing when run again.")
            .permissions(QbPermissions.SETUP)
            .requiresMfa(MfaRequirement.ADMINISTRATION)
            .contextFactory((start, input) -> new ProcessContext(start))
            .outputMapper(ctx -> ctx.get(OUTPUT, SetupOutput.class))
            .step("Load the roles", QueryEntities.of(SecurityEntities.ROLE_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("roleCode", new ArrayList<>(QbRoles.all().stream()
                    .map(QbRoles.Role::code).toList())))
                .limit(QbRoles.all().size() + 1)
                .build(), ROLES))
            .step("Load their permissions", QueryEntities.of(SecurityEntities.ROLE_PERMISSION_DATASET,
                ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("roleId", new ArrayList<>(list(ctx, ROLES).stream()
                        .map(EntityInstance::id).toList())))
                    .limit(1000)
                    .build(), GRANTS))
            .step("Load the ledger accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("accountCode", new ArrayList<>(QbLedger.ACCOUNTS.stream()
                        .map(QbLedger.Account::code).toList())))
                    .limit(QbLedger.ACCOUNTS.size() + 1)
                    .build(), ACCOUNTS))
            .step("Load the parameters", QueryEntities.of(ParamEntities.DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In(ParamEntities.KEY, new ArrayList<>(QbParams.ALL.stream()
                    .map(QbParams.Param::key).toList())))
                .limit(QbParams.ALL.size() + 1)
                .build(), PARAMS))
            .step("Load the countries", QueryEntities.of(QbCountries.DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("code", new ArrayList<>(CountryData.all().stream()
                    .map(CountryData.Country::code).toList())))
                .limit(CountryData.all().size() + 1)
                .build(), COUNTRIES))
            .compute("Add what is missing", (metadata, ctx) -> setup(ctx))
            .step("Open the ledger accounts", CallProcess.forEach(LedgerProcesses.OPEN_ACCOUNT, 1,
                ctx -> listOf(ctx, OPEN), null))
            .step("Declare the parameters", CallProcess.forEach("PARAM_CREATE", 1,
                ctx -> listOf(ctx, CREATE), null)));

    static void setup(ProcessContext ctx) {
        Map<String, Object> roleIds = new HashMap<>();
        for (EntityInstance role : list(ctx, ROLES)) {
            roleIds.put(role.get("roleCode"), role.id());
        }
        Set<String> granted = new HashSet<>();
        for (EntityInstance grant : list(ctx, GRANTS)) {
            granted.add(grant.get("roleId") + " " + grant.get("permission"));
        }
        List<String> created = new ArrayList<>();
        int added = 0;
        for (QbRoles.Role role : QbRoles.all()) {
            Object roleId = roleIds.get(role.code());
            if (roleId == null) {
                roleId = ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", role.code(),
                    "labels", role.labels(), "enabled", true, "requireMfa", role.requireMfa()));
                created.add(role.code());
            }
            for (String permission : role.permissions()) {
                if (granted.add(roleId + " " + permission)) {
                    ctx.changes().insert(SecurityEntities.ROLE_PERMISSION,
                        Map.of("roleId", roleId, "permission", permission));
                    added++;
                }
            }
        }

        Set<String> accounts = new HashSet<>();
        list(ctx, ACCOUNTS).forEach(account -> accounts.add(account.get("accountCode")));
        List<LedgerProcesses.OpenAccountInput> open = QbLedger.ACCOUNTS.stream()
            .filter(account -> !accounts.contains(account.code()))
            .map(account -> new LedgerProcesses.OpenAccountInput(account.code(), account.name(), account.type()))
            .toList();

        Set<String> params = new HashSet<>();
        list(ctx, PARAMS).forEach(param -> params.add(param.get(ParamEntities.KEY)));
        List<ParamProcesses.CreateInput> create = QbParams.ALL.stream()
            .filter(param -> !params.contains(param.key()))
            .map(param -> new ParamProcesses.CreateInput(param.key(), param.valueKind(), param.value(),
                param.description()))
            .toList();

        Set<String> countries = new HashSet<>();
        list(ctx, COUNTRIES).forEach(country -> countries.add(country.get("code")));
        int countriesAdded = 0;
        for (CountryData.Country country : CountryData.all()) {
            if (!countries.contains(country.code())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", country.code());
                row.put("name", country.names());
                row.put("regions", country.regions());
                ctx.changes().insert(QbCountries.COUNTRY, row);
                countriesAdded++;
            }
        }

        ctx.put(OPEN, open);
        ctx.put(CREATE, create);
        ctx.put(OUTPUT, new SetupOutput(List.copyOf(created), added,
            open.stream().map(LedgerProcesses.OpenAccountInput::accountCode).toList(), countriesAdded,
            create.stream().map(ParamProcesses.CreateInput::key).toList()));
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private static List<?> listOf(ProcessContext ctx, String key) {
        return ctx.contains(key) ? (List<?>) ctx.get(key) : List.of();
    }

    private QbSetupProcesses() {}
}

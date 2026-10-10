package com.jabiz.quizbuks.setup;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.country.CountryCatalog;
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
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.MfaRequirement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code QB_SETUP} prepares a QuizBuks installation (docs/quizbuks/plans/Q1-skeleton.md): the roles of
 * {@link QbRoles} with their permissions, the ledger accounts of {@link QbLedger}, the countries of
 * {@link CountryCatalog} and the business parameters of {@link QbParams}.
 *
 * <p>It adds only what has never existed. Every item it makes or finds is written to {@link QbSetupRecords}, and an
 * item with a record is never added again: a role an administrator deleted or a permission revoked stays gone, and
 * a parameter keeps the value an administrator set. An item without a record is added unless it exists now or is
 * scheduled to begin (roles and grants are read as they will be, too); then it is only recorded. So it can run again
 * at any time, to add what a later phase declares. Granting permissions is platform administration, so it asks for a
 * second factor like the platform's own security processes. Every read takes the whole result (decision D32).
 */
public final class QbSetupProcesses {

    public static final String SETUP = "QB_SETUP";

    /** Far enough ahead to see every scheduled role and grant (they are scheduled in business time, not centuries). */
    static final Instant AHEAD = Instant.parse("3000-01-01T00:00:00Z");

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

    static final String RECORDS = "records";
    static final String ROLES = "roles";
    static final String GRANTS = "grants";
    static final String ROLES_AHEAD = "rolesAhead";
    static final String GRANTS_AHEAD = "grantsAhead";
    static final String ACCOUNTS = "accounts";
    static final String PARAMS = "params";
    static final String COUNTRIES = "countries";
    static final String OPEN = "open";
    static final String CREATE = "create";
    static final String OUTPUT = "output";

    /** The whole result of a query: the platform refuses more than its maximum rather than cutting it short. */
    private static EntityQuery all(QueryPredicate predicate) {
        return EntityQuery.builder().where(predicate).limit(Integer.MAX_VALUE).build();
    }

    private static EntityQuery all() {
        return EntityQuery.builder().limit(Integer.MAX_VALUE).build();
    }

    private static List<Object> codes(List<?> items, Function<Object, String> code) {
        return new ArrayList<>(items.stream().map(code).toList());
    }

    public static ProcessDefinition<SetupInput, SetupOutput, ProcessContext> process(CountryCatalog catalog) {
        return ProcessDefinition.define(SETUP, 1, SetupInput.class, SetupOutput.class, ProcessContext.class, pb -> pb
            .description("Creates the QuizBuks roles, ledger accounts, countries and business parameters that have "
                + "never existed; never adds back what was removed.")
            .permissions(QbPermissions.SETUP)
            .requiresMfa(MfaRequirement.ADMINISTRATION)
            .contextFactory((start, input) -> new ProcessContext(start))
            .outputMapper(ctx -> ctx.get(OUTPUT, SetupOutput.class))
            .step("Load what setup has seen", QueryEntities.of(QbSetupRecords.DATASET, ctx -> all(), RECORDS))
            .step("Load the roles", QueryEntities.of(SecurityEntities.ROLE_DATASET, ctx -> all(
                new QueryPredicate.In("roleCode", codes(QbRoles.all(), r -> ((QbRoles.Role) r).code()))), ROLES))
            .step("Load their permissions", QueryEntities.of(SecurityEntities.ROLE_PERMISSION_DATASET,
                ctx -> all(new QueryPredicate.In("roleId", new ArrayList<>(list(ctx, ROLES).stream()
                    .map(EntityInstance::id).toList()))), GRANTS))
            .step("Load the roles scheduled to begin", RunTemplate.at("qb.setup.roles", ctx -> Map.of(),
                ctx -> AHEAD, ctx -> null, ROLES_AHEAD))
            .step("Load the grants scheduled to begin", RunTemplate.at("qb.setup.grants", ctx -> Map.of(),
                ctx -> AHEAD, ctx -> null, GRANTS_AHEAD))
            .step("Load the ledger accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET, ctx -> all(
                new QueryPredicate.In("accountCode", codes(QbLedger.ACCOUNTS, a -> ((QbLedger.Account) a).code()))),
                ACCOUNTS))
            .step("Load the parameters", QueryEntities.of(ParamEntities.DATASET, ctx -> all(
                new QueryPredicate.In(ParamEntities.KEY, codes(QbParams.ALL, p -> ((QbParams.Param) p).key()))),
                PARAMS))
            .step("Load the countries", QueryEntities.of(QbCountries.DATASET, ctx -> all(
                new QueryPredicate.In("code", codes(catalog.countries(), c -> ((CountryData.Country) c).code()))),
                COUNTRIES))
            .compute("Add what has never existed", (metadata, ctx) -> setup(ctx, catalog.countries()))
            .step("Open the ledger accounts", CallProcess.forEach(LedgerProcesses.OPEN_ACCOUNT, 1,
                ctx -> listOf(ctx, OPEN), null))
            .step("Declare the parameters", CallProcess.forEach("PARAM_CREATE", 1,
                ctx -> listOf(ctx, CREATE), null)));
    }

    static void setup(ProcessContext ctx, List<CountryData.Country> catalog) {
        Set<String> seen = new HashSet<>();
        list(ctx, RECORDS).forEach(record -> seen.add(record.get("itemKey")));
        Set<String> found = new HashSet<>();
        Map<String, Object> roleIds = new HashMap<>();
        for (EntityInstance role : list(ctx, ROLES)) {
            roleIds.put(role.get("roleCode"), role.id());
            found.add(QbSetupRecords.role(role.get("roleCode")));
        }
        Map<Object, String> roleCodes = new HashMap<>();
        roleIds.forEach((code, id) -> roleCodes.put(String.valueOf(id), code));
        for (EntityInstance grant : list(ctx, GRANTS)) {
            String code = roleCodes.get(String.valueOf((Object) grant.get("roleId")));
            found.add(QbSetupRecords.grant(code, grant.get("permission")));
        }
        rows(ctx, ROLES_AHEAD).forEach(row -> found.add(QbSetupRecords.role(String.valueOf(row.get("roleCode")))));
        rows(ctx, GRANTS_AHEAD).forEach(row -> found.add(QbSetupRecords.grant(String.valueOf(row.get("roleCode")),
            String.valueOf(row.get("permission")))));
        list(ctx, ACCOUNTS).forEach(account -> found.add(QbSetupRecords.account(account.get("accountCode"))));
        list(ctx, PARAMS).forEach(param -> found.add(QbSetupRecords.param(param.get(ParamEntities.KEY))));
        list(ctx, COUNTRIES).forEach(country -> found.add(QbSetupRecords.country(country.get("code"))));

        Recorder recorder = new Recorder(ctx, seen, found);
        List<String> created = new ArrayList<>();
        int added = 0;
        for (QbRoles.Role role : QbRoles.all()) {
            if (recorder.isNew(QbSetupRecords.role(role.code()))) {
                roleIds.put(role.code(), ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", role.code(),
                    "labels", role.labels(), "enabled", true, "requireMfa", role.requireMfa())));
                created.add(role.code());
            }
            Object roleId = roleIds.get(role.code());
            for (String permission : role.permissions()) {
                String key = QbSetupRecords.grant(role.code(), permission);
                // A role that is gone (or only scheduled) gets nothing: its grants wait, unrecorded, for the role.
                if ((roleId != null || recorder.known(key)) && recorder.isNew(key)) {
                    ctx.changes().insert(SecurityEntities.ROLE_PERMISSION,
                        Map.of("roleId", roleId, "permission", permission));
                    added++;
                }
            }
        }

        List<LedgerProcesses.OpenAccountInput> open = new ArrayList<>();
        for (QbLedger.Account account : QbLedger.ACCOUNTS) {
            if (recorder.isNew(QbSetupRecords.account(account.code()))) {
                open.add(new LedgerProcesses.OpenAccountInput(account.code(), account.name(), account.type()));
            }
        }

        List<ParamProcesses.CreateInput> create = new ArrayList<>();
        for (QbParams.Param param : QbParams.ALL) {
            if (recorder.isNew(QbSetupRecords.param(param.key()))) {
                create.add(new ParamProcesses.CreateInput(param.key(), param.valueKind(), param.value(),
                    param.description()));
            }
        }

        int countriesAdded = 0;
        for (CountryData.Country country : catalog) {
            if (recorder.isNew(QbSetupRecords.country(country.code()))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", country.code());
                row.put("name", country.names());
                row.put("regions", country.regions());
                ctx.changes().insert(QbCountries.COUNTRY, row);
                countriesAdded++;
            }
        }

        ctx.put(OPEN, List.copyOf(open));
        ctx.put(CREATE, List.copyOf(create));
        ctx.put(OUTPUT, new SetupOutput(List.copyOf(created), added,
            open.stream().map(LedgerProcesses.OpenAccountInput::accountCode).toList(), countriesAdded,
            create.stream().map(ParamProcesses.CreateInput::key).toList()));
    }

    /**
     * Decides each item once: an item with a record is old; one that exists (or is scheduled) without a record gets
     * its record now and is old too; anything else is new and recorded, for the caller to make.
     */
    private record Recorder(ProcessContext ctx, Set<String> seen, Set<String> found) {

        /** Whether setup has a record of the item or finds it in the data. */
        boolean known(String key) {
            return seen.contains(key) || found.contains(key);
        }

        boolean isNew(String key) {
            if (seen.contains(key)) {
                return false;
            }
            seen.add(key);
            ctx.changes().insert(QbSetupRecords.RECORD, Map.of("itemKey", key));
            return !found.contains(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> found = (List<Map<String, Object>>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private static List<?> listOf(ProcessContext ctx, String key) {
        return ctx.contains(key) ? (List<?>) ctx.get(key) : List.of();
    }

    private QbSetupProcesses() {}
}

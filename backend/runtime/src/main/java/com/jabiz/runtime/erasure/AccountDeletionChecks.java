package com.jabiz.runtime.erasure;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.security.SignInEntries;
import com.jabiz.runtime.storage.MaintenanceStorage;
import com.jabiz.security.erasure.ErasureLoad;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Startup checks of account deletion (docs/design/10-security.md section 17.5), category {@value ErasureChecks#CATEGORY}:
 * <ul>
 *   <li>an entry lets its users ask for the deletion of their account, but no maintenance connection
 *       ({@code jabiz.erasure.datasource.url}) is configured: the accounts would be erased and their personal data never
 *       purged. An error, a warning under the dev profile;</li>
 *   <li>the maintenance connection's URL is not an R2DBC one (a JDBC URL, typically): every purge would fail. An
 *       error, whatever the entries;</li>
 *   <li>an eraser's {@link PersonalDataEraser#loads()} read a dataset that does not exist or a field its entity does not
 *       have, or use a load name twice with different meanings: the notices could never be read, and every request
 *       would fail;</li>
 *   <li>(phase 17zz22) a load's condition or total is on a sensitive or masked field, a condition gives a value its
 *       field cannot hold, or a total is of a field that is not a number or an amount.</li>
 * </ul>
 */
@Component
public class AccountDeletionChecks implements PlatformCheck {

    private final SignInEntries entries;
    private final PersonalDataErasers erasers;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry definitions;
    private final Environment environment;

    public AccountDeletionChecks(SignInEntries entries, PersonalDataErasers erasers, DatasetRegistry datasets,
        EntityDefinitionRegistry definitions, Environment environment) {
        this.entries = entries;
        this.erasers = erasers;
        this.datasets = datasets;
        this.definitions = definitions;
        this.environment = environment;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        String url = environment.getProperty("jabiz.erasure.datasource.url", "");
        boolean development = environment.acceptsProfiles(Profiles.of("dev"));
        for (SignInEntries.Entry entry : entries.all()) {
            if (entry.accountDeletion() && url.isBlank()) {
                String message = "account-deletion is on, but no maintenance connection (jabiz.erasure.datasource.url,"
                    + " JABIZ_ERASURE_DATASOURCE_URL) is configured: personal data would never be purged";
                String location = "jabiz.security.entries." + entry.name();
                problems.add(development ? CheckProblem.warning(ErasureChecks.CATEGORY, location, message)
                    : CheckProblem.error(ErasureChecks.CATEGORY, location, message + " (set it, or run with the dev"
                        + " profile)"));
            }
        }
        String urlProblem = url.isBlank() ? null : MaintenanceStorage.urlProblem(url);
        if (urlProblem != null) {
            // Whether or not an entry deletes accounts: a purge on this connection would fail every time.
            problems.add(CheckProblem.error(ErasureChecks.CATEGORY, "jabiz.erasure.datasource.url",
                "the maintenance connection (JABIZ_ERASURE_DATASOURCE_URL) " + urlProblem));
        }
        Map<String, ErasureLoad> seen = new LinkedHashMap<>();
        for (PersonalDataEraser eraser : erasers.all()) {
            String location = "PersonalDataEraser " + eraser.getClass().getName();
            List<ErasureLoad> loads;
            try {
                loads = eraser.loads();
            } catch (RuntimeException e) {
                problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, "loads() failed: " + e.getMessage()));
                continue;
            }
            for (ErasureLoad load : loads) {
                ErasureLoad earlier = seen.putIfAbsent(load.name(), load);
                if (earlier != null && !earlier.equals(load)) {
                    problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, "load " + load.name()
                        + " is declared with another dataset, field, kind or conditions elsewhere"));
                }
                Optional<DatasetDefinition> dataset = datasets.findById(load.dataset());
                if (dataset.isEmpty()) {
                    problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, "load " + load.name()
                        + " reads unknown dataset " + load.dataset()));
                    continue;
                }
                definitions.find(dataset.get().targetEntityType())
                    .ifPresent(def -> problems.addAll(fields(location, load, def)));
            }
        }
        return List.copyOf(problems);
    }

    /**
     * The fields a load names (phase 17zz22): its user field, its conditions' fields and the field it adds up. A
     * condition or a total on a sensitive or masked field would tell its values through the notices: refused, as is a
     * condition's value the field cannot hold (a code not in its dictionary, text for a number) and a total of
     * anything but a number or an amount.
     */
    static List<CheckProblem> fields(String location, ErasureLoad load, EntityDefinition def) {
        List<CheckProblem> problems = new ArrayList<>();
        String at = "load " + load.name() + ": entity " + def.name;
        if (def.findField(load.userField()).isEmpty()) {
            problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, at + " has no field " + load.userField()));
        }
        for (ErasureLoad.Condition condition : load.conditions()) {
            Optional<FieldDefinition> field = def.findField(condition.field());
            if (field.isEmpty()) {
                problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, at + " has no field "
                    + condition.field() + " (a condition)"));
                continue;
            }
            if (hidden(field.get())) {
                problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, at + ": the condition on "
                    + condition.field() + " is on a sensitive or masked field"));
                continue;
            }
            for (Object value : condition.values()) {
                try {
                    FieldValueCoercer.coerce(field.get(), value, true);
                } catch (RuntimeException e) {
                    problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, at + ": the condition on "
                        + condition.field() + " gives a value it cannot hold: " + e.getMessage()));
                }
            }
        }
        if (load.kind() == ErasureLoad.Kind.SUM) {
            Optional<FieldDefinition> field = def.findField(load.field());
            if (field.isEmpty()) {
                problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, at + " has no field " + load.field()
                    + " (to add up)"));
            } else if (hidden(field.get())) {
                problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, at + " adds up " + load.field()
                    + ", a sensitive or masked field"));
            } else if (!(field.get().kind() instanceof SemanticKind.Numeric
                || field.get().kind() instanceof SemanticKind.Monetary)) {
                problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, at + " adds up " + load.field()
                    + ", which is not a number or an amount"));
            }
        }
        return problems;
    }

    private static boolean hidden(FieldDefinition field) {
        return field.sensitive() || field.isMasked();
    }
}

package com.jabiz.runtime.erasure;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.security.SignInEntries;
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
 *   <li>an eraser's {@link PersonalDataEraser#loads()} read a dataset that does not exist or a field its entity does not
 *       have, or use a load name twice with different meanings: the notices could never be read, and every request
 *       would fail.</li>
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
                        + " is declared with another dataset or field elsewhere"));
                }
                Optional<DatasetDefinition> dataset = datasets.findById(load.dataset());
                if (dataset.isEmpty()) {
                    problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, "load " + load.name()
                        + " reads unknown dataset " + load.dataset()));
                    continue;
                }
                definitions.find(dataset.get().targetEntityType())
                    .filter(def -> def.findField(load.userField()).isEmpty())
                    .ifPresent(def -> problems.add(CheckProblem.error(ErasureChecks.CATEGORY, location, "load "
                        + load.name() + ": entity " + def.name + " has no field " + load.userField())));
            }
        }
        return List.copyOf(problems);
    }
}

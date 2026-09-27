package com.jabiz.runtime.file;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.file.FileKind;
import com.jabiz.file.FilePolicy;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup check of files (category {@value #CATEGORY}, docs/design/14-files.md section 4), reporting every problem at
 * once: policy names declared twice, a policy whose limit exceeds the request limit, file fields naming an unknown
 * policy or of an entity no dataset serves (their references could not be found), and, when any policy exists, a
 * storage directory that is not configured, cannot be created or is not writable. Runs on the startup thread.
 */
@Component
public class FileChecks implements PlatformCheck {

    public static final String CATEGORY = "FILE";

    private final FilePolicyRegistry policies;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final FileProperties properties;

    public FileChecks(FilePolicyRegistry policies, EntityDefinitionRegistry entities, DatasetRegistry datasets,
        FileProperties properties) {
        this.policies = policies;
        this.entities = entities;
        this.datasets = datasets;
        this.properties = properties;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        for (String name : policies.duplicates()) {
            problems.add(CheckProblem.error(CATEGORY, "FilePolicy " + name, "declared more than once"));
        }
        long maxRequest = properties.maxRequestBytes().toBytes();
        for (FilePolicy policy : policies.all()) {
            if (policy.maxBytes() > maxRequest) {
                problems.add(CheckProblem.error(CATEGORY, "FilePolicy " + policy.name(), "maxBytes "
                    + policy.maxBytes() + " exceeds jabiz.files.max-request-bytes (" + maxRequest + ")"));
            }
        }
        for (EntityDefinition def : entities.all()) {
            for (FieldDefinition field : FileKind.fieldsOf(def)) {
                String location = def.name + "." + field.name();
                String policy = FileKind.policyOf(field.kind()).orElseThrow();
                if (policies.find(policy).isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "file policy " + policy
                        + " is not declared"));
                }
                if (datasets.findForEntity(def.name).isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "no dataset serves " + def.name
                        + ", so references to files cannot be found"));
                }
            }
        }
        if (!policies.all().isEmpty()) {
            checkRoot(problems);
        }
        return problems;
    }

    private void checkRoot(List<CheckProblem> problems) {
        FileProperties.Local local = properties.local();
        if (!local.configured()) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.files.local.root",
                "file policies are declared but no storage directory is configured"));
            return;
        }
        Path root = local.rootPath();
        try {
            Files.createDirectories(root);
        } catch (IOException | SecurityException e) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.files.local.root", "storage directory " + root
                + " cannot be created: " + e.getMessage()));
            return;
        }
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.files.local.root", "storage directory " + root
                + " is not a writable directory"));
        }
    }
}

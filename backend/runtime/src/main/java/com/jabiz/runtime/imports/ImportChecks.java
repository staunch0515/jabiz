package com.jabiz.runtime.imports;

import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportField;
import com.jabiz.imports.ImportFormat;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.file.FilePolicyRegistry;
import com.jabiz.runtime.process.ProcessInputSchemas;
import com.jabiz.runtime.process.ProcessRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Startup self-check of imports (docs/design/20-imports.md section 7): ids are unique; the file policy exists, takes
 * import types only, and takes the type the layout reads; the process rows go to is registered; the parameters can
 * be described as a form; the import and its fields have texts in every language.
 */
@Component
class ImportChecks implements PlatformCheck {

    private static final String CATEGORY = "IMPORT";

    private final ImportRegistry imports;
    private final FilePolicyRegistry policies;
    private final ProcessRegistry processes;
    private final MessageCatalog messages;

    ImportChecks(ImportRegistry imports, FilePolicyRegistry policies, ProcessRegistry processes,
        MessageCatalog messages) {
        this.imports = imports;
        this.policies = policies;
        this.processes = processes;
        this.messages = messages;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        imports.duplicates().forEach(id -> problems.add(CheckProblem.error(CATEGORY, id, "declared more than once")));
        List<String> texts = new ArrayList<>();
        for (ImportDefinition<?> definition : imports.all()) {
            String location = definition.id();
            Optional<FilePolicy> policy = policies.find(definition.filePolicy());
            if (policy.isEmpty()) {
                problems.add(CheckProblem.error(CATEGORY, location, "file policy " + definition.filePolicy()
                    + " does not exist"));
            } else if (!policy.get().forImports()) {
                problems.add(CheckProblem.error(CATEGORY, location, "file policy " + definition.filePolicy()
                    + " must allow import types only (TEXT, XLSX, XML)"));
            } else {
                MediaTypes needed = switch (definition.format()) {
                    case ImportFormat.Csv csv -> MediaTypes.TEXT;
                    case ImportFormat.FixedWidth fixed -> MediaTypes.TEXT;
                    case ImportFormat.Xlsx xlsx -> MediaTypes.XLSX;
                    case ImportFormat.Xml xml -> MediaTypes.XML;
                    case ImportFormat.Custom custom -> null;
                };
                if (needed != null && !policy.get().allows(needed)) {
                    problems.add(CheckProblem.error(CATEGORY, location, "file policy " + definition.filePolicy()
                        + " does not allow " + needed + ", which the layout reads"));
                }
            }
            String target = definition.target().process() + " v" + definition.target().version();
            if (processes.find(definition.target().process(), definition.target().version()).isEmpty()) {
                problems.add(CheckProblem.error(CATEGORY, location, "process " + target + " is not registered"));
            }
            if (definition.hasParams()) {
                for (String unsupported : ProcessInputSchemas.of(definition.paramsType()).problems()) {
                    problems.add(CheckProblem.warning(CATEGORY, location, "parameter form cannot describe "
                        + unsupported + " (entered as raw JSON)"));
                }
            }
            texts.add("import." + definition.id());
            for (ImportField field : definition.fields()) {
                texts.add("import." + definition.id() + "." + field.name());
            }
        }
        messages.missing(texts).forEach(missing -> problems.add(CheckProblem.error("MESSAGES", missing,
            "no text for the import or its field")));
        return problems;
    }
}

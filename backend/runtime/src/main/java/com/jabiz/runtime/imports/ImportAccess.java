package com.jabiz.runtime.imports;

import com.jabiz.context.RequestContext;
import com.jabiz.file.FilePolicy;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.file.FilePolicyRegistry;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.security.Permissions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Who may run an import (docs/design/20-imports.md section 6): the import's own permission, the permissions of the
 * process its rows go to - rows are handed to it as sub-processes, which check nothing themselves - and the read
 * permission of its file policy. Checked by the endpoints and again by {@code IMPORT_RUN} itself, which an internal
 * process can still be asked to run directly.
 */
@Component
class ImportAccess {

    private final ProcessRegistry processes;
    private final FilePolicyRegistry policies;
    private final boolean development;
    private final com.jabiz.runtime.security.MfaPolicy mfa;

    ImportAccess(ProcessRegistry processes, FilePolicyRegistry policies, Environment environment,
        com.jabiz.runtime.security.MfaPolicy mfa) {
        this.mfa = mfa;
        this.processes = processes;
        this.policies = policies;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    Set<String> required(ImportDefinition<?> definition) {
        Set<String> permissions = new LinkedHashSet<>();
        permissions.add(definition.permission());
        processes.find(definition.target().process(), definition.target().version())
            .map(ProcessDefinition::permissions).ifPresent(permissions::addAll);
        policies.find(definition.filePolicy()).map(FilePolicy::readPermission).ifPresent(permissions::add);
        return permissions;
    }

    boolean allowed(ImportDefinition<?> definition, RequestContext context) {
        return Permissions.allowsAll(context, required(definition), development);
    }

    void require(ImportDefinition<?> definition, RequestContext context) {
        Permissions.requireAll(context, required(definition), development, "Import " + definition.id());
    }

    /**
     * {@link #require}, and at the entry point also the second factor the row process may require
     * (docs/design/10-security.md section 10); the import step itself, like any nested process, is not checked.
     */
    void requireAtEntry(ImportDefinition<?> definition, RequestContext context) {
        require(definition, context);
        processes.find(definition.target().process(), definition.target().version()).ifPresent(process ->
            mfa.require(context, process.mfa(), "Import " + definition.id()));
    }
}

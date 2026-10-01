package com.jabiz.runtime.document;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.report.ReportScopes;
import com.jabiz.runtime.security.Permissions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Who may see an issued document (docs/design/22-documents.md section 4.3): whoever holds every permission it was
 * issued with and has the issuer's values of the data scopes that depend on the caller. Reading, verifying and sending
 * it all ask this; a document the caller may not see is reported as not found.
 */
@Component
public class DocumentAccess {

    private final ReportScopes scopes;
    private final boolean development;

    public DocumentAccess(ReportScopes scopes, Environment environment) {
        this.scopes = scopes;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    public boolean visible(DocumentRun run, RequestContext context) {
        if (!Permissions.allowsAll(context, run.permissions(), development)) {
            return false;
        }
        // Every template's datasets as they are now: a scope that has come to depend on the caller limits readers too.
        for (String template : run.templateVersions().keySet()) {
            if (!scopes.matches(template, run.scope(), context)) {
                return false;
            }
        }
        return scopes.matches(run.scope(), context);
    }
}

package com.jabiz.runtime.query;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Startup self-check (docs/design/19-reports.md section 3.1): every report template has a title
 * ({@code query.<id>}) in every language of the application, since the reports page and exported page headers show
 * it. Column texts are optional: they fall back to the display names of the fields they come from.
 */
@Component
public class ReportMessagesCheck implements PlatformCheck {

    private final MessageCatalog messages;
    private final SqlTemplateRegistry templates;

    public ReportMessagesCheck(MessageCatalog messages, SqlTemplateRegistry templates) {
        this.messages = messages;
        this.templates = templates;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(messages, templates.all());
    }

    static List<CheckProblem> problems(MessageCatalog messages, Collection<AdvancedQueryDefinition> queries) {
        List<String> titles = queries.stream()
            .filter(query -> query.report() != null)
            .map(AdvancedQueryDefinition::queryId)
            .map(id -> "query." + id)
            .toList();
        return messages.missing(titles).stream()
            .map(missing -> CheckProblem.error("MESSAGES", missing, "no title for the report"))
            .toList();
    }
}

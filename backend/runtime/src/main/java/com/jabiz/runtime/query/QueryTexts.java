package com.jabiz.runtime.query;

import com.jabiz.entity.MetaModelExporter;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * The display texts of a SQL template (docs/design/19-reports.md section 3.1): its title {@code query.<id>} and
 * column names {@code query.<id>.<column>}, a column without its own text falling back to the display name of the
 * field it comes from, then to its name. The catalog and the exports use the same texts.
 */
@Component
public class QueryTexts {

    private final MessageCatalog messages;

    public QueryTexts(MessageCatalog messages) {
        this.messages = messages;
    }

    public String title(AdvancedQueryDefinition query, Locale locale) {
        return messages.find("query." + query.queryId(), locale).orElse(query.queryId());
    }

    public String column(AdvancedQueryDefinition query, ProjectedField field, Locale locale) {
        return messages.find("query." + query.queryId() + "." + field.name(), locale)
            .or(() -> field.sourceEntity() == null ? Optional.empty()
                : messages.find(MetaModelExporter.labelKey(field.sourceEntity(), field.sourceField()), locale))
            .orElse(field.name());
    }

    /** A platform text by key, else the given default. */
    public String text(String key, Locale locale, String fallback) {
        return messages.find(key, locale).orElse(fallback);
    }
}

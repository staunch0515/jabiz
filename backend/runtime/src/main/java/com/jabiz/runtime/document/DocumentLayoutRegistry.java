package com.jabiz.runtime.document;

import com.jabiz.document.DocumentLayout;
import com.jabiz.document.DocumentLayoutProblems;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The application's document layouts (beans of {@link DocumentLayout}, docs/design/22-documents.md section 2) and
 * their startup check {@code DOCUMENTS}: unique ids, templates that exist and return the columns shown, parameters
 * that agree across a layout's templates, a known subject entity, the texts in every language of the application and a
 * page size the platform knows.
 */
@Component
public class DocumentLayoutRegistry implements PlatformCheck {

    static final String CATEGORY = "DOCUMENTS";

    private final Map<String, DocumentLayout> layouts = new TreeMap<>();
    private final List<CheckProblem> duplicates = new ArrayList<>();
    private final SqlTemplateRegistry templates;
    private final EntityDefinitionRegistry entities;
    private final MessageCatalog messages;
    private final DocumentSettings settings;

    public DocumentLayoutRegistry(ObjectProvider<DocumentLayout> beans, SqlTemplateRegistry templates,
        EntityDefinitionRegistry entities, MessageCatalog messages, DocumentSettings settings) {
        this.templates = templates;
        this.entities = entities;
        this.messages = messages;
        this.settings = settings;
        beans.orderedStream().forEach(layout -> {
            if (layouts.putIfAbsent(layout.id(), layout) != null) {
                duplicates.add(CheckProblem.error(CATEGORY, layout.id(), "declared twice"));
            }
        });
    }

    public Optional<DocumentLayout> find(String id) {
        return Optional.ofNullable(layouts.get(id));
    }

    public Collection<DocumentLayout> all() {
        return layouts.values();
    }

    /** The templates of a layout, prepared (kinds resolved), in the layout's order; missing ones left out. */
    public List<AdvancedQueryDefinition> templatesOf(DocumentLayout layout) {
        List<AdvancedQueryDefinition> found = new ArrayList<>();
        for (String id : layout.templates()) {
            templates.find(id).map(templates::prepare).ifPresent(found::add);
        }
        return found;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(duplicates);
        if (!settings.configuredPageSize().isEmpty() && DocumentSettings.parse(settings.configuredPageSize()) == null) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.documents.page-size",
                "must be A4 or LETTER, was " + settings.configuredPageSize()));
        }
        for (DocumentLayout layout : layouts.values()) {
            for (DocumentLayoutProblems.Problem problem : DocumentLayoutProblems.of(layout,
                id -> templates.find(id).map(this::prepared))) {
                problems.add(CheckProblem.error(CATEGORY, problem.location(), problem.message()));
            }
            if (layout.subjectEntity() != null && entities.find(layout.subjectEntity()).isEmpty()) {
                problems.add(CheckProblem.error(CATEGORY, layout.id(), "unknown subject entity "
                    + layout.subjectEntity()));
            }
            for (String missing : messages.missing(layout.requiredMessages())) {
                problems.add(CheckProblem.error("MESSAGES", missing, "no text for document layout " + layout.id()));
            }
        }
        return problems;
    }

    /** The template with its kinds resolved; as declared when that fails (the template check reports why). */
    private AdvancedQueryDefinition prepared(AdvancedQueryDefinition query) {
        try {
            return templates.prepare(query);
        } catch (RuntimeException e) {
            return query;
        }
    }
}

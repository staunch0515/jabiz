package com.jabiz.runtime.erasure;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.document.DocumentLayoutRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.security.erasure.ErasedEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup check of the applications' erasers (docs/design/10-security.md section 17; decision D62 items 7 and 8): each
 * entity known and declared once per user field, its user field and personal fields fields of it, a field cleared on
 * erasure optional and changeable, a field the purge overwrites optional or text that can hold the mark
 * {@value ErasureValues#TEXT_MARK}, and the document layouts declared.
 */
@Component
public class ErasureChecks implements PlatformCheck {

    public static final String CATEGORY = "ERASURE";

    private final PersonalDataErasers erasers;
    private final EntityDefinitionRegistry entities;
    private final DocumentLayoutRegistry layouts;

    public ErasureChecks(PersonalDataErasers erasers, EntityDefinitionRegistry entities,
        DocumentLayoutRegistry layouts) {
        this.erasers = erasers;
        this.entities = entities;
        this.layouts = layouts;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> undeclared = new HashSet<>();
        for (ErasedEntity erased : erasers.entities()) {
            String location = "PersonalDataEraser " + erased.entity();
            // Once per user field: an entity may be found by several (decision D62 item 8).
            if (!seen.add(erased.entity() + "\n" + erased.userField())) {
                problems.add(CheckProblem.error(CATEGORY, location, "declared more than once with the user field "
                    + erased.userField()));
            }
            EntityDefinition def = entities.find(erased.entity()).orElse(null);
            if (def == null) {
                // Once, however many times it is declared.
                if (undeclared.add(erased.entity())) {
                    problems.add(CheckProblem.error(CATEGORY, location, "entity " + erased.entity()
                        + " is not declared"));
                }
                continue;
            }
            if (def.fields.get(erased.userField()) == null) {
                problems.add(CheckProblem.error(CATEGORY, location, "user field " + erased.userField()
                    + " is not a field of " + def.name));
            }
            for (String name : erased.fields()) {
                FieldDefinition field = def.fields.get(name);
                if (field == null) {
                    problems.add(CheckProblem.error(CATEGORY, location, "field " + name + " is not a field of "
                        + def.name));
                    continue;
                }
                if (name.equals(def.primaryKey)) {
                    problems.add(CheckProblem.error(CATEGORY, location, "field " + name + " is the primary key: rows "
                        + "keep their keys (decision D62 item 4)"));
                    continue;
                }
                if (erased.action() == ErasedEntity.RowAction.CLEAR && (field.required() || field.immutable())) {
                    problems.add(CheckProblem.error(CATEGORY, location, "field " + name + " is cleared on erasure, "
                        + "so it must be optional and changeable; declare the entity delete(...) or keep(...)"));
                }
                if (field.required() && !overwritable(field.kind())) {
                    problems.add(CheckProblem.error(CATEGORY, location, "field " + name + " is required and not "
                        + "text: the purge has nothing to overwrite it with"));
                }
            }
            for (String layout : erased.documents()) {
                if (layouts.find(layout).isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "document layout " + layout
                        + " is not declared"));
                }
            }
        }
        return List.copyOf(problems);
    }

    /** Whether a required field's column can take the purge's mark: text long enough, or JSON. */
    static boolean overwritable(SemanticKind kind) {
        if (kind instanceof SemanticKind.Text text) {
            return text.maxLength() == null || text.maxLength() >= ErasureValues.TEXT_MARK.length();
        }
        return kind instanceof SemanticKind.Custom;
    }
}

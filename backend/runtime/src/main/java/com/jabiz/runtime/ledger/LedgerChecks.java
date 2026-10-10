package com.jabiz.runtime.ledger;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup self-check of the ledger's analysis dimensions (docs/design/11-ledger-events-jobs.md section 1.5): each
 * position and name declared once; a dimension whose values come from an entity names an entity with a default
 * dataset and a text, code, identity (its primary key) or reference field; ids are UUIDs (of a temporal entity), and
 * a field of a temporal entity that may change is warned about. Dictionaries may be filled in the database later, so
 * they are not checked here.
 */
@Component
public class LedgerChecks implements PlatformCheck {

    public static final String CATEGORY = "LEDGER";

    private final LedgerDimensionRegistry dimensions;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;

    public LedgerChecks(LedgerDimensionRegistry dimensions, EntityDefinitionRegistry entities,
        DatasetRegistry datasets) {
        this.dimensions = dimensions;
        this.entities = entities;
        this.datasets = datasets;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<Integer> positions = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (LedgerDimension dimension : dimensions.all()) {
            String location = "LedgerDimension " + dimension.name();
            if (!positions.add(dimension.position())) {
                problems.add(CheckProblem.error(CATEGORY, location, "position " + dimension.position()
                    + " is declared by more than one dimension"));
            }
            if (!names.add(dimension.name())) {
                problems.add(CheckProblem.error(CATEGORY, location, "declared more than once"));
            }
            if (dimension.source() instanceof LedgerDimension.EntitySource source) {
                var entity = entities.find(source.entity());
                if (entity.isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "entity " + source.entity()
                        + " is not declared"));
                    continue;
                }
                checkField(entity.get(), source.field(), location, problems);
                if (datasets.findForEntity(source.entity()).isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "entity " + source.entity()
                        + " has no default dataset"));
                }
            }
        }
        return problems;
    }

    /** The field of an entity source: its kind, and for ids that they are UUIDs found through an index. */
    private void checkField(EntityDefinition entity, String fieldName, String location, List<CheckProblem> problems) {
        FieldDefinition field = entity.fields.get(fieldName);
        String label = entity.name + "." + fieldName;
        String error = switch (field == null ? null : field.kind()) {
            case SemanticKind.Text text -> null;
            case SemanticKind.Code code -> null;
            // Ids are found through the primary key's index (for a temporal entity, the current-version index);
            // another identity column need not have one.
            case SemanticKind.SemanticIdentity identity when !fieldName.equals(entity.primaryKey) ->
                label + " is an identity field but not the primary key of " + entity.name;
            case SemanticKind.SemanticIdentity identity -> uuids(label, entity.name);
            case SemanticKind.Reference reference -> uuids(label, reference.targetEntity());
            case null, default -> label + " is not a text, code, identity or reference field";
        };
        if (error != null) {
            problems.add(CheckProblem.error(CATEGORY, location, error));
        } else if (entity.temporal && !field.immutable()) {
            // Only a condition on an immutable field narrows the versions read (decision D29).
            problems.add(CheckProblem.warning(CATEGORY, location, label + " may change, so its values are looked up"
                + " among all current instances of " + entity.name + "; make it immutable"));
        }
    }

    /**
     * Null if the instances of {@code identified} have UUIDs, which values of id dimensions must be: those of a
     * temporal entity ({@link EntityDefinition#normalizeId}); any other id would be refused at every posting.
     */
    private String uuids(String label, String identified) {
        EntityDefinition def = entities.find(identified).orElse(null);
        if (def == null) {
            return label + " refers to " + identified + ", which is not declared";
        }
        return def.temporal ? null : label + " does not hold UUIDs: " + identified + " is not temporal";
    }
}

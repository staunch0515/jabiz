package com.jabiz.runtime.ledger;

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
 * dataset and a text or code field. Dictionaries may be filled in the database later, so they are not checked here.
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
                FieldDefinition field = entity.get().fields.get(source.field());
                if (field == null || !(field.kind() instanceof SemanticKind.Text
                    || field.kind() instanceof SemanticKind.Code)) {
                    problems.add(CheckProblem.error(CATEGORY, location, source.entity() + "." + source.field()
                        + " is not a text or code field"));
                }
                if (datasets.findForEntity(source.entity()).isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "entity " + source.entity()
                        + " has no default dataset"));
                }
            }
        }
        return problems;
    }
}

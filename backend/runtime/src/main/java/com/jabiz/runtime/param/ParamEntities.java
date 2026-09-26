package com.jabiz.runtime.param;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.param.ParamKinds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Business parameters as the temporal platform entity {@code SysParam} (docs/design/04-temporal-append-only.md
 * section 9, decision D13): rates, thresholds and the like, whose changes can be scheduled ahead. The kind is stored
 * with the value and cannot change; every write path checks that the value conforms to it.
 */
@Configuration
public class ParamEntities {

    public static final String ENTITY = "SysParam";
    public static final String DATASET = "urn:jabiz:dataset:platform:SysParam";

    public static final String KEY = "paramKey";
    public static final String KIND = "valueKind";
    public static final String VALUE = "value";
    public static final String DESCRIPTION = "description";

    public static final EntityDefinition SYS_PARAM = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sys_param_version");
        eb.primaryKey("paramId");
        eb.field("paramId", f -> f.physicalColumn("param_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:param"));
        eb.field(KEY, f -> f.physicalColumn("param_key").immutable(true).required(true).asText(200));
        eb.field(KIND, f -> f.physicalColumn("value_kind").immutable(true).required(true)
            .asCustom(ParamKindSupport.KIND_ID, Map.of()));
        eb.field(VALUE, f -> f.physicalColumn("param_value").required(true).asText(4000));
        eb.field(DESCRIPTION, f -> f.physicalColumn("description").asText(500));
        eb.unique("uk_sys_param_key", KEY);
        // The value is text; whether it is a value of the parameter's kind depends on the other field.
        eb.check(PlatformErrorCodes.PARAM_VALUE_INVALID, (state, ctx) -> checkValue(state));
        eb.temporal(t -> t.allowScheduled(true));
        eb.listView("default", lv -> lv
            .columns(KEY, VALUE, DESCRIPTION, "effectStartTime")
            .filters(KEY)
            .sorts(KEY, "effectStartTime")
            .defaultSort(KEY, true));
    });

    static List<Violation> checkValue(Map<String, Object> state) {
        Object spec = state.get(KIND);
        Object value = state.get(VALUE);
        if (!(spec instanceof Map<?, ?> kindSpec) || value == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        SemanticKind kind = ParamKinds.parse((Map<String, ?>) kindSpec);
        try {
            String canonical = ParamKinds.canonical(kind, value);
            if (canonical.equals(value)) {
                return List.of();
            }
            // Stored text is always canonical, whichever path wrote it (decision D13).
            return List.of(new Violation(VALUE, PlatformErrorCodes.PARAM_VALUE_INVALID,
                "Value of parameter " + state.get(KEY) + " must be written as " + canonical,
                Map.of("kind", String.valueOf(kindSpec.get("type")))));
        } catch (IllegalArgumentException e) {
            return List.of(new Violation(VALUE, PlatformErrorCodes.PARAM_VALUE_INVALID,
                "Value of parameter " + state.get(KEY) + " is not a " + kindSpec.get("type") + ": " + e.getMessage(),
                Map.of("kind", String.valueOf(kindSpec.get("type")))));
        }
    }

    @Bean
    EntityDefinition sysParamEntity() {
        return SYS_PARAM;
    }

    @Bean
    DatasetDefinition sysParamDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions(ParamPermissions.READ, ParamPermissions.WRITE)
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

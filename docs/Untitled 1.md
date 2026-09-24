下面是重构后的完整后端代码架构。所有改动已落实：

1. **全面纯响应式（Non-blocking Reactive）**：统一使用 Reactor (`Mono` / `Flux`)，将底层存储抽象 `StorageEngine`、查询执行器、解析器与业务管理器全面响应式化。
2. **事务原子性保证**：`commitBatch` 整体纳入 `StorageEngine.inTransaction` 闭包，任何阶段失败自动回滚。
3. **消除对话与临时残留痕迹**：删除了原代码中类似“上一轮讨论”、“来自你已有的”等对话式注释及死代码，移除了 `com.jabiz.process.handler` 中同名重复冲突类。
4. **时钟可测试性解耦**：引入统一的 `java.time.Clock` 注入，废除代码中写死的 `Instant.now()`。
5. **统一元模型注册中心**：废弃全局静态的 `EntityRegistry`，统一收敛到 Spring 管理的 `EntityDefinitionRegistry`。
6. **补齐规则防线**：`FieldRule.validate()` 真实执行断言判断，修复 `ValidationException` 的继承和字段存储。
7. **全英文注释规范**。

### 1. 基础异常与时钟配置

Java

```
// File: ./src/main/java/com/jabiz/config/ClockConfig.java
package com.jabiz.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/Violation.java
package com.jabiz.entity;

public record Violation(String field, String ruleCode, String message) {}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/ValidationException.java
package com.jabiz.entity;

import java.util.Collections;
import java.util.List;

public class ValidationException extends RuntimeException {

    private final List<Violation> violations;

    public ValidationException(List<Violation> violations) {
        super("Entity validation failed with " + violations.size() + " violation(s)");
        this.violations = violations != null ? List.copyOf(violations) : List.of();
    }

    public List<Violation> getViolations() {
        return Collections.unmodifiableList(violations);
    }
}
```

### 2. 实体元模型与规则体系 (Entity & Meta-model)

Java

```
// File: ./src/main/java/com/jabiz/entity/DimensionType.java
package com.jabiz.entity;

public enum DimensionType {
    MASS,
    LENGTH,
    VOLUME,
    TIME
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/TemporalRole.java
package com.jabiz.entity;

public enum TemporalRole {
    EVENT_TIME,
    SYSTEM_RECORDED,
    VALID_FROM,
    VALID_TO,
    VALID_TIME,
    TRANSACTION_TIME
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/SemanticKind.java
package com.jabiz.entity;

import java.util.List;

public sealed interface SemanticKind {
    record None() implements SemanticKind {}
    record SemanticIdentity(String urn) implements SemanticKind {}
    record Monetary(String currency, int scale) implements SemanticKind {}
    record PhysicalQuantity(DimensionType dimension, String unitUrn) implements SemanticKind {}
    record Temporal(TemporalRole role) implements SemanticKind {}
    record SpatialH3(int resolution) implements SemanticKind {}
    record Code(String dictUrn, List<String> allowedValues) implements SemanticKind {}
    record Version() implements SemanticKind {}
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/RuleSpec.java
package com.jabiz.entity;

import java.util.Map;

public record RuleSpec(String code, String kind, Map<String, Object> params) {}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/FieldRule.java
package com.jabiz.entity;

import java.util.Objects;
import java.util.function.Predicate;

public record FieldRule(String code, Predicate<Object> predicate) {

    public FieldRule {
        Objects.requireNonNull(code, "Rule code cannot be null");
        Objects.requireNonNull(predicate, "Predicate cannot be null");
    }

    public void validate(Object value) {
        if (!predicate.test(value)) {
            throw new IllegalArgumentException(String.format("Validation rule failed: [%s] on value [%s]", code, value));
        }
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/FieldDefinition.java
package com.jabiz.entity;

import java.util.List;

public record FieldDefinition(
    String name,
    String physicalColumn,
    boolean immutable,
    SemanticKind kind,
    List<FieldRule> rules,
    List<RuleSpec> ruleSpecs
) {
    public FieldDefinition {
        rules = rules != null ? List.copyOf(rules) : List.of();
        ruleSpecs = ruleSpecs != null ? List.copyOf(ruleSpecs) : List.of();
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/StateTransitionRule.java
package com.jabiz.entity;

import java.util.List;

public record StateTransitionRule(String from, List<String> to) {
    public StateTransitionRule {
        to = to != null ? List.copyOf(to) : List.of();
    }

    public boolean canTransitionTo(String targetState) {
        return to.contains(targetState);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/SpatialGuardRule.java
package com.jabiz.entity;

import java.util.function.LongPredicate;

public record SpatialGuardRule(String targetStatus, String locationField, LongPredicate guard) {}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/FieldBuilder.java
package com.jabiz.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class FieldBuilder {
    private final String name;
    private String physicalColumn;
    private boolean immutable = false;
    private SemanticKind kind = new SemanticKind.None();
    private final List<FieldRule> rules = new ArrayList<>();
    private final List<RuleSpec> ruleSpecs = new ArrayList<>();

    public FieldBuilder(String name) {
        this.name = name;
        this.physicalColumn = name;
    }

    public FieldBuilder physicalColumn(String col) {
        this.physicalColumn = col;
        return this;
    }

    public FieldBuilder immutable(boolean v) {
        this.immutable = v;
        return this;
    }

    public FieldBuilder asSemanticIdentity(String urn) {
        this.kind = new SemanticKind.SemanticIdentity(urn);
        return this;
    }

    public FieldBuilder asMonetary(String currency, int scale) {
        this.kind = new SemanticKind.Monetary(currency, scale);
        return this;
    }

    public FieldBuilder asPhysicalQuantity(DimensionType dim, String unitUrn) {
        this.kind = new SemanticKind.PhysicalQuantity(dim, unitUrn);
        return this;
    }

    public FieldBuilder asTemporal(TemporalRole role) {
        this.kind = new SemanticKind.Temporal(role);
        return this;
    }

    public FieldBuilder asSpatialH3(int resolution) {
        this.kind = new SemanticKind.SpatialH3(resolution);
        return this;
    }

    public FieldBuilder asCode(String dictUrn, String... values) {
        this.kind = new SemanticKind.Code(dictUrn, List.of(values));
        return this;
    }

    public FieldBuilder rule(String code, String kind, Map<String, Object> params, Predicate<Object> predicate) {
        ruleSpecs.add(new RuleSpec(code, kind, params));
        rules.add(new FieldRule(code, predicate));
        return this;
    }

    public FieldBuilder rule(String code, Predicate<Object> predicate) {
        rules.add(new FieldRule(code, predicate));
        return this;
    }

    public FieldDefinition build() {
        return new FieldDefinition(
            name,
            physicalColumn,
            immutable,
            kind,
            rules,
            ruleSpecs
        );
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/StateTransitionBuilder.java
package com.jabiz.entity;

import java.util.ArrayList;
import java.util.List;

public final class StateTransitionBuilder {
    private final List<StateTransitionRule> rules = new ArrayList<>();

    public FromClause from(String state) {
        return new FromClause(state);
    }

    public final class FromClause {
        private final String from;

        FromClause(String state) {
            this.from = state;
        }

        public StateTransitionBuilder to(String... targets) {
            rules.add(new StateTransitionRule(from, List.of(targets)));
            return StateTransitionBuilder.this;
        }
    }

    List<StateTransitionRule> build() {
        return List.copyOf(rules);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/EntityDefinition.java
package com.jabiz.entity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class EntityDefinition {
    public final String name;
    public final String physicalTable;
    public final String primaryKey;
    public final Map<String, FieldDefinition> fields;
    public final List<StateTransitionRule> transitions;
    public final List<SpatialGuardRule> spatialGuards;

    EntityDefinition(
        String name,
        String physicalTable,
        String primaryKey,
        Map<String, FieldDefinition> fields,
        List<StateTransitionRule> transitions,
        List<SpatialGuardRule> spatialGuards
    ) {
        this.name = name;
        this.physicalTable = physicalTable;
        this.primaryKey = primaryKey;
        this.fields = Collections.unmodifiableMap(fields);
        this.transitions = List.copyOf(transitions);
        this.spatialGuards = List.copyOf(spatialGuards);
    }

    public static EntityDefinition define(String name, Consumer<EntityBuilder> block) {
        EntityBuilder b = new EntityBuilder(name);
        block.accept(b);
        return b.build();
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/EntityBuilder.java
package com.jabiz.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

public final class EntityBuilder {
    private final String name;
    private String physicalTable;
    private String primaryKey;
    private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();
    private final List<StateTransitionRule> transitions = new ArrayList<>();
    private final List<SpatialGuardRule> spatialGuards = new ArrayList<>();

    EntityBuilder(String name) {
        this.name = name;
    }

    public void physicalTable(String table) {
        this.physicalTable = table;
    }

    public void primaryKey(String key) {
        this.primaryKey = key;
    }

    public void field(String name, Consumer<FieldBuilder> block) {
        FieldBuilder fb = new FieldBuilder(name);
        block.accept(fb);
        fields.put(name, fb.build());
    }

    public void stateTransitions(String statusField, Consumer<StateTransitionBuilder> block) {
        StateTransitionBuilder stb = new StateTransitionBuilder();
        block.accept(stb);
        transitions.addAll(stb.build());
    }

    public void spatialGuard(String targetStatus, String locationField, LongPredicate guard) {
        spatialGuards.add(new SpatialGuardRule(targetStatus, locationField, guard));
    }

    public EntityDefinition build() {
        return new EntityDefinition(
            name,
            physicalTable,
            primaryKey,
            fields,
            transitions,
            spatialGuards
        );
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/EntityDefinitionRegistry.java
package com.jabiz.entity;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class EntityDefinitionRegistry {

    private final Map<String, EntityDefinition> definitions = new ConcurrentHashMap<>();

    public void register(EntityDefinition def) {
        definitions.put(def.name, def);
    }

    public EntityDefinition getOrThrow(String entityType) {
        EntityDefinition def = definitions.get(entityType);
        if (def == null) {
            throw new IllegalArgumentException("Unrecognized EntityDefinition: " + entityType);
        }
        return def;
    }

    public boolean contains(String entityType) {
        return definitions.containsKey(entityType);
    }

    public Collection<EntityDefinition> all() {
        return definitions.values();
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/BaseEntityDefinitions.java
package com.jabiz.entity;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

public abstract class BaseEntityDefinitions {

    protected static Consumer<FieldBuilder> semanticIdentity(String physicalColumn, String urn) {
        return f -> f.physicalColumn(physicalColumn).immutable(true).asSemanticIdentity(urn);
    }

    protected static Consumer<FieldBuilder> systemRecordedTime(String physicalColumn) {
        return f -> f.physicalColumn(physicalColumn).immutable(true).asTemporal(TemporalRole.TRANSACTION_TIME);
    }

    protected static Consumer<FieldBuilder> temporalCausality(
        String physicalColumn,
        String ruleCode,
        int toleranceSeconds,
        Clock clock
    ) {
        return f -> f.physicalColumn(physicalColumn)
            .asTemporal(TemporalRole.VALID_TIME)
            .rule(
                ruleCode,
                "NOT_FUTURE",
                Map.of("toleranceSeconds", toleranceSeconds),
                v -> {
                    Instant target = (v instanceof Instant inst) ? inst : Instant.parse(String.valueOf(v));
                    return !target.isAfter(clock.instant().plusSeconds(toleranceSeconds));
                }
            );
    }

    protected static Consumer<FieldBuilder> nonNegativeMonetary(
        String physicalColumn,
        String ruleCode,
        String currency,
        int scale
    ) {
        return f -> f.physicalColumn(physicalColumn)
            .asMonetary(currency, scale)
            .rule(
                ruleCode,
                "RANGE",
                Map.of("min", 0.0),
                v -> ((Number) v).doubleValue() >= 0.0
            );
    }

    protected static Consumer<FieldBuilder> rangeQuantity(
        String physicalColumn,
        String ruleCode,
        DimensionType dimension,
        String unitUrn,
        double min,
        double max
    ) {
        return f -> f.physicalColumn(physicalColumn)
            .asPhysicalQuantity(dimension, unitUrn)
            .rule(
                ruleCode,
                "RANGE",
                Map.of("min", min, "max", max),
                v -> {
                    double val = ((Number) v).doubleValue();
                    return val >= min && val <= max;
                }
            );
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/WaybillEntityDefinitions.java
package com.jabiz.entity;

import java.time.Clock;
import java.util.Set;

public final class WaybillEntityDefinitions extends BaseEntityDefinitions {

    public static EntityDefinition create(Clock clock) {
        return EntityDefinition.define("WaybillTracking", eb -> {
            eb.physicalTable("t_legacy_waybill_2026");
            eb.primaryKey("waybillId");

            eb.field("waybillId", semanticIdentity("f_wb_sn", "urn:ubos:entity:logistics:waybill"));
            eb.field("freightCharge", nonNegativeMonetary("f_charge_amt", "NON_NEGATIVE_CHARGE", "JPY", 0));
            eb.field("totalWeight", rangeQuantity(
                "f_gross_wt", "VALID_MASS_RANGE", DimensionType.MASS, "urn:unit:si:kilogram", 10.0, 50000.0
            ));
            eb.field("shippedTime", temporalCausality("f_shipped_timestamp", "TEMPORAL_CAUSALITY", 300, clock));
            eb.field("recordedTime", systemRecordedTime("f_sys_created_at"));
            eb.field("currentLocation", f -> f.physicalColumn("f_current_h3_cell").asSpatialH3(8));
            eb.field("status", f -> f.physicalColumn("f_status_code")
                .asCode("urn:ubos:dict:waybill_status", "CREATED", "IN_TRANSIT", "CUSTOMS_CLEARED", "DELIVERED"));

            eb.stateTransitions("status", st -> {
                st.from("CREATED").to("IN_TRANSIT");
                st.from("IN_TRANSIT").to("CUSTOMS_CLEARED");
                st.from("CUSTOMS_CLEARED").to("DELIVERED");
            });

            eb.spatialGuard("CUSTOMS_CLEARED", "currentLocation", h3Index -> {
                Set<Long> tokyoPortCustomsCells = Set.of(0x882f516a23ffffL, 0x882f516a21ffffL);
                return tokyoPortCustomsCells.contains(h3Index);
            });
        });
    }

    private WaybillEntityDefinitions() {}
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/PriceEntityDefinitions.java
package com.jabiz.entity;

import java.time.Clock;

public final class PriceEntityDefinitions extends BaseEntityDefinitions {

    public static EntityDefinition create(Clock clock) {
        return EntityDefinition.define("PriceVersion", eb -> {
            eb.physicalTable("t_price_version");
            eb.primaryKey("priceId");

            eb.field("amount", nonNegativeMonetary("f_amount", "NON_NEGATIVE_AMOUNT", "JPY", 0));
            eb.field("effectiveTime", temporalCausality("f_effective_at", "PRICE_CAUSALITY", 0, clock));
            eb.field("recordedTime", systemRecordedTime("f_created_at"));
        });
    }

    private PriceEntityDefinitions() {}
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/EntityDefinitionsConfig.java
package com.jabiz.entity;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class EntityDefinitionsConfig {

    private final EntityDefinitionRegistry registry;
    private final Clock clock;

    public EntityDefinitionsConfig(EntityDefinitionRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @PostConstruct
    public void registerDefaults() {
        registry.register(WaybillEntityDefinitions.create(clock));
        registry.register(PriceEntityDefinitions.create(clock));
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/EntityValidator.java
package com.jabiz.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EntityValidator {

    private EntityValidator() {}

    public static List<Violation> validate(EntityDefinition def, Map<String, Object> instance, boolean isUpdate) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDefinition field : def.fields.values()) {
            Object value = instance.get(field.name());

            if (isUpdate && field.immutable()) {
                continue;
            }
            if (value == null) {
                continue;
            }

            for (FieldRule rule : field.rules()) {
                try {
                    rule.validate(value);
                } catch (Exception ex) {
                    violations.add(new Violation(field.name(), rule.code(), ex.getMessage()));
                }
            }
        }
        return violations;
    }

    public static void requireValid(EntityDefinition def, Map<String, Object> instance, boolean isUpdate) {
        List<Violation> violations = validate(def, instance, isUpdate);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/GenericRowMapper.java
package com.jabiz.entity;

import io.r2dbc.spi.Row;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GenericRowMapper {

    private GenericRowMapper() {}

    public static Map<String, Object> toMap(EntityDefinition def, Row row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldDefinition field : def.fields.values()) {
            try {
                Object value = row.get(field.physicalColumn());
                result.put(field.name(), value);
            } catch (IllegalArgumentException ignored) {
                // Column projection omission fallback
            }
        }
        return result;
    }

    public static Map<String, Object> toColumns(EntityDefinition def, Map<String, Object> logical) {
        Map<String, Object> physical = new LinkedHashMap<>();
        logical.forEach((key, value) -> {
            FieldDefinition field = def.fields.get(key);
            if (field != null) {
                physical.put(field.physicalColumn(), value);
            }
        });
        return physical;
    }

    public static Map<String, Object> toUpdatableColumns(EntityDefinition def, Map<String, Object> logical) {
        Map<String, Object> physical = new LinkedHashMap<>();
        logical.forEach((key, value) -> {
            FieldDefinition field = def.fields.get(key);
            if (field != null && !field.immutable()) {
                physical.put(field.physicalColumn(), value);
            }
        });
        return physical;
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/MetaModelExporter.java
package com.jabiz.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MetaModelExporter {

    private MetaModelExporter() {}

    public static Map<String, Object> export(EntityDefinition def) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldDefinition f : def.fields.values()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", f.name());
            json.put("immutable", f.immutable());
            json.putAll(kindToJson(f.kind()));
            json.put("rules", f.ruleSpecs());
            fields.add(json);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entity", def.name);
        root.put("primaryKey", def.primaryKey);
        root.put("fields", fields);
        root.put("stateTransitions", def.transitions);
        return root;
    }

    private static Map<String, Object> kindToJson(SemanticKind kind) {
        return switch (kind) {
            case SemanticKind.SemanticIdentity si -> Map.of("type", "semanticIdentity", "urn", si.urn());
            case SemanticKind.Monetary m -> Map.of("type", "monetary", "currency", m.currency(), "scale", m.scale());
            case SemanticKind.PhysicalQuantity q -> Map.of("type", "physicalQuantity", "dimension", q.dimension().name(), "unit", q.unitUrn());
            case SemanticKind.Temporal t -> Map.of("type", "temporal", "role", t.role().name());
            case SemanticKind.SpatialH3 s -> Map.of("type", "spatialH3", "resolution", s.resolution());
            case SemanticKind.Code c -> Map.of("type", "code", "dictUrn", c.dictUrn(), "allowedValues", c.allowedValues());
            case SemanticKind.None ignored -> Map.of("type", "none");
            case SemanticKind.Version ignored -> Map.of("type", "version");
        };
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/entity/MetaModelConsistencyChecker.java
package com.jabiz.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@Component
public class MetaModelConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(MetaModelConsistencyChecker.class);
    private final DatabaseClient db;
    private final EntityDefinitionRegistry entityRegistry;

    public MetaModelConsistencyChecker(DatabaseClient db, EntityDefinitionRegistry entityRegistry) {
        this.db = db;
        this.entityRegistry = entityRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        Flux.fromIterable(entityRegistry.all())
            .flatMap(this::validateEntity)
            .doOnError(err -> log.error("MetaModel startup consistency validation failed", err))
            .blockLast(Duration.ofSeconds(30));
    }

    private Mono<Void> validateEntity(EntityDefinition def) {
        return fetchColumns(def.physicalTable)
            .flatMap(actualColumns -> {
                if (actualColumns.isEmpty()) {
                    return Mono.error(new MetaModelInconsistencyException(
                        "Metadata table " + def.name + " mapped to " + def.physicalTable + " not found or empty"
                    ));
                }

                Set<String> missing = new HashSet<>();
                for (FieldDefinition field : def.fields.values()) {
                    if (!actualColumns.contains(field.physicalColumn())) {
                        missing.add(field.name() + " -> " + field.physicalColumn());
                    }
                }

                if (!missing.isEmpty()) {
                    return Mono.error(new MetaModelInconsistencyException(
                        "Entity " + def.name + " (" + def.physicalTable + ") has missing columns: " + String.join(", ", missing)
                    ));
                }
                return Mono.empty();
            });
    }

    private Mono<Set<String>> fetchColumns(String tableName) {
        return db.sql("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = :tableName
                """)
            .bind("tableName", tableName)
            .map((row, meta) -> row.get("column_name", String.class))
            .all()
            .collect(HashSet::new, Set::add);
    }

    public static class MetaModelInconsistencyException extends RuntimeException {
        public MetaModelInconsistencyException(String message) {
            super(message);
        }
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/MetaModelController.java
package com.jabiz;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityDefinitionRegistry;
import com.jabiz.entity.MetaModelExporter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/meta")
public class MetaModelController {

    private final EntityDefinitionRegistry registry;

    public MetaModelController(EntityDefinitionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/entities/{name}")
    public Mono<Map<String, Object>> getEntity(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            EntityDefinition def = registry.getOrThrow(name);
            return MetaModelExporter.export(def);
        });
    }
}
```

### 3. 数据集与响应式存储层 (Dataset & Reactive Storage Engine)

Java

```
// File: ./src/main/java/com/jabiz/query/PhysicalQueryPlan.java
package com.jabiz.query;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record PhysicalQueryPlan(
    String targetTable,
    String compiledFilterExpression,
    Map<String, Object> bindParams,
    List<PhysicalSort> sorts,
    int offset,
    int limit,
    Duration timeout
) {
    public record PhysicalSort(String physicalColumn, boolean ascending) {}
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/SortOrder.java
package com.jabiz.query;

public record SortOrder(String field, boolean ascending) {}
```

Java

```
// File: ./src/main/java/com/jabiz/query/QueryPredicate.java
package com.jabiz.query;

import java.util.List;

public sealed interface QueryPredicate {
    record Eq(String field, Object value) implements QueryPredicate {}
    record Ne(String field, Object value) implements QueryPredicate {}
    record Gt(String field, Object value) implements QueryPredicate {}
    record Gte(String field, Object value) implements QueryPredicate {}
    record Lt(String field, Object value) implements QueryPredicate {}
    record Lte(String field, Object value) implements QueryPredicate {}
    record In(String field, List<Object> values) implements QueryPredicate {}
    record And(List<QueryPredicate> predicates) implements QueryPredicate {}
    record Or(List<QueryPredicate> predicates) implements QueryPredicate {}
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/EntityQuery.java
package com.jabiz.query;

import java.util.List;

public record EntityQuery(
    QueryPredicate predicate,
    List<SortOrder> sorts,
    int offset,
    int limit
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private QueryPredicate predicate;
        private List<SortOrder> sorts = List.of();
        private int offset = 0;
        private int limit = 50;

        public Builder where(QueryPredicate p) {
            this.predicate = p;
            return this;
        }

        public Builder orderBy(String field, boolean asc) {
            this.sorts = List.of(new SortOrder(field, asc));
            return this;
        }

        public Builder offset(int o) {
            this.offset = o;
            return this;
        }

        public Builder limit(int l) {
            this.limit = l;
            return this;
        }

        public EntityQuery build() {
            return new EntityQuery(predicate, sorts, offset, limit);
        }
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/dataset/DatasetPolicy.java
package com.jabiz.dataset;

import java.time.Duration;

public record DatasetPolicy(
    boolean isReadOnly,
    boolean softDelete,
    String softDeleteColumn,
    int maxQueryBatchSize,
    Duration queryTimeout,
    boolean temporalTracking
) {}
```

Java

```
// File: ./src/main/java/com/jabiz/dataset/StorageRouting.java
package com.jabiz.dataset;

public record StorageRouting(
    String driver,
    String connectionPoolRef,
    String physicalTableOverride,
    String readReplicaRef
) {}
```

Java

```
// File: ./src/main/java/com/jabiz/dataset/DatasetDefinition.java
package com.jabiz.dataset;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

public record DatasetDefinition(
    String resourceId,
    String targetEntityType,
    StorageRouting storage,
    DatasetPolicy policy,
    Map<String, Object> defaultPartitionFilter
) {
    public static DatasetDefinition define(String resourceId, Consumer<Builder> consumer) {
        Builder builder = new Builder(resourceId);
        consumer.accept(builder);
        return builder.build();
    }

    public static class Builder {
        private final String resourceId;
        private String targetEntityType;
        private StorageRouting storage;
        private DatasetPolicy policy;
        private Map<String, Object> partitionFilter = Map.of();

        public Builder(String resourceId) {
            this.resourceId = resourceId;
        }

        public Builder targetEntityType(String entityType) {
            this.targetEntityType = entityType;
            return this;
        }

        public Builder storage(Consumer<StorageBuilder> c) {
            StorageBuilder sb = new StorageBuilder();
            c.accept(sb);
            this.storage = sb.build();
            return this;
        }

        public Builder policy(Consumer<PolicyBuilder> c) {
            PolicyBuilder pb = new PolicyBuilder();
            c.accept(pb);
            this.policy = pb.build();
            return this;
        }

        public Builder defaultPartitionFilter(Map<String, Object> filter) {
            this.partitionFilter = filter != null ? Map.copyOf(filter) : Map.of();
            return this;
        }

        public DatasetDefinition build() {
            return new DatasetDefinition(resourceId, targetEntityType, storage, policy, partitionFilter);
        }
    }

    public static class StorageBuilder {
        private String driver;
        private String connectionPoolRef;
        private String physicalTableOverride;
        private String readReplicaRef;

        public StorageBuilder driver(String d) {
            this.driver = d;
            return this;
        }

        public StorageBuilder connectionPoolRef(String r) {
            this.connectionPoolRef = r;
            return this;
        }

        public StorageBuilder physicalTableOverride(String t) {
            this.physicalTableOverride = t;
            return this;
        }

        public StorageBuilder readReplicaRef(String r) {
            this.readReplicaRef = r;
            return this;
        }

        public StorageRouting build() {
            return new StorageRouting(driver, connectionPoolRef, physicalTableOverride, readReplicaRef);
        }
    }

    public static class PolicyBuilder {
        private boolean readOnly;
        private boolean softDelete;
        private String col;
        private int batch = 100;
        private Duration timeout = Duration.ofSeconds(5);
        private boolean temporal;

        public PolicyBuilder readOnly(boolean ro) {
            this.readOnly = ro;
            return this;
        }

        public PolicyBuilder softDelete(boolean sd, String col) {
            this.softDelete = sd;
            this.col = col;
            return this;
        }

        public PolicyBuilder maxQueryBatchSize(int b) {
            this.batch = b;
            return this;
        }

        public PolicyBuilder queryTimeout(Duration d) {
            this.timeout = d;
            return this;
        }

        public PolicyBuilder temporalTracking(boolean t) {
            this.temporal = t;
            return this;
        }

        public DatasetPolicy build() {
            return new DatasetPolicy(readOnly, softDelete, col, batch, timeout, temporal);
        }
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/storage/StorageEngine.java
package com.jabiz.storage;

import com.jabiz.query.PhysicalQueryPlan;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;

public interface StorageEngine {

    Mono<Map<String, Object>> selectById(String table, String pkColumn, Object id);

    Mono<Void> insert(String table, Map<String, Object> record);

    Mono<Boolean> casUpdate(
        String table,
        String pkColumn,
        Object id,
        long expectedVersion,
        String versionColumn,
        Map<String, Object> updates
    );

    Mono<Boolean> deleteById(String table, String pkColumn, Object id);

    Flux<Map<String, Object>> executeQuery(PhysicalQueryPlan plan);

    <T> Mono<T> inTransaction(Function<StorageEngine, Mono<T>> transactionalAction);
}
```

Java

```
// File: ./src/main/java/com/jabiz/storage/StorageAdapterRegistry.java
package com.jabiz.storage;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class StorageAdapterRegistry {

    private final Map<String, StorageEngine> engineRegistry = new ConcurrentHashMap<>();

    public void registerEngine(String connectionPoolRef, StorageEngine engine) {
        Objects.requireNonNull(connectionPoolRef, "Connection reference cannot be null");
        Objects.requireNonNull(engine, "StorageEngine cannot be null");
        engineRegistry.put(connectionPoolRef, engine);
    }

    public StorageEngine getEngine(String connectionPoolRef) {
        StorageEngine engine = engineRegistry.get(connectionPoolRef);
        if (engine == null) {
            throw new IllegalStateException("No StorageEngine registered for reference: " + connectionPoolRef);
        }
        return engine;
    }

    public StorageEngine unregisterEngine(String connectionPoolRef) {
        return engineRegistry.remove(connectionPoolRef);
    }

    public boolean hasEngine(String connectionPoolRef) {
        return engineRegistry.containsKey(connectionPoolRef);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/storage/R2dbcStorageEngine.java
package com.jabiz.storage;

import com.jabiz.query.PhysicalQueryPlan;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class R2dbcStorageEngine implements StorageEngine {

    private final DatabaseClient db;
    private final TransactionalOperator txOperator;

    public R2dbcStorageEngine(DatabaseClient db, TransactionalOperator txOperator) {
        this.db = db;
        this.txOperator = txOperator;
    }

    @Override
    public Mono<Map<String, Object>> selectById(String table, String pkColumn, Object id) {
        String sql = String.format("SELECT * FROM %s WHERE %s = :id", escapeIdentifier(table), escapeIdentifier(pkColumn));
        return db.sql(sql)
            .bind("id", id)
            .fetch()
            .first();
    }

    @Override
    public Mono<Void> insert(String table, Map<String, Object> record) {
        List<String> cols = new ArrayList<>();
        List<String> params = new ArrayList<>();
        record.forEach((col, val) -> {
            cols.add(escapeIdentifier(col));
            params.add(":" + col);
        });

        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
            escapeIdentifier(table),
            String.join(", ", cols),
            String.join(", ", params)
        );

        DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            spec = entry.getValue() != null ? spec.bind(entry.getKey(), entry.getValue()) : spec.bindNull(entry.getKey(), Object.class);
        }
        return spec.then();
    }

    @Override
    public Mono<Boolean> casUpdate(
        String table,
        String pkColumn,
        Object id,
        long expectedVersion,
        String versionColumn,
        Map<String, Object> updates
    ) {
        List<String> assignments = new ArrayList<>();
        updates.forEach((col, val) -> assignments.add(escapeIdentifier(col) + " = :" + col));
        assignments.add(escapeIdentifier(versionColumn) + " = " + escapeIdentifier(versionColumn) + " + 1");

        String sql = String.format(
            "UPDATE %s SET %s WHERE %s = :_id AND %s = :_ver",
            escapeIdentifier(table),
            String.join(", ", assignments),
            escapeIdentifier(pkColumn),
            escapeIdentifier(versionColumn)
        );

        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
            .bind("_id", id)
            .bind("_ver", expectedVersion);

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            spec = entry.getValue() != null ? spec.bind(entry.getKey(), entry.getValue()) : spec.bindNull(entry.getKey(), Object.class);
        }

        return spec.fetch().rowsUpdated().map(count -> count > 0);
    }

    @Override
    public Mono<Boolean> deleteById(String table, String pkColumn, Object id) {
        String sql = String.format("DELETE FROM %s WHERE %s = :id", escapeIdentifier(table), escapeIdentifier(pkColumn));
        return db.sql(sql)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .map(count -> count > 0);
    }

    @Override
    public Flux<Map<String, Object>> executeQuery(PhysicalQueryPlan plan) {
        StringBuilder sqlBuilder = new StringBuilder();
        if (plan.targetTable() != null && !plan.targetTable().isBlank()) {
            sqlBuilder.append("SELECT * FROM ").append(escapeIdentifier(plan.targetTable())).append(" ");
        }
        sqlBuilder.append(plan.compiledFilterExpression());

        if (!plan.sorts().isEmpty()) {
            sqlBuilder.append(" ORDER BY ");
            List<String> sortClauses = plan.sorts().stream()
                .map(s -> escapeIdentifier(s.physicalColumn()) + (s.ascending() ? " ASC" : " DESC"))
                .toList();
            sqlBuilder.append(String.join(", ", sortClauses));
        }

        if (plan.limit() > 0) {
            sqlBuilder.append(" LIMIT ").append(plan.limit());
        }
        if (plan.offset() > 0) {
            sqlBuilder.append(" OFFSET ").append(plan.offset());
        }

        DatabaseClient.GenericExecuteSpec spec = db.sql(sqlBuilder.toString());
        for (Map.Entry<String, Object> entry : plan.bindParams().entrySet()) {
            spec = entry.getValue() != null ? spec.bind(entry.getKey(), entry.getValue()) : spec.bindNull(entry.getKey(), Object.class);
        }

        return spec.fetch().all().timeout(plan.timeout());
    }

    @Override
    public <T> Mono<T> inTransaction(Function<StorageEngine, Mono<T>> transactionalAction) {
        if (txOperator == null) {
            return transactionalAction.apply(this);
        }
        return txOperator.transactional(transactionalAction.apply(this));
    }

    private String escapeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
```

### 4. 实体运行时与事务性批处理 (Runtime & DatasetEntityManager)

Java

```
// File: ./src/main/java/com/jabiz/runtime/EntityInstance.java
package com.jabiz.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record EntityInstance(
    Object id,
    String entityType,
    long version,
    String state,
    Map<String, Object> attributes
) {
    public EntityInstance {
        attributes = attributes != null ? Collections.unmodifiableMap(new HashMap<>(attributes)) : Map.of();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String logicalFieldName) {
        return (T) attributes.get(logicalFieldName);
    }

    public <T> T getOrDefault(String logicalFieldName, T defaultValue) {
        Object val = attributes.get(logicalFieldName);
        return val != null ? (T) val : defaultValue;
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/runtime/DatasetEntityManager.java
package com.jabiz.runtime;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.*;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.query.QueryCompiler;
import com.jabiz.storage.StorageAdapterRegistry;
import com.jabiz.storage.StorageEngine;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.*;

public class DatasetEntityManager {

    private static final String DEFAULT_VERSION_COLUMN = "f_version";
    private static final String ACTION_PROPERTY = "action";

    private final StorageAdapterRegistry storageRegistry;
    private final EntityDefinitionRegistry entityRegistry;
    private final Clock clock;

    public DatasetEntityManager(
        StorageAdapterRegistry storageRegistry,
        EntityDefinitionRegistry entityRegistry,
        Clock clock
    ) {
        this.storageRegistry = Objects.requireNonNull(storageRegistry, "StorageAdapterRegistry cannot be null");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "EntityDefinitionRegistry cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    public Mono<List<EntityInstance>> commitBatch(
        DatasetDefinition dataset,
        Collection<EntityInstance> instances
    ) {
        if (instances == null || instances.isEmpty()) {
            return Mono.just(List.of());
        }

        try {
            ensureDatasetWritable(dataset);
            int maxBatch = dataset.policy().maxQueryBatchSize() > 0 ? dataset.policy().maxQueryBatchSize() : 500;
            if (instances.size() > maxBatch) {
                return Mono.error(new IllegalArgumentException(String.format(
                    "Batch size [%d] exceeds dataset limit [%d]", instances.size(), maxBatch
                )));
            }
        } catch (Exception ex) {
            return Mono.error(ex);
        }

        StorageEngine engine = resolveEngine(dataset);

        return engine.inTransaction(txEngine ->
            Flux.fromIterable(instances)
                .concatMap(instance -> processInstance(txEngine, dataset, instance))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collectList()
        );
    }

    private Mono<Optional<EntityInstance>> processInstance(
        StorageEngine engine,
        DatasetDefinition dataset,
        EntityInstance instance
    ) {
        String entityType = instance.entityType();
        if (entityType == null || entityType.isBlank()) {
            return Mono.error(new IllegalArgumentException("EntityInstance has missing entityType: " + instance));
        }

        EntityDefinition entityDef = entityRegistry.getOrThrow(entityType);
        String rawAction = instance.get(ACTION_PROPERTY);
        if (rawAction == null || rawAction.isBlank()) {
            return Mono.error(new IllegalArgumentException(String.format(
                "Entity [%s, ID: %s] is missing required '%s' property", entityType, instance.id(), ACTION_PROPERTY
            )));
        }

        EntityAction action = EntityAction.from(rawAction);
        String physicalTable = resolvePhysicalTable(dataset, entityDef);
        String pkCol = resolvePhysicalColumn(entityDef, entityDef.primaryKey);
        String versionCol = resolveVersionColumn(entityDef);
        String stateField = findStateFieldName(entityDef);

        return switch (action) {
            case INSERT -> processInsert(engine, entityDef, physicalTable, pkCol, versionCol, stateField, instance)
                .map(Optional::of);
            case UPDATE -> processUpdate(engine, dataset, entityDef, physicalTable, pkCol, versionCol, stateField, instance)
                .map(Optional::of);
            case DELETE -> processDelete(engine, dataset, entityDef, physicalTable, pkCol, versionCol, instance)
                .thenReturn(Optional.empty());
        };
    }

    private Mono<EntityInstance> processInsert(
        StorageEngine engine,
        EntityDefinition entityDef,
        String physicalTable,
        String pkCol,
        String versionCol,
        String stateField,
        EntityInstance instance
    ) {
        return Mono.fromCallable(() -> {
            Map<String, Object> attrs = cleanAttributes(instance.attributes());
            EntityValidator.requireValid(entityDef, attrs, false);
            validateSemanticConstraints(entityDef, attrs);

            Map<String, Object> physicalRow = new HashMap<>();
            physicalRow.put(pkCol, instance.id());

            long initialVersion = 1L;
            physicalRow.put(versionCol, initialVersion);

            String nowIso = clock.instant().toString();
            entityDef.fields.values().forEach(field -> {
                if (field.kind() instanceof SemanticKind.Temporal temporal
                    && temporal.role() == TemporalRole.SYSTEM_RECORDED) {
                    physicalRow.put(field.physicalColumn(), nowIso);
                }
            });

            for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                FieldDefinition field = entityDef.fields.get(entry.getKey());
                if (field != null) {
                    physicalRow.put(field.physicalColumn(), entry.getValue());
                }
            }

            String initialState = (stateField != null && attrs.containsKey(stateField))
                ? String.valueOf(attrs.get(stateField))
                : instance.state();

            return Map.entry(physicalRow, new EntityInstance(instance.id(), entityDef.name, initialVersion, initialState, attrs));
        }).flatMap(tuple -> engine.insert(physicalTable, tuple.getKey()).thenReturn(tuple.getValue()));
    }

    private Mono<EntityInstance> processUpdate(
        StorageEngine engine,
        DatasetDefinition dataset,
        EntityDefinition entityDef,
        String physicalTable,
        String pkCol,
        String versionCol,
        String stateField,
        EntityInstance instance
    ) {
        return findById(dataset, entityDef, instance.id())
            .switchIfEmpty(Mono.error(new IllegalArgumentException(String.format(
                "Update failed: %s [ID: %s] not found", entityDef.name, instance.id()
            ))))
            .flatMap(current -> {
                Map<String, Object> incomingAttrs = cleanAttributes(instance.attributes());

                for (String fieldName : incomingAttrs.keySet()) {
                    FieldDefinition fd = entityDef.fields.get(fieldName);
                    if (fd != null && fd.immutable()) {
                        Object oldVal = current.attributes().get(fieldName);
                        Object newVal = incomingAttrs.get(fieldName);
                        if (!Objects.equals(oldVal, newVal)) {
                            return Mono.error(new IllegalStateException(String.format(
                                "Immutability violation on %s: Field [%s] cannot be altered", entityDef.name, fieldName
                            )));
                        }
                    }
                }

                try {
                    EntityValidator.requireValid(entityDef, incomingAttrs, true);
                    validateSemanticConstraints(entityDef, incomingAttrs);
                } catch (Exception ex) {
                    return Mono.error(ex);
                }

                String nextState = current.state();
                if (stateField != null && incomingAttrs.containsKey(stateField)) {
                    String candidateState = String.valueOf(incomingAttrs.get(stateField));
                    if (!Objects.equals(current.state(), candidateState)) {
                        boolean allowed = entityDef.transitions.stream()
                            .filter(rule -> Objects.equals(rule.from(), current.state()))
                            .anyMatch(rule -> rule.canTransitionTo(candidateState));

                        if (!allowed) {
                            return Mono.error(new IllegalStateException(String.format(
                                "Illegal transition from [%s] to [%s] on %s [ID: %s]",
                                current.state(), candidateState, entityDef.name, instance.id()
                            )));
                        }

                        try {
                            evaluateSpatialGuard(entityDef, candidateState, incomingAttrs);
                        } catch (Exception ex) {
                            return Mono.error(ex);
                        }
                        nextState = candidateState;
                    }
                }

                Map<String, Object> physicalUpdates = new HashMap<>();
                for (Map.Entry<String, Object> entry : incomingAttrs.entrySet()) {
                    FieldDefinition fd = entityDef.fields.get(entry.getKey());
                    if (fd != null) {
                        physicalUpdates.put(fd.physicalColumn(), entry.getValue());
                    }
                }

                String finalNextState = nextState;
                return engine.casUpdate(
                    physicalTable,
                    pkCol,
                    instance.id(),
                    current.version(),
                    versionCol,
                    physicalUpdates
                ).flatMap(updated -> {
                    if (!updated) {
                        return Mono.error(new ConcurrentModificationException(String.format(
                            "Optimistic lock conflict on %s [ID: %s], expected version [%d]",
                            entityDef.name, instance.id(), current.version()
                        )));
                    }

                    Map<String, Object> mergedAttrs = new HashMap<>(current.attributes());
                    mergedAttrs.putAll(incomingAttrs);

                    return Mono.just(new EntityInstance(
                        instance.id(),
                        entityDef.name,
                        current.version() + 1,
                        finalNextState,
                        mergedAttrs
                    ));
                });
            });
    }

    private Mono<Void> processDelete(
        StorageEngine engine,
        DatasetDefinition dataset,
        EntityDefinition entityDef,
        String physicalTable,
        String pkCol,
        String versionCol,
        EntityInstance instance
    ) {
        return findById(dataset, entityDef, instance.id())
            .switchIfEmpty(Mono.error(new IllegalArgumentException(String.format(
                "Delete failed: %s [ID: %s] not found", entityDef.name, instance.id()
            ))))
            .flatMap(current -> {
                if (dataset.policy().softDelete()) {
                    String softDelCol = dataset.policy().softDeleteColumn();
                    if (softDelCol == null || softDelCol.isBlank()) {
                        softDelCol = "f_is_deleted";
                    }

                    return engine.casUpdate(
                        physicalTable,
                        pkCol,
                        instance.id(),
                        current.version(),
                        versionCol,
                        Map.of(softDelCol, true, "f_sys_updated_at", clock.instant().toString())
                    ).flatMap(updated -> {
                        if (!updated) {
                            return Mono.error(new ConcurrentModificationException(String.format(
                                "Optimistic lock conflict on soft-deleting %s [ID: %s]",
                                entityDef.name, instance.id()
                            )));
                        }
                        return Mono.empty();
                    });
                } else {
                    return engine.deleteById(physicalTable, pkCol, instance.id())
                        .flatMap(deleted -> {
                            if (!deleted) {
                                return Mono.error(new IllegalArgumentException(String.format(
                                    "Physical deletion failed: %s [ID: %s]", entityDef.name, instance.id()
                                )));
                            }
                            return Mono.empty();
                        });
                }
            });
    }

    public Mono<EntityInstance> findById(
        DatasetDefinition dataset,
        EntityDefinition entityDef,
        Object entityId
    ) {
        StorageEngine engine = resolveEngine(dataset);
        String physicalTable = resolvePhysicalTable(dataset, entityDef);
        String pkColumn = resolvePhysicalColumn(entityDef, entityDef.primaryKey);

        return engine.selectById(physicalTable, pkColumn, entityId)
            .map(rawRow -> hydrate(entityDef, rawRow));
    }

    public Flux<EntityInstance> query(
        DatasetDefinition dataset,
        EntityDefinition entityDef,
        EntityQuery query
    ) {
        String targetConnPool = Optional.ofNullable(dataset.storage().readReplicaRef())
            .filter(ref -> !ref.isBlank())
            .orElseGet(() -> dataset.storage().connectionPoolRef());

        StorageEngine engine = storageRegistry.getEngine(targetConnPool);
        QueryCompiler compiler = new QueryCompiler();
        PhysicalQueryPlan plan = compiler.compileSql(dataset, entityDef, query);

        return engine.executeQuery(plan).map(row -> hydrate(entityDef, row));
    }

    private void evaluateSpatialGuard(
        EntityDefinition entityDef,
        String targetState,
        Map<String, Object> incomingAttrs
    ) {
        if (entityDef.spatialGuards == null) return;

        for (SpatialGuardRule guard : entityDef.spatialGuards) {
            if (Objects.equals(guard.targetStatus(), targetState)) {
                String spatialField = guard.locationField();
                Object rawCoordinate = incomingAttrs.get(spatialField);

                if (rawCoordinate == null) {
                    throw new SecurityException(String.format(
                        "Spatial guard rejected: Fresh location coordinate [%s] must be provided to enter state [%s]",
                        spatialField, targetState
                    ));
                }

                Long h3Cell = ((Number) rawCoordinate).longValue();
                if (!guard.guard().test(h3Cell)) {
                    throw new SecurityException(String.format(
                        "Spatial guard rejected: Location [0x%x] not authorized on %s for state [%s]",
                        h3Cell, entityDef.name, targetState
                    ));
                }
            }
        }
    }

    private Map<String, Object> cleanAttributes(Map<String, Object> raw) {
        Map<String, Object> cleaned = new HashMap<>(raw);
        cleaned.remove(ACTION_PROPERTY);
        return cleaned;
    }

    private void ensureDatasetWritable(DatasetDefinition dataset) {
        if (dataset.policy().isReadOnly()) {
            throw new IllegalStateException("Write rejected: Dataset " + dataset.resourceId() + " is marked READ-ONLY");
        }
    }

    private StorageEngine resolveEngine(DatasetDefinition dataset) {
        return storageRegistry.getEngine(dataset.storage().connectionPoolRef());
    }

    private String resolvePhysicalTable(DatasetDefinition dataset, EntityDefinition entityDef) {
        if (Objects.equals(dataset.targetEntityType(), entityDef.name)) {
            String override = dataset.storage().physicalTableOverride();
            if (override != null && !override.isBlank()) {
                return override;
            }
        }
        return entityDef.physicalTable;
    }

    private String resolvePhysicalColumn(EntityDefinition entityDef, String logicalFieldName) {
        FieldDefinition field = entityDef.fields.get(logicalFieldName);
        if (field != null && field.physicalColumn() != null) {
            return field.physicalColumn();
        }
        return logicalFieldName;
    }

    private String resolveVersionColumn(EntityDefinition entityDef) {
        return entityDef.fields.values().stream()
            .filter(f -> f.kind() instanceof SemanticKind.Version)
            .map(FieldDefinition::physicalColumn)
            .findFirst()
            .orElse(DEFAULT_VERSION_COLUMN);
    }

    private String findStateFieldName(EntityDefinition entityDef) {
        if (!entityDef.transitions.isEmpty()) {
            String firstFromState = entityDef.transitions.get(0).from();
            for (FieldDefinition f : entityDef.fields.values()) {
                if (f.kind() instanceof SemanticKind.Code code && code.allowedValues().contains(firstFromState)) {
                    return f.name();
                }
            }
        }

        for (FieldDefinition f : entityDef.fields.values()) {
            if ("status".equalsIgnoreCase(f.name()) || "state".equalsIgnoreCase(f.name())) {
                return f.name();
            }
        }
        return null;
    }

    private void validateSemanticConstraints(EntityDefinition entityDef, Map<String, Object> attributes) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            FieldDefinition field = entityDef.fields.get(entry.getKey());
            if (field == null || entry.getValue() == null) continue;

            Object val = entry.getValue();
            switch (field.kind()) {
                case SemanticKind.Code code -> {
                    String strVal = String.valueOf(val);
                    if (!code.allowedValues().isEmpty() && !code.allowedValues().contains(strVal)) {
                        throw new IllegalArgumentException(String.format(
                            "Value [%s] on field [%s] of %s not in allowed Code dictionary %s",
                            strVal, field.name(), entityDef.name, code.allowedValues()
                        ));
                    }
                }
                case SemanticKind.SpatialH3 ignored -> {
                    if (!(val instanceof Number)) {
                        throw new IllegalArgumentException("Field [" + field.name() + "] on " + entityDef.name + " expects numeric H3 index");
                    }
                }
                case SemanticKind.Monetary ignored -> {
                    if (!(val instanceof Number)) {
                        throw new IllegalArgumentException("Field [" + field.name() + "] on " + entityDef.name + " requires numeric value");
                    }
                }
                default -> {}
            }
        }
    }

    private EntityInstance hydrate(EntityDefinition entityDef, Map<String, Object> raw) {
        String pkCol = resolvePhysicalColumn(entityDef, entityDef.primaryKey);
        Object id = raw.get(pkCol);

        String versionCol = resolveVersionColumn(entityDef);
        long version = ((Number) raw.getOrDefault(versionCol, 1L)).longValue();

        String stateField = findStateFieldName(entityDef);
        String state = null;
        if (stateField != null) {
            String stateCol = resolvePhysicalColumn(entityDef, stateField);
            state = (String) raw.get(stateCol);
        }

        Map<String, Object> attributes = new HashMap<>();
        for (FieldDefinition field : entityDef.fields.values()) {
            if (raw.containsKey(field.physicalColumn())) {
                attributes.put(field.name(), raw.get(field.physicalColumn()));
            }
        }

        return new EntityInstance(id, entityDef.name, version, state, attributes);
    }

    public enum EntityAction {
        INSERT,
        UPDATE,
        DELETE;

        public static EntityAction from(String text) {
            return switch (text.trim().toUpperCase()) {
                case "INSERT", "CREATE", "ADD" -> INSERT;
                case "UPDATE", "MODIFY", "SAVE" -> UPDATE;
                case "DELETE", "REMOVE" -> DELETE;
                default -> throw new IllegalArgumentException("Unsupported action: " + text);
            };
        }
    }
}
```

### 5. 高级查询与编译器 (Advanced Query)

Java

```
// File: ./src/main/java/com/jabiz/query/QueryCompiler.java
package com.jabiz.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;

import java.util.*;

public class QueryCompiler {

    public PhysicalQueryPlan compileSql(
        DatasetDefinition dataset,
        EntityDefinition entityDef,
        EntityQuery query
    ) {
        String physicalTable = Optional.ofNullable(dataset.storage().physicalTableOverride())
            .filter(t -> !t.isBlank())
            .orElse(entityDef.physicalTable);

        int safeLimit = Math.min(
            query.limit(),
            dataset.policy().maxQueryBatchSize() > 0 ? dataset.policy().maxQueryBatchSize() : 100
        );

        Map<String, Object> params = new HashMap<>();
        StringBuilder sqlBuilder = new StringBuilder();

        List<String> fragments = new ArrayList<>();
        if (dataset.defaultPartitionFilter() != null && !dataset.defaultPartitionFilter().isEmpty()) {
            for (Map.Entry<String, Object> entry : dataset.defaultPartitionFilter().entrySet()) {
                String paramKey = "p_part_" + entry.getKey();
                fragments.add(entry.getKey() + " = :" + paramKey);
                params.put(paramKey, entry.getValue());
            }
        }

        if (query.predicate() != null) {
            String compiledPredicate = compilePredicate(query.predicate(), entityDef, params, 0);
            if (!compiledPredicate.isBlank()) {
                fragments.add(compiledPredicate);
            }
        }

        if (!fragments.isEmpty()) {
            sqlBuilder.append("WHERE ").append(String.join(" AND ", fragments));
        }

        List<PhysicalQueryPlan.PhysicalSort> physicalSorts = new ArrayList<>();
        for (SortOrder sort : query.sorts()) {
            FieldDefinition fd = entityDef.fields.get(sort.field());
            if (fd == null) {
                throw new IllegalArgumentException("Unknown sort field: " + sort.field());
            }
            physicalSorts.add(new PhysicalQueryPlan.PhysicalSort(fd.physicalColumn(), sort.ascending()));
        }

        return new PhysicalQueryPlan(
            physicalTable,
            sqlBuilder.toString(),
            params,
            physicalSorts,
            query.offset(),
            safeLimit,
            dataset.policy().queryTimeout()
        );
    }

    private String compilePredicate(
        QueryPredicate pred,
        EntityDefinition entityDef,
        Map<String, Object> params,
        int depth
    ) {
        return switch (pred) {
            case QueryPredicate.Eq eq -> buildComparison("=", eq.field(), eq.value(), entityDef, params);
            case QueryPredicate.Ne ne -> buildComparison("<>", ne.field(), ne.value(), entityDef, params);
            case QueryPredicate.Gt gt -> buildComparison(">", gt.field(), gt.value(), entityDef, params);
            case QueryPredicate.Gte gte -> buildComparison(">=", gte.field(), gte.value(), entityDef, params);
            case QueryPredicate.Lt lt -> buildComparison("<", lt.field(), lt.value(), entityDef, params);
            case QueryPredicate.Lte lte -> buildComparison("<=", lte.field(), lte.value(), entityDef, params);
            case QueryPredicate.In in -> {
                FieldDefinition fd = resolveField(entityDef, in.field());
                String paramName = "p_in_" + fd.physicalColumn() + "_" + depth;
                params.put(paramName, in.values());
                yield fd.physicalColumn() + " IN (:" + paramName + ")";
            }
            case QueryPredicate.And and -> {
                List<String> children = and.predicates().stream()
                    .map(p -> compilePredicate(p, entityDef, params, depth + 1))
                    .filter(s -> !s.isBlank())
                    .toList();
                yield children.isEmpty() ? "" : "(" + String.join(" AND ", children) + ")";
            }
            case QueryPredicate.Or or -> {
                List<String> children = or.predicates().stream()
                    .map(p -> compilePredicate(p, entityDef, params, depth + 1))
                    .filter(s -> !s.isBlank())
                    .toList();
                yield children.isEmpty() ? "" : "(" + String.join(" OR ", children) + ")";
            }
        };
    }

    private String buildComparison(
        String op,
        String field,
        Object value,
        EntityDefinition entityDef,
        Map<String, Object> params
    ) {
        FieldDefinition fd = resolveField(entityDef, field);

        if (op.contains(">") || op.contains("<")) {
            if (fd.kind() instanceof SemanticKind.Code || fd.kind() instanceof SemanticKind.SpatialH3) {
                throw new IllegalArgumentException(
                    "Range comparison [" + op + "] is invalid on field [" + field + "] with semantic " + fd.kind()
                );
            }
        }

        String paramKey = "p_" + fd.physicalColumn() + "_" + params.size();
        params.put(paramKey, value);
        return fd.physicalColumn() + " " + op + " :" + paramKey;
    }

    private FieldDefinition resolveField(EntityDefinition entityDef, String fieldName) {
        FieldDefinition fd = entityDef.fields.get(fieldName);
        if (fd == null) {
            throw new IllegalArgumentException("Field [" + fieldName + "] does not exist on entity [" + entityDef.name + "]");
        }
        return fd;
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/custom/SemanticValue.java
package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

public record SemanticValue(Object value, SemanticKind kind) {
    @SuppressWarnings("unchecked")
    public <T> T as(Class<T> clazz) {
        return clazz.cast(value);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/custom/SemanticRow.java
package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SemanticRow {
    private final Map<String, SemanticValue> columns = new HashMap<>();

    public void put(String fieldName, Object rawValue, SemanticKind kind) {
        columns.put(fieldName, new SemanticValue(rawValue, kind));
    }

    public SemanticValue get(String fieldName) {
        return columns.get(fieldName);
    }

    public Object getRaw(String fieldName) {
        SemanticValue sv = columns.get(fieldName);
        return sv != null ? sv.value() : null;
    }

    public Map<String, SemanticValue> getAllColumns() {
        return Collections.unmodifiableMap(columns);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/custom/ProjectedField.java
package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

public record ProjectedField(
    String name,
    SemanticKind kind,
    String sourceEntity,
    String sourceField
) {
    public static ProjectedField of(String name, SemanticKind kind) {
        return new ProjectedField(name, kind, null, null);
    }

    public static ProjectedField from(String name, SemanticKind kind, String entity, String field) {
        return new ProjectedField(name, kind, entity, field);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/custom/QueryParameter.java
package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

public record QueryParameter(
    String name,
    SemanticKind kind,
    boolean required,
    Object defaultValue,
    String description
) {
    public static QueryParameter of(String name, SemanticKind kind, boolean required) {
        return new QueryParameter(name, kind, required, null, "");
    }

    public static QueryParameter of(String name, SemanticKind kind, Object defaultValue) {
        return new QueryParameter(name, kind, false, defaultValue, "");
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/custom/AdvancedQueryDefinition.java
package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public record AdvancedQueryDefinition(
    String queryId,
    String description,
    List<String> participatingEntities,
    List<QueryParameter> parameters,
    List<ProjectedField> resultFields,
    String sqlTemplate,
    Duration timeoutOverride
) {
    public static AdvancedQueryDefinition define(String queryId, Consumer<Builder> consumer) {
        Builder builder = new Builder(queryId);
        consumer.accept(builder);
        return builder.build();
    }

    public static class Builder {
        private final String queryId;
        private String description = "";
        private final List<String> entities = new ArrayList<>();
        private final List<QueryParameter> parameters = new ArrayList<>();
        private final List<ProjectedField> resultFields = new ArrayList<>();
        private String sqlTemplate;
        private Duration timeout = Duration.ofSeconds(10);

        public Builder(String queryId) {
            this.queryId = queryId;
        }

        public Builder description(String d) {
            this.description = d;
            return this;
        }

        public Builder fromEntities(String... entityNames) {
            this.entities.addAll(List.of(entityNames));
            return this;
        }

        public Builder parameter(String name, SemanticKind kind, boolean required) {
            this.parameters.add(QueryParameter.of(name, kind, required));
            return this;
        }

        public Builder parameter(String name, SemanticKind kind, Object defaultValue) {
            this.parameters.add(QueryParameter.of(name, kind, defaultValue));
            return this;
        }

        public Builder returns(String name, SemanticKind kind) {
            this.resultFields.add(ProjectedField.of(name, kind));
            return this;
        }

        public Builder returns(String name, SemanticKind kind, String sourceEntity, String sourceField) {
            this.resultFields.add(ProjectedField.from(name, kind, sourceEntity, sourceField));
            return this;
        }

        public Builder sqlTemplate(String sql) {
            this.sqlTemplate = sql;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public AdvancedQueryDefinition build() {
            return new AdvancedQueryDefinition(
                queryId,
                description,
                List.copyOf(entities),
                List.copyOf(parameters),
                List.copyOf(resultFields),
                sqlTemplate,
                timeout
            );
        }
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/custom/LogisticsAnalyticsQueries.java
package com.jabiz.query.custom;

import com.jabiz.entity.DimensionType;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;

import java.util.List;

public final class LogisticsAnalyticsQueries {

    public static final AdvancedQueryDefinition TOKYO_PORT_WAYBILL_CUSTOMS_AUDIT =
        AdvancedQueryDefinition.define("query:logistics:tokyo_port_audit", qb -> {
            qb.description("Audit Tokyo port waybill declarations, weights, and spatial cells");

            qb.fromEntities("WaybillTracking", "CustomsDeclaration");

            qb.parameter("minFreight", new SemanticKind.Monetary("JPY", 0), true);
            qb.parameter("allowedStatuses", new SemanticKind.Code("urn:ubos:dict:waybill_status", List.of("CUSTOMS_CLEARED", "DELIVERED")), true);
            qb.parameter("shippedAfter", new SemanticKind.Temporal(TemporalRole.EVENT_TIME), false);

            qb.returns("waybillSn", new SemanticKind.SemanticIdentity("urn:ubos:entity:logistics:waybill"), "WaybillTracking", "waybillId");
            qb.returns("finalFreight", new SemanticKind.Monetary("JPY", 0), "WaybillTracking", "freightCharge");
            qb.returns("clearedWeightKg", new SemanticKind.PhysicalQuantity(DimensionType.MASS, "urn:unit:si:kilogram"), "WaybillTracking", "totalWeight");
            qb.returns("customsPortCell", new SemanticKind.SpatialH3(8), "WaybillTracking", "currentLocation");
            qb.returns("declarationNo", new SemanticKind.SemanticIdentity("urn:ubos:entity:customs:declaration"), "CustomsDeclaration", "declarationId");
            qb.returns("dutyPaid", new SemanticKind.Monetary("JPY", 0), "CustomsDeclaration", "dutyAmount");

            qb.sqlTemplate("""
                SELECT 
                    w.f_wb_sn            AS waybillSn,
                    w.f_charge_amt       AS finalFreight,
                    w.f_gross_wt         AS clearedWeightKg,
                    w.f_current_h3_cell  AS customsPortCell,
                    c.f_decl_no          AS declarationNo,
                    c.f_duty_amt         AS dutyPaid
                FROM {{WaybillTracking}} w
                INNER JOIN {{CustomsDeclaration}} c 
                    ON w.f_wb_sn = c.f_wb_ref_sn
                WHERE w.f_charge_amt >= :minFreight
                  AND w.f_status_code IN (:allowedStatuses)
                  AND (:shippedAfter IS NULL OR w.f_shipped_timestamp >= :shippedAfter)
                ORDER BY w.f_shipped_timestamp DESC
            """);
        });

    private LogisticsAnalyticsQueries() {}
}
```

Java

```
// File: ./src/main/java/com/jabiz/query/custom/AdvancedQueryExecutor.java
package com.jabiz.query.custom;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.storage.StorageAdapterRegistry;
import com.jabiz.storage.StorageEngine;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.regex.Pattern;

public class AdvancedQueryExecutor {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private final StorageAdapterRegistry storageRegistry;

    public AdvancedQueryExecutor(StorageAdapterRegistry storageRegistry) {
        this.storageRegistry = Objects.requireNonNull(storageRegistry);
    }

    public Flux<SemanticRow> execute(
        DatasetDefinition dataset,
        AdvancedQueryDefinition queryDef,
        Map<String, EntityDefinition> entityDefinitions,
        Map<String, Object> inputParams
    ) {
        return Flux.defer(() -> {
            Map<String, Object> validatedParams = validateAndBuildParams(queryDef, inputParams);
            String compiledSql = resolvePhysicalTables(queryDef.sqlTemplate(), dataset, entityDefinitions);

            String connectionPool = Optional.ofNullable(dataset.storage().readReplicaRef())
                .filter(ref -> !ref.isBlank())
                .orElseGet(() -> dataset.storage().connectionPoolRef());

            StorageEngine engine = storageRegistry.getEngine(connectionPool);

            PhysicalQueryPlan plan = new PhysicalQueryPlan(
                null,
                compiledSql,
                validatedParams,
                List.of(),
                0,
                dataset.policy().maxQueryBatchSize(),
                queryDef.timeoutOverride() != null ? queryDef.timeoutOverride() : dataset.policy().queryTimeout()
            );

            return engine.executeQuery(plan).map(row -> mapToSemanticRow(queryDef, row));
        });
    }

    private Map<String, Object> validateAndBuildParams(AdvancedQueryDefinition queryDef, Map<String, Object> inputs) {
        Map<String, Object> result = new HashMap<>();

        for (QueryParameter paramSpec : queryDef.parameters()) {
            String name = paramSpec.name();
            Object value = inputs != null ? inputs.get(name) : null;

            if (value == null) {
                if (paramSpec.required()) {
                    throw new IllegalArgumentException("Missing required query parameter: " + name);
                }
                value = paramSpec.defaultValue();
            }

            if (value != null) {
                validateParamSemantic(paramSpec.name(), paramSpec.kind(), value);
            }

            result.put(name, value);
        }

        return result;
    }

    private void validateParamSemantic(String paramName, SemanticKind kind, Object val) {
        switch (kind) {
            case SemanticKind.Code code -> {
                if (val instanceof Collection<?> coll) {
                    for (Object item : coll) {
                        checkCodeValue(paramName, code, String.valueOf(item));
                    }
                } else {
                    checkCodeValue(paramName, code, String.valueOf(val));
                }
            }
            case SemanticKind.Monetary ignored -> {
                if (!(val instanceof Number)) {
                    throw new IllegalArgumentException("Parameter [" + paramName + "] must be numeric currency");
                }
            }
            case SemanticKind.SpatialH3 ignored -> {
                if (!(val instanceof Number)) {
                    throw new IllegalArgumentException("Parameter [" + paramName + "] must be an H3 cell index (long)");
                }
            }
            default -> {}
        }
    }

    private void checkCodeValue(String paramName, SemanticKind.Code code, String strVal) {
        if (!code.allowedValues().isEmpty() && !code.allowedValues().contains(strVal)) {
            throw new IllegalArgumentException(String.format(
                "Parameter [%s] value [%s] violates Code dictionary: %s",
                paramName, strVal, code.allowedValues()
            ));
        }
    }

    private String resolvePhysicalTables(
        String sqlTemplate,
        DatasetDefinition dataset,
        Map<String, EntityDefinition> entityDefs
    ) {
        String resolvedSql = sqlTemplate;
        for (Map.Entry<String, EntityDefinition> entry : entityDefs.entrySet()) {
            String entityName = entry.getKey();
            EntityDefinition def = entry.getValue();

            String actualTable;
            if (Objects.equals(dataset.targetEntityType(), entityName) &&
                dataset.storage().physicalTableOverride() != null &&
                !dataset.storage().physicalTableOverride().isBlank()) {
                actualTable = dataset.storage().physicalTableOverride();
            } else {
                actualTable = def.physicalTable;
            }

            if (!IDENTIFIER_PATTERN.matcher(actualTable).matches()) {
                throw new SecurityException("Physical table contains invalid characters: " + actualTable);
            }

            resolvedSql = resolvedSql.replace("{{" + entityName + "}}", actualTable);
        }
        return resolvedSql;
    }

    private SemanticRow mapToSemanticRow(AdvancedQueryDefinition queryDef, Map<String, Object> rawRow) {
        SemanticRow semanticRow = new SemanticRow();
        for (ProjectedField field : queryDef.resultFields()) {
            Object rawValue = rawRow.get(field.name());
            semanticRow.put(field.name(), rawValue, field.kind());
        }
        return semanticRow;
    }
}
```

### 6. 统一资源解析体系 (Resource Resolvers)

Java

```
// File: ./src/main/java/com/jabiz/resource/ResourceNotFoundException.java
package com.jabiz.resource;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/ResourceResolutionException.java
package com.jabiz.resource;

public class ResourceResolutionException extends RuntimeException {
    public ResourceResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/ResourceId.java
package com.jabiz.resource;

import java.util.regex.Pattern;

public record ResourceId(String namespace, String kind, String type, String id) {

    private static final Pattern URN_PATTERN =
        Pattern.compile("^urn:([a-z0-9-]+):([a-z0-9-]+):([a-z0-9.:_-]+):([a-zA-Z0-9._-]+)$");

    public static ResourceId parse(String urn) {
        var m = URN_PATTERN.matcher(urn);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid resource URN: " + urn);
        }
        return new ResourceId(m.group(1), m.group(2), m.group(3), m.group(4));
    }

    @Override
    public String toString() {
        return "urn:%s:%s:%s:%s".formatted(namespace, kind, type, id);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/Resource.java
package com.jabiz.resource;

public interface Resource {
    ResourceId resourceId();
}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/GenericResource.java
package com.jabiz.resource;

import java.util.Map;

public record GenericResource(ResourceId resourceId, Map<String, Object> data) implements Resource {}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/UserDto.java
package com.jabiz.resource;

public record UserDto(String id, String name, String email) {}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/ResourceResolver.java
package com.jabiz.resource;

import reactor.core.publisher.Mono;

public interface ResourceResolver<T extends Resource> {
    String kind();
    Mono<T> resolve(ResourceId id);
}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/EntityResourceResolver.java
package com.jabiz.resource;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityDefinitionRegistry;
import com.jabiz.entity.GenericRowMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EntityResourceResolver implements ResourceResolver<Resource> {

    private final DatabaseClient db;
    private final EntityDefinitionRegistry entityRegistry;

    public EntityResourceResolver(DatabaseClient db, EntityDefinitionRegistry entityRegistry) {
        this.db = db;
        this.entityRegistry = entityRegistry;
    }

    @Override
    public String kind() {
        return "entity";
    }

    @Override
    public Mono<Resource> resolve(ResourceId id) {
        EntityDefinition def;
        try {
            def = entityRegistry.getOrThrow(id.type());
        } catch (IllegalArgumentException e) {
            return Mono.error(new ResourceNotFoundException(
                "Unregistered entity type: " + id.type() + " (" + id + ")"
            ));
        }

        String primaryKeyColumn = resolvePhysicalColumn(def, def.primaryKey);
        String sql = String.format("SELECT * FROM \"%s\" WHERE \"%s\" = :id",
            def.physicalTable.replace("\"", "\"\""),
            primaryKeyColumn.replace("\"", "\"\"")
        );

        return db.sql(sql)
            .bind("id", id.id())
            .map((row, meta) -> GenericRowMapper.toMap(def, row))
            .one()
            .map(dataMap -> (Resource) new GenericResource(id, dataMap))
            .switchIfEmpty(Mono.error(new ResourceNotFoundException("Resource not found: " + id)));
    }

    private String resolvePhysicalColumn(EntityDefinition def, String logicalPrimaryKeyField) {
        var field = def.fields.get(logicalPrimaryKeyField);
        if (field == null) {
            throw new IllegalStateException(
                "Entity " + def.name + " declared primary key " + logicalPrimaryKeyField + " which is not present in fields"
            );
        }
        return field.physicalColumn();
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/UserResourceResolver.java
package com.jabiz.resource;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class UserResourceResolver implements ResourceResolver<Resource> {

    private final WebClient userServiceClient;

    public UserResourceResolver(WebClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    @Override
    public String kind() {
        return "user";
    }

    @Override
    public Mono<Resource> resolve(ResourceId id) {
        return userServiceClient.get()
            .uri("/users/{id}", id.id())
            .retrieve()
            .bodyToMono(UserDto.class)
            .timeout(Duration.ofSeconds(5))
            .map(dto -> (Resource) new GenericResource(
                id,
                Map.of(
                    "userId", dto.id(),
                    "name", dto.name(),
                    "email", dto.email()
                )
            ))
            .onErrorResume(WebClientResponseException.NotFound.class,
                e -> Mono.error(new ResourceNotFoundException("User not found: " + id)))
            .onErrorResume(Exception.class, e -> Mono.error(
                new ResourceResolutionException("Remote call to user service failed for " + id, e)
            ));
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/ResourceRegistry.java
package com.jabiz.resource;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResourceRegistry {

    private final Map<String, ResourceResolver<?>> resolvers = new ConcurrentHashMap<>();

    public ResourceRegistry(List<ResourceResolver<?>> allResolvers) {
        allResolvers.forEach(r -> resolvers.put(r.kind(), r));
    }

    public Mono<? extends Resource> resolve(String urn) {
        ResourceId id = ResourceId.parse(urn);
        ResourceResolver<?> resolver = resolvers.get(id.kind());
        if (resolver == null) {
            return Mono.error(new IllegalArgumentException("Unregistered resource kind: " + id.kind()));
        }
        return resolver.resolve(id);
    }
}
```

### 7. 流程引擎调度与执行 (Process Engine & Sponsor Process)

Java

```
// File: ./src/main/java/com/jabiz/process/StepDefinition.java
package com.jabiz.process;

public record StepDefinition(
    String stepName,
    Class<? extends StepHandler<?, ?>> handlerClass,
    Object metadata
) {}
```

Java

```
// File: ./src/main/java/com/jabiz/process/ProcessContext.java
package com.jabiz.process;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ProcessContext {

    private final long processSeqId;
    private final Map<String, Object> attributes = new HashMap<>();

    public ProcessContext(long processSeqId) {
        this.processSeqId = processSeqId;
    }

    public long processSeqId() {
        return processSeqId;
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    public Object get(String key) {
        return attributes.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = attributes.get(key);
        if (v == null) return null;
        if (!type.isInstance(v)) {
            throw new IllegalStateException("Context key " + key + " type " + v.getClass() + " does not match expected " + type);
        }
        return (T) v;
    }

    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(attributes));
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/StepHandler.java
package com.jabiz.process;

import reactor.core.publisher.Mono;

public interface StepHandler<M, CTX extends ProcessContext> {
    Mono<Void> execute(M metadata, CTX ctx);
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/ProcessDefinition.java
package com.jabiz.process;

import java.util.List;
import java.util.function.Consumer;

public record ProcessDefinition(
    String name,
    int version,
    String description,
    Class<?> inputType,
    Class<?> outputType,
    List<StepDefinition> steps
) {
    public static ProcessDefinition define(String name, int version, Consumer<ProcessBuilder> block) {
        ProcessBuilder builder = new ProcessBuilder(name, version);
        block.accept(builder);
        return builder.build();
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/ProcessBuilder.java
package com.jabiz.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProcessBuilder {

    private final String name;
    private final int version;
    private String description;
    private Class<?> inputType;
    private Class<?> outputType;
    private final List<StepDefinition> steps = new ArrayList<>();

    ProcessBuilder(String name, int version) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.version = version;
    }

    public ProcessBuilder description(String description) {
        this.description = description;
        return this;
    }

    public ProcessBuilder inputType(Class<?> inputType) {
        this.inputType = inputType;
        return this;
    }

    public ProcessBuilder outputType(Class<?> outputType) {
        this.outputType = outputType;
        return this;
    }

    public ProcessBuilder step(String stepName, Class<? extends StepHandler<?, ?>> handlerClass, Object metadata) {
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(handlerClass, "handlerClass must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        steps.add(new StepDefinition(stepName, handlerClass, metadata));
        return this;
    }

    ProcessDefinition build() {
        if (steps.isEmpty()) {
            throw new IllegalStateException("Process " + name + " must define at least one step");
        }
        return new ProcessDefinition(name, version, description, inputType, outputType, List.copyOf(steps));
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/ProcessRegistry.java
package com.jabiz.process;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class ProcessRegistry {

    private final Map<String, ProcessDefinition> registry = new ConcurrentHashMap<>();

    public void register(ProcessDefinition def) {
        registry.put(def.name(), def);
    }

    public ProcessDefinition get(String name) {
        ProcessDefinition def = registry.get(name);
        if (def == null) {
            throw new IllegalArgumentException("Unregistered process: " + name);
        }
        return def;
    }

    public Collection<ProcessDefinition> all() {
        return registry.values();
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/ProcessExecutor.java
package com.jabiz.process;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ProcessExecutor {

    private final ApplicationContext applicationContext;

    public ProcessExecutor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @SuppressWarnings("unchecked")
    public <CTX extends ProcessContext> Mono<CTX> run(ProcessDefinition definition, CTX context) {
        return Flux.fromIterable(definition.steps())
            .concatMap(step -> {
                StepHandler<Object, CTX> handler = (StepHandler<Object, CTX>) applicationContext.getBean(step.handlerClass());
                return handler.execute(step.metadata(), context);
            })
            .then(Mono.just(context));
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/ProcessResource.java
package com.jabiz.resource;

import com.jabiz.process.ProcessDefinition;

public record ProcessResource(ResourceId resourceId, ProcessDefinition definition) implements Resource {}
```

Java

```
// File: ./src/main/java/com/jabiz/resource/ProcessResourceResolver.java
package com.jabiz.resource;

import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ProcessResourceResolver implements ResourceResolver<Resource> {

    private final ProcessRegistry processRegistry;

    public ProcessResourceResolver(ProcessRegistry processRegistry) {
        this.processRegistry = processRegistry;
    }

    @Override
    public String kind() {
        return "process";
    }

    @Override
    public Mono<Resource> resolve(ResourceId id) {
        try {
            ProcessDefinition def = processRegistry.get(id.type());
            return Mono.just(new ProcessResource(id, def));
        } catch (IllegalArgumentException e) {
            return Mono.error(new ResourceNotFoundException("Process not registered: " + id.type() + " (" + id + ")"));
        }
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/AuthenticatedUser.java
package com.jabiz.process.sponsor;

public record AuthenticatedUser(String userId, int failedLoginAttempts) {}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/LoginContext.java
package com.jabiz.process.sponsor;

import com.jabiz.process.ProcessContext;

public class LoginContext extends ProcessContext {

    private static final String KEY_AUTHENTICATED_USER = "authenticated_user";
    private static final String KEY_LOGIN_RECORD_ID = "login_record_id";

    public LoginContext(long processSeqId) {
        super(processSeqId);
    }

    public void setAuthenticatedUser(AuthenticatedUser user) {
        put(KEY_AUTHENTICATED_USER, user);
    }

    public AuthenticatedUser getAuthenticatedUser() {
        AuthenticatedUser user = get(KEY_AUTHENTICATED_USER, AuthenticatedUser.class);
        if (user == null) {
            throw new IllegalStateException("Authentication step uncompleted: authenticated_user not found");
        }
        return user;
    }

    public void setLoginRecordId(String loginRecordId) {
        put(KEY_LOGIN_RECORD_ID, loginRecordId);
    }

    public String getLoginRecordId() {
        return get(KEY_LOGIN_RECORD_ID, String.class);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/AuthenticationMetadata.java
package com.jabiz.process.sponsor;

public record AuthenticationMetadata(
    String inputKeyUsername,
    String inputKeyPassword,
    String userEntityType,
    String outputUserEntityKey
) {}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/RoleAccessMetadata.java
package com.jabiz.process.sponsor;

public record RoleAccessMetadata(
    String targetUserEntityKey,
    String requiredRoleSlug,
    String roleRelationshipType
) {}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/LoginRecordMetadata.java
package com.jabiz.process.sponsor;

public record LoginRecordMetadata(
    String targetUserEntityKey,
    String loginRecordEntityType,
    String usedRoleSlug,
    String commitType
) {}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/SponsorSignInInput.java
package com.jabiz.process.sponsor;

public record SponsorSignInInput(String usernameOrEmail, String password) {}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/SponsorSignInOutput.java
package com.jabiz.process.sponsor;

public record SponsorSignInOutput(String userId, String loginRecordId) {}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/AuthenticationHandler.java
package com.jabiz.process.sponsor;

import com.jabiz.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("sponsorAuthenticationHandler")
public class AuthenticationHandler implements StepHandler<AuthenticationMetadata, LoginContext> {

    @Override
    public Mono<Void> execute(AuthenticationMetadata meta, LoginContext ctx) {
        return Mono.fromRunnable(() -> {
            String username = (String) ctx.get(meta.inputKeyUsername());
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("Username credential must be present");
            }
            // User credential validation simulation
            ctx.setAuthenticatedUser(new AuthenticatedUser(username, 0));
        });
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/RoleAccessHandler.java
package com.jabiz.process.sponsor;

import com.jabiz.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RoleAccessHandler implements StepHandler<RoleAccessMetadata, LoginContext> {

    @Override
    public Mono<Void> execute(RoleAccessMetadata meta, LoginContext ctx) {
        return Mono.fromRunnable(() -> {
            AuthenticatedUser user = ctx.getAuthenticatedUser();
            if (user == null) {
                throw new SecurityException("User context absent during role authorization");
            }
        });
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/LoginRecordCreationHandler.java
package com.jabiz.process.sponsor;

import com.jabiz.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class LoginRecordCreationHandler implements StepHandler<LoginRecordMetadata, LoginContext> {

    @Override
    public Mono<Void> execute(LoginRecordMetadata meta, LoginContext ctx) {
        return Mono.fromRunnable(() -> {
            AuthenticatedUser user = ctx.getAuthenticatedUser();
            if (user == null) {
                throw new IllegalStateException("Cannot persist login record without authenticated user");
            }
            ctx.setLoginRecordId(UUID.randomUUID().toString());
        });
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/sponsor/SponsorSignInProcess.java
package com.jabiz.process.sponsor;

import com.jabiz.process.ProcessDefinition;

public final class SponsorSignInProcess {

    public static final ProcessDefinition DEFINITION =
        ProcessDefinition.define("SPONSOR_SIGN_IN", 1, pb -> pb
            .description("Sponsor authentication, role validation, and audit trail record generation")
            .inputType(SponsorSignInInput.class)
            .outputType(SponsorSignInOutput.class)
            .step("Authentication & User Load", AuthenticationHandler.class,
                new AuthenticationMetadata("usernameOrEmail", "password", "USER_V1", "authenticated_user"))
            .step("Role and Access Check", RoleAccessHandler.class,
                new RoleAccessMetadata("authenticated_user", "SPONSOR_ROLE_V1", "HAS_ROLE"))
            .step("Create Login Record & Setup Context", LoginRecordCreationHandler.class,
                new LoginRecordMetadata("authenticated_user", "LOGIN_RECORD_V1", "SPONSOR_ROLE_V1", "CREATE"))
        );

    private SponsorSignInProcess() {}
}
```

Java

```
// File: ./src/main/java/com/jabiz/process/ProcessDefinitionsConfig.java
package com.jabiz.process;

import com.jabiz.process.sponsor.SponsorSignInProcess;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessDefinitionsConfig {

    private final ProcessRegistry registry;

    public ProcessDefinitionsConfig(ProcessRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void registerDefaults() {
        registry.register(SponsorSignInProcess.DEFINITION);
    }
}
```

### 8. Web 入口与 SPA 路由适配 (Web Application)

Java

```
// File: ./src/main/java/com/jabiz/app/CreateTodo.java
package com.jabiz.app;

import jakarta.validation.constraints.NotBlank;

public record CreateTodo(@NotBlank String title) {}
```

Java

```
// File: ./src/main/java/com/jabiz/app/Todo.java
package com.jabiz.app;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("todo")
public record Todo(@Id Long id, String title, boolean done) {
    public Todo withId(Long id) {
        return new Todo(id, title, done);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/app/TodoRepository.java
package com.jabiz.app;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface TodoRepository extends ReactiveCrudRepository<Todo, Long> {}
```

Java

```
// File: ./src/main/java/com/jabiz/app/TodoController.java
package com.jabiz.app;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoRepository repo;

    public TodoController(TodoRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public Flux<Todo> list() {
        return repo.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Todo> create(@Valid @RequestBody CreateTodo req) {
        return repo.save(new Todo(null, req.title(), false));
    }

    @PutMapping("/{id}/toggle")
    public Mono<Todo> toggle(@PathVariable long id) {
        return repo.findById(id)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap(t -> repo.save(new Todo(t.id(), t.title(), !t.done())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable long id) {
        return repo.deleteById(id);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/app/SpaFallbackFilter.java
package com.jabiz.app;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class SpaFallbackFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var req = exchange.getRequest();
        String path = req.getPath().value();
        boolean spaRoute = HttpMethod.GET.equals(req.getMethod())
            && !path.startsWith("/api")
            && !path.startsWith("/actuator")
            && !path.contains(".");
        if (spaRoute) {
            return chain.filter(exchange.mutate()
                .request(req.mutate().path("/index.html").build()).build());
        }
        return chain.filter(exchange);
    }
}
```

Java

```
// File: ./src/main/java/com/jabiz/app/App.java
package com.jabiz.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.jabiz")
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
```
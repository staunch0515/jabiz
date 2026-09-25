package com.jabiz.runtime.process.entity;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.process.NoMetadata;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Assigns the primary key of a new instance when the entity declares its identity field as
 * generated. Entities with business keys are left untouched, so a missing key still fails
 * validation as a required field.
 */
@Component
public class AssignIdentityHandler implements StepHandler<NoMetadata, EntityChangeContext> {

    private final EntityIdGenerator generator;

    public AssignIdentityHandler(EntityIdGenerator generator) {
        this.generator = generator;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, EntityChangeContext ctx) {
        return Mono.fromRunnable(() -> {
            EntityDefinition definition = ctx.definition();
            FieldDefinition identity = definition.field(definition.primaryKey);
            if (identity.generated()) {
                ctx.putAttribute(identity.name(), generator.next(definition));
            }
        });
    }
}

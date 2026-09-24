package com.jabiz.process.entity;

import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/** Publishes the generic entity processes as beans and provides the default identity generator. */
@Configuration
class EntityProcessConfig {

    @Bean
    ProcessDefinition<AddEntityInput, EntityInstance, EntityChangeContext> addEntityProcess() {
        return AddProcessDefinition.DEFINITION;
    }

    @Bean
    ProcessDefinition<UpdateEntityInput, EntityInstance, EntityChangeContext> updateEntityProcess() {
        return UpdateProcessDefinition.DEFINITION;
    }

    @Bean
    ProcessDefinition<DeleteEntityInput, DeleteEntityOutput, EntityChangeContext> deleteEntityProcess() {
        return DeleteProcessDefinition.DEFINITION;
    }

    @Bean
    @ConditionalOnMissingBean(EntityIdGenerator.class)
    EntityIdGenerator entityIdGenerator() {
        return definition -> UUID.randomUUID().toString();
    }
}

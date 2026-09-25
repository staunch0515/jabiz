package com.jabiz.runtime.web;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.JsonSchemaExporter;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes the entity metamodel so that clients can build forms and client-side validation from it
 * (docs/design/02-metamodel.md section 8).
 */
@RestController
@RequestMapping("/api/meta")
class MetaModelController {

    private final EntityDefinitionRegistry registry;

    MetaModelController(EntityDefinitionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/entities/{name}")
    Map<String, Object> getEntity(@PathVariable("name") String name) {
        return MetaModelExporter.export(find(name));
    }

    /** JSON Schema (draft 2020-12) of the attributes of one instance. */
    @GetMapping(value = "/schema/{name}", produces = {"application/schema+json", "application/json"})
    Map<String, Object> getSchema(@PathVariable("name") String name) {
        return JsonSchemaExporter.export(find(name));
    }

    private EntityDefinition find(String name) {
        return registry.find(name).orElseThrow(
            () -> new EntityNotFoundException("Unregistered entity type: " + name));
    }
}

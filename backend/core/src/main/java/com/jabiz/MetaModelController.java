package com.jabiz;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityDefinitionRegistry;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.runtime.EntityNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Exposes the entity metamodel so that clients can build forms and client-side validation from it. */
@RestController
@RequestMapping("/api/meta")
class MetaModelController {

    private final EntityDefinitionRegistry registry;

    MetaModelController(EntityDefinitionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/entities/{name}")
    Map<String, Object> getEntity(@PathVariable("name") String name) {
        EntityDefinition def = registry.find(name).orElseThrow(
            () -> new EntityNotFoundException("Unregistered entity type: " + name));
        return MetaModelExporter.export(def);
    }
}

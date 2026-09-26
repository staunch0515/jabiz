plugins {
    `java-library`
    jacoco
}

// jabiz-core: pure Java. No main dependencies at all, so Spring, Reactor and R2DBC cannot even be
// referenced here; ArchitectureTest in app states the same rules explicitly.
base {
    archivesName.set("jabiz-core")
}

// Phase 1 acceptance: the core classes below must keep at least 80% line coverage.
val coverageGatedClasses = listOf(
    "com.jabiz.entity.FieldValueCoercer",
    "com.jabiz.entity.EntityValidator",
    "com.jabiz.entity.EntityBuilder",
    "com.jabiz.query.QueryCompiler",
    "com.jabiz.resource.ResourceId",
    "com.jabiz.process.ProcessDefinitionBuilder",
    // Phase 2
    "com.jabiz.context.RequestContext",
    "com.jabiz.i18n.MessageCatalog",
    "com.jabiz.i18n.MessageTemplate",
    // Phase 3
    "com.jabiz.entity.SemanticKinds",
    "com.jabiz.entity.CustomKinds",
    "com.jabiz.entity.GuardDefinition",
    "com.jabiz.entity.ListViewDefinition",
    "com.jabiz.entity.MetaModelExporter",
    "com.jabiz.entity.JsonSchemaExporter",
    "com.jabiz.dataset.DatasetDefinition",
    "com.jabiz.dataset.DatasetScope",
    "com.jabiz.dictionary.StaticDictionary",
    // Phase 4
    "com.jabiz.temporal.Timeline",
    "com.jabiz.temporal.VersionPlanner",
    "com.jabiz.temporal.EntityVersion",
    "com.jabiz.entity.TemporalBuilder",
    "com.jabiz.entity.EntityDefinition",
    // Phase 5
    "com.jabiz.entity.SemanticKindParser",
    "com.jabiz.query.template.SqlText",
    "com.jabiz.query.template.SqlTemplateRenderer",
    "com.jabiz.query.template.SqlTemplateFile",
    "com.jabiz.query.template.TemplateChecks",
    "com.jabiz.query.template.OuterQueryCompiler",
    "com.jabiz.query.template.TemplateValues",
    "com.jabiz.query.template.SqlTypeCompatibility",
    "com.jabiz.query.custom.AdvancedQueryDefinition",
)

dependencies {
    // Property tests of the temporal invariants (docs/design/07-quality.md section 6).
    testImplementation("net.jqwik:jqwik:1.9.3")
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "CLASS"
            includes = coverageGatedClasses
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

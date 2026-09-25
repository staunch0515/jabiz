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
)

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

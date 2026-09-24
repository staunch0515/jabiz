plugins {
    id("java")
    jacoco
}

group = "com.jabiz"
version = "0.0.1-SNAPSHOT"

// Phase 1 acceptance: the core classes below must keep at least 80% line coverage.
val coverageGatedClasses = listOf(
    "com.jabiz.entity.FieldValueCoercer",
    "com.jabiz.entity.EntityValidator",
    "com.jabiz.entity.EntityBuilder",
    "com.jabiz.query.QueryCompiler",
    "com.jabiz.resource.ResourceId",
    "com.jabiz.process.ProcessDefinitionBuilder",
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

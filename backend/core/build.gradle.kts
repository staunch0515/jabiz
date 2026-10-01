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
    // Phase 6
    "com.jabiz.process.ProcessDefinition",
    "com.jabiz.process.ProcessContext",
    "com.jabiz.process.ChangeSet",
    "com.jabiz.process.ChangeSet\$Target",
    "com.jabiz.process.StepDefinition",
    "com.jabiz.process.RetryPolicy",
    // Phase 7
    "com.jabiz.security.LoginAttemptPolicy",
    "com.jabiz.security.LoginOutcome",
    // Phase 9
    "com.jabiz.ledger.LedgerPosting",
    "com.jabiz.ledger.LedgerBalances",
    "com.jabiz.ledger.PostingLine",
    "com.jabiz.ledger.Direction",
    "com.jabiz.ledger.LedgerDimension",
    "com.jabiz.ledger.ForeignAmount",
    "com.jabiz.event.EventSubscription",
    "com.jabiz.event.DomainEvent",
    "com.jabiz.job.JobDefinition",
    "com.jabiz.dataset.DatasetPolicy",
    // Phase 10
    "com.jabiz.entity.Rules",
    "com.jabiz.entity.RuleKinds",
    // Phase 13d
    "com.jabiz.entity.i18n.I18nText",
    "com.jabiz.entity.i18n.I18nTextSupport",
    "com.jabiz.process.ActsOn",
    // Phase 14b
    "com.jabiz.numbering.NumberFormat",
    "com.jabiz.numbering.NumberSequence",
    "com.jabiz.approval.ApprovalSubject",
    "com.jabiz.approval.ApprovalCondition*",
    "com.jabiz.approval.ConditionParser",
    "com.jabiz.approval.ApprovalLevel",
    "com.jabiz.approval.ApprovalEvaluation",
    "com.jabiz.approval.ContentHash",
    "com.jabiz.approval.FactType",
    "com.jabiz.security.SodRule",
    // Phase 14d
    "com.jabiz.query.custom.TemplateVersion",
    "com.jabiz.report.*",
    // Phase 14e
    "com.jabiz.imports.*",
    "com.jabiz.file.MediaTypeDetector",
    // Phase 14f
    "com.jabiz.audit.*",
    "com.jabiz.integrity.*",
    "com.jabiz.retention.*",
    "com.jabiz.export.*",
    // Phase 14j
    "com.jabiz.document.*",
    "com.jabiz.query.template.TemplateSchemas",
)

dependencies {
    // Property tests of the temporal invariants (docs/design/07-quality.md section 6).
    testImplementation("net.jqwik:jqwik:1.9.3")
    // Reads the validation cases shared with the frontend (spec/validation-cases.json, decision D15).
    testImplementation("tools.jackson.core:jackson-databind")
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
    // The validation cases shared with the frontend; -Dvalidation-cases.update=true rewrites their field metadata.
    val cases = rootProject.layout.projectDirectory.file("../spec/validation-cases.json").asFile
    inputs.file(cases)
    systemProperty("validation-cases.file", cases.absolutePath)
    systemProperty("validation-cases.update", providers.systemProperty("validation-cases.update").orElse("false").get())
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

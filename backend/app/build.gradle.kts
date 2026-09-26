dependencies {
    implementation(project(":runtime"))
    implementation(project(":ext-geo"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    // Records every SQL statement, to show that temporal writes never update or delete (ROADMAP phase 4).
    testImplementation("io.r2dbc:r2dbc-proxy")
    // A second application instance's cluster lock in the job tests (ROADMAP phase 9).
    testImplementation("net.javacrumbs.shedlock:shedlock-provider-r2dbc:7.10.1")
    // Random transaction sequences for the ledger's balance invariant against the database (ROADMAP phase 9).
    testImplementation("net.jqwik:jqwik:1.9.3")
}

// platformCheck (docs/design/07-quality.md section 2): the startup self-checks, run against a freshly migrated
// test database (JABIZ_TEST_DB_URL, else Testcontainers); prints one line per problem, fails on any error.
val platformCheckRuntime by configurations.creating
dependencies {
    platformCheckRuntime(testFixtures(project(":runtime")))
}

val platformCheck = tasks.register<JavaExec>("platformCheck") {
    group = "verification"
    description = "Runs the platform's static checks (metamodel, datasets, SQL templates, ...) against a test database."
    classpath = sourceSets["main"].runtimeClasspath + platformCheckRuntime
    mainClass.set("com.jabiz.runtime.test.PlatformCheckLauncher")
    args("com.jabiz.app.App")
    systemProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true")
    listOf("JABIZ_TEST_DB_URL", "JABIZ_TEST_DB_USER", "JABIZ_TEST_DB_PASSWORD").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    // Always run: the database, not only the sources, is checked.
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(platformCheck)
}

// Scenario replay (docs/design/07-quality.md section 3): snapshots live next to the scenarios in the source tree;
// -Dscenario.update-snapshots=true (given to Gradle) rewrites the ones that differ.
tasks.test {
    val updateSnapshots = providers.systemProperty("scenario.update-snapshots").orElse("false")
    systemProperty("scenario.resources-dir", layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
    systemProperty("scenario.update-snapshots", updateSnapshots.get())
    // The OpenAPI document the frontend generates its types from (docs/design/12-frontend.md section 3);
    // -Dopenapi.update-snapshot=true rewrites it when the API changed.
    val openApi = rootProject.layout.projectDirectory.file("../frontend/openapi/openapi.json").asFile
    systemProperty("openapi.snapshot", openApi.absolutePath)
    systemProperty("openapi.update-snapshot", providers.systemProperty("openapi.update-snapshot").orElse("false").get())
    if (updateSnapshots.get() == "true" || providers.systemProperty("openapi.update-snapshot").orNull == "true") {
        outputs.upToDateWhen { false }
    }
}

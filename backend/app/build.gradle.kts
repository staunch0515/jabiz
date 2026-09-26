dependencies {
    implementation(project(":runtime"))
    implementation(project(":ext-geo"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    // Records every SQL statement, to show that temporal writes never update or delete (ROADMAP phase 4).
    testImplementation("io.r2dbc:r2dbc-proxy")
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

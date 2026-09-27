// The demo application: runtime + business declarations, packaged together with the admin frontend
// (docs/design/17-apps-and-branches.md section 3.1).
plugins {
    id("jabiz.boot-app")
}

jabizApp {
    mainClass = "com.jabiz.app.App"
    spa("/", "../../frontend")
}

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
    // Timers of the platform's observations (docs/design/13-observability-ops.md).
    testImplementation("io.micrometer:micrometer-core")
}

// Load test (ROADMAP phase 11, docs/perf/phase-11-load-test.md): drives a running application over HTTP. Not part of
// check; run it against a started jar: LOAD_USER=admin LOAD_PASSWORD=... ./gradlew :app:loadTest
val loadTestSourceSet = sourceSets.create("loadTest")
dependencies {
    "loadTestImplementation"("org.hdrhistogram:HdrHistogram:2.2.2")
    "loadTestImplementation"("tools.jackson.core:jackson-databind")
}

tasks.register<JavaExec>("loadTest") {
    group = "verification"
    description = "Load test of a running application (LOAD_BASE_URL, LOAD_USER, LOAD_PASSWORD, LOAD_* sizes)."
    classpath = loadTestSourceSet.runtimeClasspath
    mainClass.set("com.jabiz.app.load.LoadTest")
    outputs.upToDateWhen { false }
}

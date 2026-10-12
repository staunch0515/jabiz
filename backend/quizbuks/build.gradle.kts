// QuizBuks (docs/quizbuks/02-design.md): the runtime with the QuizBuks declarations in one jar. For now it serves
// only the platform's admin frontend under /admin/; the answering app (/) and the sponsor console (/sponsor/) are
// added once the platform's application frontend library is in (docs/quizbuks/03-plan.md, phases Q2 and Q3).
plugins {
    id("jabiz.boot-app")
}

jabizApp {
    mainClass = "com.jabiz.quizbuks.QuizbuksApp"
    spa("/admin", "../../frontend")
    languages("en", "zh", "ja")
    // The snapshots stay in the application's directory: the platform's own (frontend/openapi) are not ours to write.
    openApiSnapshot = rootProject.layout.projectDirectory.file("quizbuks/src/test/resources/openapi.json")
    publicQueriesSnapshot = rootProject.layout.projectDirectory.file("quizbuks/src/test/resources/public-queries.json")
}

dependencies {
    implementation(project(":runtime"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("net.jqwik:jqwik:1.9.3")
    // Registration and mail against a local SMTP server (Q2), as the platform's own tests.
    testImplementation("com.icegreen:greenmail-junit5:2.1.3")
}

// The throughput baseline (docs/quizbuks/plans/Q5-answering.md, D-Q5-10) is tagged "perf": not part of check, run on
// its own with ./gradlew :quizbuks:perfTest.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("perf")
    }
}

// platformCheck starts the application with application.yml only: Stripe's webhook secrets, which come from the
// environment and fail the startup when missing, get throwaway values there, so that the inbound providers and their
// consumers are checked (the platform's launcher brings throwaway keys of its own, but none for inbound providers).
tasks.named<JavaExec>("platformCheck") {
    environment("STRIPE_WEBHOOK_SECRET", "whsec_platform_check_throwaway_00000000000")
    environment("STRIPE_CONNECT_WEBHOOK_SECRET", "whsec_platform_check_throwaway_connect_000")
}

tasks.register<Test>("perfTest") {
    description = "Runs the answering throughput baseline (tag perf)."
    group = "verification"
    val test = tasks.named<Test>("test").get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath
    systemProperties(test.systemProperties)
    jvmArgs(test.jvmArgs ?: emptyList<String>())
    useJUnitPlatform {
        includeTags("perf")
    }
    testLogging {
        showStandardStreams = true
    }
}

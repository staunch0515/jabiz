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
}

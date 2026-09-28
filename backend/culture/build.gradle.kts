// Culture, Unfiltered (docs/culture/00-design.md): the runtime with the application's declarations, the public site
// at / and the platform's admin frontend at /admin/ (docs/design/17-apps-and-branches.md section 3).
plugins {
    id("jabiz.boot-app")
}

jabizApp {
    mainClass = "com.jabiz.culture.CultureApp"
    spa("/", "../../site")
    spa("/admin", "../../frontend")
    // The public templates' catalog, from which the site generates its types (docs/culture/00-design.md section 7.2).
    publicQueriesSnapshot = rootProject.layout.projectDirectory.file("../site/src/api/public-queries.json")
}

// The plugin reads jabizApp.publicQueriesSnapshot when it configures the test task, which happens before the block
// above sets it; until the platform reads it lazily, the test task is told directly (after the plugin's own action).
tasks.named<Test>("test") {
    systemProperty("public-queries.snapshot", jabizApp.publicQueriesSnapshot.get().asFile.absolutePath)
}

dependencies {
    implementation(project(":runtime"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("net.jqwik:jqwik:1.9.3")
}

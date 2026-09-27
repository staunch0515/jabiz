// Culture, Unfiltered (docs/culture/00-design.md): the runtime with the application's declarations, the public site
// at / and the platform's admin frontend at /admin/ (docs/design/17-apps-and-branches.md section 3).
plugins {
    id("jabiz.boot-app")
}

jabizApp {
    mainClass = "com.jabiz.culture.CultureApp"
    spa("/", "../../site")
    spa("/admin", "../../frontend")
}

dependencies {
    implementation(project(":runtime"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
}

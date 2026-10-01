// The finance application (docs/finance/00-design.md): the runtime with the finance declarations. Until the
// application's own admin pages exist (platform phase 14a, finance-web/), it serves the platform's admin frontend.
plugins {
    id("jabiz.boot-app")
}

jabizApp {
    mainClass = "com.jabiz.finance.FinanceApp"
    spa("/", "../../frontend")
    // The books are kept in US English, shown as in the US (FD4, FIN-UI-010).
    languages("en")
    region = "en-US"
}

dependencies {
    implementation(project(":runtime"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("net.jqwik:jqwik:1.9.3")
}

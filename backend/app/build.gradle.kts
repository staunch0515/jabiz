dependencies {
    implementation(project(":runtime"))
    implementation(project(":ext-geo"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    // Records every SQL statement, to show that temporal writes never update or delete (ROADMAP phase 4).
    testImplementation("io.r2dbc:r2dbc-proxy")
}

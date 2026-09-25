dependencies {
    implementation(project(":runtime"))
    implementation(project(":ext-geo"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
}

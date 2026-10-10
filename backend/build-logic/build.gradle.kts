plugins {
    `kotlin-dsl`
}

// The plugins applied by the conventions. Their versions are declared here only: the backend build gets them onto
// its classpath through jabiz.boot-app and applies them by id without a version.
dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.0.1")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
    implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")

    // Functional tests: TestKit builds of fixture projects with the plugin under test on their classpath.
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("bootApp") {
            id = "jabiz.boot-app"
            implementationClass = "com.jabiz.gradle.BootAppPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

plugins {
    `java-library`
}

// QuizBuks for Shopify (docs/quizbuks/shopify/S1-install.md): the shops, their staff, the access tokens and the
// webhooks of the Shopify app. A library that the QuizBuks jar includes (:quizbuks), depending on the platform only:
// it never reaches the engine's classes, the wallets or the sponsors' tables; from S2 on it calls the engine's
// processes by name. Its integration tests run in :quizbuks, which starts the application.
base {
    archivesName.set("quizbuks-shopify")
}

dependencies {
    implementation(project(":runtime"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

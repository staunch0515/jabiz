plugins {
    `java-library`
}

// QuizBuks for Shopify (docs/quizbuks/shopify/S1-install.md): the shops, their staff, the access tokens and the
// webhooks of the Shopify app. A library that the QuizBuks jar includes (:quizbuks), depending on the platform only:
// it never reaches the engine's classes, the wallets or the sponsors' tables; from S2 on it calls the engine's channel
// processes with the records of the channel contract (:quizbuks-channel). Its integration tests run in :quizbuks, which
// starts the application.
base {
    archivesName.set("quizbuks-shopify")
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":quizbuks-channel"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Q55b: the brand profile QB_BRAND chooses (quizbuks-web/brands/<brand>/brand.json, docs/quizbuks/plans/Q54-brand-profile.md)
// is written into the resources when they are packaged: @QB_BRAND_NAME@ (and the support address and site) in the
// messages of QuizBuks for Shopify. A brand is deployed on its own, so the jar is the brand's.
val qbBrandTokens: Provider<Map<String, String>> = providers.environmentVariable("QB_BRAND")
    .map { it.trim() }.filter { it.isNotEmpty() }.orElse("quizbuks")
    .map { id ->
        require(Regex("[a-z0-9][a-z0-9-]*").matches(id)) { "QB_BRAND must name a brand profile, was \"$id\"" }
        val file = rootProject.file("../quizbuks-web/brands/$id/brand.json")
        require(file.isFile) { "QB_BRAND=$id: no brand profile ${file.path}" }
        @Suppress("UNCHECKED_CAST")
        val profile = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
        mapOf(
            "QB_BRAND_NAME" to profile["name"].toString(),
            "QB_BRAND_SUPPORT_EMAIL" to profile["supportEmail"].toString(),
            "QB_BRAND_SITE_URL" to profile["siteUrl"].toString(),
        )
    }

tasks.named<ProcessResources>("processResources") {
    inputs.property("brand", qbBrandTokens)
    filteringCharset = "UTF-8"
    filesMatching("jabiz/quizbuks/shopify/messages*.properties") {
        filter(mapOf("tokens" to qbBrandTokens.get()), org.apache.tools.ant.filters.ReplaceTokens::class.java)
    }
}

import com.jabiz.gradle.Pwa

// QuizBuks (docs/quizbuks/02-design.md): the runtime with the QuizBuks declarations in one jar, serving three front
// ends (docs/quizbuks/plans/QP-pages.md): the takers' app at /taker/ (a PWA; Q30 moved it from /), the sponsor console
// at /sponsor/ and the platform's admin frontend at /admin/, each signing in to its own entry, and the business site at
// /biz/ (static pages, no sign-in; the bare address opens it, jabiz.web.root-redirect). The two QuizBuks SPAs are pnpm projects of
// their own linking the platform's packages; spaCheckTaker, spaCheckSponsor and spaCheckBiz (part of check) keep their versions the
// platform's. The admin frontend carries QuizBuks's own pages (admin-extension: reviews and the user summary,
// docs/quizbuks/plans/Q9a-admin-extension.md), type-checked and compiled in when the jar is built.
plugins {
    id("jabiz.boot-app")
}

jabizApp {
    mainClass = "com.jabiz.quizbuks.QuizbuksApp"
    spa("/taker", "../../quizbuks-web/app", entry = "app", pwa = Pwa.ON)
    spa("/biz", "../../quizbuks-web/biz")
    spa("/sponsor", "../../quizbuks-web/sponsor", entry = "sponsor")
    // QuizBuks for Shopify's embedded pages (S1): framed by the shop that opened them (jabiz.web.spa[].embedding).
    spa("/shopify", "../../quizbuks-web/shopify", entry = "shopify")
    // Its public pages (S4a): the landing page, the privacy policy and support, static like the business site.
    spa("/shopify-app", "../../quizbuks-web/shopify-site")
    spa("/admin", "../../frontend", extension = "admin-extension", entry = "admin")
    // Q48a: the operators' back office, the same admin frontend and pages signing in to the entry operator; the menu
    // offers what the operator's permissions allow.
    spa("/operator", "../../frontend", extension = "admin-extension", entry = "operator")
    languages("en", "zh", "ja")
    // The snapshots stay in the application's directory: the platform's own (frontend/openapi) are not ours to write.
    openApiSnapshot = rootProject.layout.projectDirectory.file("quizbuks/src/test/resources/openapi.json")
    publicQueriesSnapshot = rootProject.layout.projectDirectory.file("quizbuks/src/test/resources/public-queries.json")
}

// S4a: the public pages of QuizBuks for Shopify write the support address and the site's origin in at build time
// (quizbuks-web/shopify-site/scripts/build.mjs), so a change of either builds them again.
tasks.matching { it.name == "spaBuildShopifyApp" }.configureEach {
    inputs.property("supportEmail", providers.environmentVariable("QB_SHOPIFY_SUPPORT_EMAIL").orElse(""))
    inputs.property("siteUrl", providers.environmentVariable("SITE_URL").orElse(""))
}

// Q54: QuizBuks's own front ends are built with the brand profile QB_BRAND chooses (quizbuks-web/brands/<brand>,
// docs/quizbuks/plans/Q54-brand-profile.md): a change of the choice or of any profile builds them again.
val brandedSpas = setOf("spaBuildTaker", "spaBuildSponsor", "spaBuildBiz", "spaBuildShopify", "spaBuildShopifyApp")
tasks.matching { it.name in brandedSpas }.configureEach {
    inputs.property("brand", providers.environmentVariable("QB_BRAND").orElse(""))
    inputs.dir(rootProject.layout.projectDirectory.dir("../quizbuks-web/brands"))
}
// Q55b: the admin and operator back offices take the brand from the extension (admin-extension/src/brand.ts), which
// holds every profile (brands.generated.ts) and picks the one VITE_QB_BRAND names.
tasks.matching { it.name in setOf("spaBuildAdmin", "spaBuildOperator") }.configureEach {
    val brand = providers.environmentVariable("QB_BRAND").orElse("")
    inputs.property("brand", brand)
    (this as com.github.gradle.node.pnpm.task.PnpmTask).environment.put("VITE_QB_BRAND", brand)
}

dependencies {
    implementation(project(":runtime"))
    // QuizBuks for Shopify (docs/quizbuks/shopify/S1-install.md): a library of its own, in this jar.
    implementation(project(":quizbuks-shopify"))
    // The channel contract (S2): the inputs and outputs of the processes QuizBuks for Shopify calls.
    implementation(project(":quizbuks-channel"))
    testImplementation(testFixtures(project(":runtime")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("net.jqwik:jqwik:1.9.3")
    // Registration and mail against a local SMTP server (Q2), as the platform's own tests.
    testImplementation("com.icegreen:greenmail-junit5:2.1.3")
    // The statement budget of answering (Q10a3) records the statements as the platform's own budget test does.
    testImplementation("io.r2dbc:r2dbc-proxy")
    // The migration of the lock split (Q10a4) is tested by migrating schemas of its own up to V9 and on.
    testImplementation("org.flywaydb:flyway-core")
    // A second instance of the platform's job executor (an attempt whose lease ran out, W-6) takes the meter registry.
    testImplementation("io.micrometer:micrometer-core")
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
    // Q8a: Claude's key, which also comes from the environment only.
    environment("ANTHROPIC_API_KEY", "platform-check-throwaway-not-a-claude-key")
    // Q29: the key sealing the sponsors' mail server passwords (platform decision D56): declared a MailSenderSource,
    // the application refuses to start without it; a throwaway value here, as the launcher's own keys.
    environment("JABIZ_SEAL_KEY", "cGxhdGZvcm0tY2hlY2stdGhyb3dhd2F5LXNlYWwta2V5LW5vdC1mb3ItYW55LXVzZS0wMA==")
    // S1: the Shopify app's key and secret (session tokens, webhooks and the Admin API), from the environment only.
    environment("SHOPIFY_API_KEY", "platform-check-throwaway-shopify-key")
    environment("SHOPIFY_API_SECRET", "platform_check_throwaway_shopify_secret_00")
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

// Load test of one hot publication (docs/quizbuks/plans/Q10-launch.md section 3, docs/quizbuks/perf/Q10a-load-test.md):
// drives a running jar over HTTP with real tokens, as the platform app's loadTest does. Not part of check; run it
// against a started jar: LOAD_ADMIN_PASSWORD=... LOAD_MAILPIT_URL=... ./gradlew :quizbuks:loadTest. With LOAD_DB_URL it
// also reads pg_stat_statements and samples pg_stat_activity of the application's database (JDBC, outside the jar).
val loadTestSourceSet = sourceSets.create("loadTest")
dependencies {
    "loadTestImplementation"("org.hdrhistogram:HdrHistogram:2.2.2")
    "loadTestImplementation"("tools.jackson.core:jackson-databind")
    "loadTestRuntimeOnly"("org.postgresql:postgresql")
}

tasks.register<JavaExec>("loadTest") {
    group = "verification"
    description = "Load test of one hot publication of a running QuizBuks (LOAD_BASE_URL, LOAD_ADMIN_*, LOAD_* sizes)."
    classpath = loadTestSourceSet.runtimeClasspath
    mainClass.set("com.jabiz.quizbuks.load.QuizbuksLoadTest")
    outputs.upToDateWhen { false }
}

// The load test is not run by check, but it is compiled there: it cannot break unnoticed.
tasks.named("check") {
    dependsOn("compileLoadTestJava")
}

// Q55b: the brand profile QB_BRAND chooses (quizbuks-web/brands/<brand>/brand.json, docs/quizbuks/plans/Q54-brand-profile.md)
// is written into the resources when they are packaged: @QB_BRAND_NAME@ (and the support address and site) in the
// messages, quizbuks/brand.properties and application.yml. A brand is deployed on its own, so the jar is the brand's.
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
    filesMatching(listOf("messages*.properties", "quizbuks/brand.properties", "application.yml")) {
        filter(mapOf("tokens" to qbBrandTokens.get()), org.apache.tools.ant.filters.ReplaceTokens::class.java)
    }
}

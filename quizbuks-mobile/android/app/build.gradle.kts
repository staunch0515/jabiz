import groovy.json.JsonSlurper

// The shell's settings are all in ../twa-manifest.json (the Bubblewrap format; README.md): the build reads them from
// there, so the manifest the CI compares with the app's web manifest is the one that is built.
plugins {
    id("com.android.application")
}

@Suppress("UNCHECKED_CAST")
val twa = JsonSlurper().parse(rootProject.file("twa-manifest.json")) as Map<String, Any>
fun twa(key: String): String = twa[key]?.toString() ?: error("twa-manifest.json has no $key")

val host = twa("host")
// Play needs a larger version code for every upload: the upload workflow passes its run number (README.md).
val versionCodeFromCi = System.getenv("QB_ANDROID_VERSION_CODE")?.toInt() ?: 1

// The upload key (Play App Signing re-signs with the app signing key Google keeps): only from the environment, never
// from a file in the repository. Without it the release is built unsigned, which is what the pull requests' check does.
val keystore = System.getenv("QB_ANDROID_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = twa("packageId")
    compileSdk = 36

    defaultConfig {
        applicationId = twa("packageId")
        minSdk = twa("minSdkVersion").toInt()
        targetSdk = 36
        versionCode = versionCodeFromCi
        versionName = twa("appVersionName")

        resValue("string", "appName", twa("name"))
        resValue("string", "launcherName", twa("launcherName"))
        resValue("string", "launchUrl", "https://$host${twa("startUrl")}")
        resValue("string", "hostName", host)
        resValue("string", "providerAuthority", "${twa("packageId")}.fileprovider")
        resValue("string", "fallbackType", twa("fallbackType"))
        resValue("string", "orientation", twa("orientation"))
        resValue("string", "generatorApp", twa("generatorApp"))
        resValue("string", "assetStatements", """
            [{ "relation": ["delegate_permission/common.handle_all_urls"],
               "target": { "namespace": "web", "site": "https://$host" } }]
        """.trimIndent().replace("\"", "\\\""))
        resValue("color", "colorPrimary", twa("themeColor"))
        resValue("color", "colorPrimaryDark", twa("themeColorDark"))
        resValue("color", "navigationColor", twa("navigationColor"))
        resValue("color", "navigationColorDark", twa("navigationColorDark"))
        resValue("color", "navigationDividerColor", twa("navigationDividerColor"))
        resValue("color", "navigationDividerColorDark", twa("navigationDividerColorDark"))
        resValue("color", "backgroundColor", twa("backgroundColor"))
        resValue("integer", "splashScreenFadeOutDuration", twa("splashScreenFadeOutDuration"))
        resValue("bool", "enableSiteSettingsShortcut", twa("enableSiteSettingsShortcut"))
        // The manifest's intent filter takes the host and the path prefix as placeholders (a resource is not allowed in
        // pathPrefix): only the app's addresses open the app, the sponsor console and the business site stay in the
        // browser (Q49b D-Q49b-4).
        manifestPlaceholders["hostName"] = host
        manifestPlaceholders["scopePath"] = twa("scope")
        buildConfigField("String", "HOST", "\"$host\"")
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    signingConfigs {
        if (keystore != null) {
            create("upload") {
                storeFile = file(keystore)
                storePassword = System.getenv("QB_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QB_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("QB_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.7.4")
}

import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

fun resolveGitSha(): String {
    return try {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(project.rootDir)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
            .ifBlank { "dev" }
    } catch (_: Exception) {
        "dev"
    }
}

fun quoteBuildConfigString(value: String): String {
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

android {
    namespace = "com.matelink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.matelink"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.4.2"
        buildConfigField("String", "GIT_SHA", "\"${resolveGitSha()}\"")
        val publicInfoBaseUrl = providers.gradleProperty("MATELINK_PUBLIC_INFO_BASE_URL").orElse("").get()
        buildConfigField("String", "MATELINK_PUBLIC_INFO_BASE_URL", "\"$publicInfoBaseUrl\"")
        buildConfigField("boolean", "JOURVOLT_MOCK_LOGIN", "false")
        buildConfigField("String", "JOURVOLT_MOCK_SOURCE", "\"\"")
        val cloudLoginEnabled = providers.gradleProperty("JOURVOLT_CLOUD_LOGIN")
            .orElse("true")
            .get()
            .toBoolean()
        buildConfigField("boolean", "JOURVOLT_CLOUD_LOGIN", cloudLoginEnabled.toString())
        val cloudBaseUrl = providers.gradleProperty("JOURVOLT_API_BASE_URL")
            .orElse("https://api.jourvolt.com/")
            .get()
        buildConfigField("String", "JOURVOLT_API_BASE_URL", quoteBuildConfigString(cloudBaseUrl))
        buildConfigField("String", "JOURVOLT_MOCK_BASE_URL", "\"\"")
        manifestPlaceholders["amapApiKey"] = providers.gradleProperty("AMAP_API_KEY").orElse("").get()
        val jourVoltAuthHost = providers.gradleProperty("JOURVOLT_AUTH_HOST")
            .orElse("auth.jourvolt.com")
            .get()
            .trim()
            .removePrefix("https://")
            .removeSuffix("/")
        buildConfigField("String", "JOURVOLT_AUTH_HOST", quoteBuildConfigString(jourVoltAuthHost))
        manifestPlaceholders["jourvoltAuthHost"] = jourVoltAuthHost
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val formalReleaseSigningConfig = providers.gradleProperty("MATELINK_SIGNING_PROPERTIES_FILE")
        .orNull
        ?.let { configuredPath ->
            val propertiesFile = File(configuredPath).absoluteFile
            check(propertiesFile.isFile) {
                "MATELINK_SIGNING_PROPERTIES_FILE does not point to a file"
            }
            val properties = Properties()
            propertiesFile.inputStream().use { properties.load(it) }
            fun requiredProperty(name: String): String =
                properties.getProperty(name)?.trim().orEmpty().also {
                    check(it.isNotEmpty()) { "Signing property $name is required" }
                }

            val configuredStoreFile = File(requiredProperty("storeFile"))
            val keystoreFile = if (configuredStoreFile.isAbsolute) {
                configuredStoreFile
            } else {
                File(propertiesFile.parentFile ?: project.rootDir, configuredStoreFile.path)
            }
            check(keystoreFile.isFile) { "Signing keystore file does not exist" }

            signingConfigs.create("formalRelease") {
                storeFile = keystoreFile
                storePassword = requiredProperty("storePassword")
                keyAlias = requiredProperty("keyAlias")
                keyPassword = requiredProperty("keyPassword")
            }
        }

    buildTypes {
        debug {
            applicationIdSuffix = ".test.mock"
            resValue("string", "app_name", "MateLink Test")
            buildConfigField("boolean", "JOURVOLT_MOCK_LOGIN", "true")
            buildConfigField("String", "JOURVOLT_MOCK_SOURCE", "\"mock_fixture\"")
            buildConfigField("boolean", "JOURVOLT_CLOUD_LOGIN", "false")
            val mockBaseUrl = providers.gradleProperty("JOURVOLT_MOCK_BASE_URL")
                .orElse("http://10.0.2.2:18090/")
                .get()
            buildConfigField("String", "JOURVOLT_MOCK_BASE_URL", quoteBuildConfigString(mockBaseUrl))
            val debugApiBaseUrl = providers.gradleProperty("JOURVOLT_DEBUG_API_BASE_URL")
                .orElse(mockBaseUrl)
                .get()
            buildConfigField("String", "JOURVOLT_API_BASE_URL", quoteBuildConfigString(debugApiBaseUrl))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = formalReleaseSigningConfig
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Production App Link guard: a Release build must carry explicitly provided
// JourVolt API / App Link configuration. Silent fallbacks (api.jourvolt.com /
// auth.jourvolt.com) must never reach a signed package, so a Release task graph
// fails fast when the pilot properties are absent. Debug builds are unaffected;
// build-pilot-apk.ps1 always passes both properties explicitly.
val releaseGuardApiBaseUrl = providers.gradleProperty("JOURVOLT_API_BASE_URL").orNull
val releaseGuardAuthHost = providers.gradleProperty("JOURVOLT_AUTH_HOST").orNull
    ?.trim()
    ?.removePrefix("https://")
    ?.removeSuffix("/")
tasks.matching { it.name == "generateReleaseBuildConfig" }.configureEach {
    doFirst {
        check(!releaseGuardApiBaseUrl.isNullOrBlank()) {
            "Release guard: provide -PJOURVOLT_API_BASE_URL explicitly; " +
                "a Release package must never fall back to the default API URL"
        }
        check(releaseGuardAuthHost == "auth.teslalink.joviluma.com") {
            "Release guard: provide -PJOURVOLT_AUTH_HOST=auth.teslalink.joviluma.com explicitly " +
                "(got: ${releaseGuardAuthHost ?: "<missing>"}); the App Link host must match production assetlinks"
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security (EncryptedSharedPreferences)
    implementation(libs.security.crypto)
    implementation(libs.errorprone.annotations)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Glance (App Widgets)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Charts
    implementation(libs.mpandroidchart)

    // Pinned to the latest exact version currently published on Maven Central.
    // The official download package may be newer than the Maven artifact.
    implementation("com.amap.api:3dmap-location-search:11.1.001_loc11.1.001_sea9.7.4")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    doFirst {
        check(providers.gradleProperty("allowConnectedDeviceTests").orNull == "isolated-device") {
            "Connected Android tests are blocked by default because they can uninstall the target app. " +
                "Run them only on an isolated test device with -PallowConnectedDeviceTests=isolated-device."
        }
    }
}

val verifyDebugForegroundServiceType by tasks.registering {
        dependsOn("processDebugMainManifest")

    doLast {
        val mergedManifest = layout.buildDirectory
            .file("intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
            .get()
            .asFile
        val manifestText = mergedManifest.readText()
        val workManagerService = Regex(
            """<service\s+[^>]*android:name="androidx.work.impl.foreground.SystemForegroundService"[^>]*android:foregroundServiceType="dataSync"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        check(workManagerService.containsMatchIn(manifestText)) {
            "Merged WorkManager foreground service must declare android:foregroundServiceType=\"dataSync\"."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyDebugForegroundServiceType)
}

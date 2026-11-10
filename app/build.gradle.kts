import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: read the keystore from keystore.properties if present (kept
// out of git). Without it — e.g. a fresh checkout — release falls back to the
// debug key so the project still builds.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream()) }

// Dev channel (testing on the owner's phone only): "assembleDev" makes a separate app, VoxelPort Dev (its own package, so it sits
// next to the normal one), that updates itself from a private feed. The feed's secret file name comes from dev.properties (never committed):
//   devToken=<random hex>
val devPropsFile = rootProject.file("dev.properties")
val devProps = Properties().apply { if (devPropsFile.exists()) load(devPropsFile.inputStream()) }
val devToken: String = devProps.getProperty("devToken") ?: "not-configured"
val gitShort: String = runCatching {
    ProcessBuilder("git", "rev-parse", "--short", "HEAD").directory(rootProject.projectDir).redirectErrorStream(true).start()
        .inputStream.bufferedReader().readText().trim()
}.getOrDefault("").ifEmpty { "dev" }

android {
    namespace = "com.voxelport.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.voxelport.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.0.0"
        // The bundled Java runtime is arm64 only; every phone that can host a server is arm64.
        // Where this build looks for updates (comma separated), and whether it is the dev build.
        buildConfigField("String", "UPDATE_FEEDS", "\"https://voxelport.github.io/dl/android.json,https://voxelport.in/dl/android.json\"")
        buildConfigField("boolean", "IS_DEV", "false")
        manifestPlaceholders["appLabel"] = "VoxelPort"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystorePropsFile.exists()) "release" else "debug")
        }
        // The private dev build: another package (installs next to the real app), a private update feed, and a version that goes up with every build.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            matchingFallbacks += listOf("release")
            manifestPlaceholders["appLabel"] = "VoxelPort Dev"
            buildConfigField("String", "UPDATE_FEEDS", "\"https://voxelport.in/dl/dev-$devToken.json\"")
            buildConfigField("boolean", "IS_DEV", "true")
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // The Java runtime is already xz-compressed; storing it as-is lets us stream it.
    androidResources { noCompress += "xz" }
    packaging { jniLibs { useLegacyPackaging = false } }
}

androidComponents {
    onVariants(selector().withBuildType("dev")) { variant ->
        // Minutes since a fixed day, so every dev build is newer than the last one (the real app's codes are small and unrelated).
        val code = ((System.currentTimeMillis() / 60_000L) - 29_400_000L).toInt()
        variant.outputs.forEach {
            it.versionCode.set(code)
            it.versionName.set("dev-$gitShort")
        }
    }
}

dependencies {
    implementation(files("libs/vpmobile.aar")) // VoxelPort tunnel (Go, gomobile) from the CLI repo
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013") // the real org.json (android.jar only has stubs on the unit-test classpath)
}

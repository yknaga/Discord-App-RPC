@file:Suppress("UnstableApiUsage")

plugins {
    id("rpc.android.application")
    id("rpc.android.application.compose")
    id("rpc.android.hilt")
}

// Release signing is provided by CI through environment variables (the
// keystore itself is kept in GitHub Secrets, never in this repository).
// A fixed key makes every CI build share one signature, so a new APK can
// be installed over the previous one instead of requiring an uninstall.
// Without the variables, local builds keep using the debug key as before.
val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_FILE")

// CI passes -PappVersionCode=<number> so every CI build gets a higher
// versionCode than the previous one and Android accepts it as an update.
val ciVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull()

android {
    namespace = "com.my.rpc"

    signingConfigs {
        create("release") {
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.my.rpc"
        versionCode = ciVersionCode ?: libs.versions.version.code.get().toInt()
        versionName = libs.versions.version.name.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName(
                if (releaseKeystorePath != null) "release" else "debug"
            )
        }
    }

    packagingOptions.resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")

    // Disables dependency metadata when building APKs.
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        // This is for the signed .apk that we post to GitHub releases
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        // This is for the Google Play Store if we ever decide to publish there
        includeInBundle = true
    }
}
dependencies {
    implementation (projects.domain)
    implementation (projects.theme)
    implementation (projects.featureStartup)
    implementation (projects.featureCrashHandler)
    implementation (projects.featureProfile)
    implementation (projects.featureAbout)
    implementation (projects.featureSettings)
    implementation (projects.featureLogs)
    implementation (projects.featureRpcBase)
    implementation (projects.featureRpc)
    implementation (projects.featureHome)
    implementation (projects.common.preference)
    implementation (projects.common.navigation)

    // Extras
    implementation (libs.app.compat)
    implementation (libs.accompanist.navigation.animation)
    implementation (libs.kotlinx.serialization.json)


    // Material
    implementation (libs.material3)
    implementation (libs.androidx.material)
    implementation (libs.material3.windows.size)
}
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.booxultimatum"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.booxultimatum"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 25
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    @Suppress("UNCHECKED_CAST")
    val publishSigning = rootProject.extra["publishSigning"] as Properties?
    signingConfigs {
        if (publishSigning != null) create("publish") {
            storeFile = file(publishSigning.getProperty("storeFile"))
            storePassword = publishSigning.getProperty("storePassword")
            keyAlias = publishSigning.getProperty("keyAlias")
            keyPassword = publishSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Minified and non-debuggable for real-world memory and CPU. Without -Pbu.signing it is signed with the
            // debug key, for CI and quick checks; a public build, and any build for a tablet running the published
            // app, must use the release key.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (publishSigning != null) "publish" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    lint {
        // Signature/development permissions are granted via adb by design (tier T1).
        disable += "ProtectedPermissions"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        // knowledge/ is the single source of truth for the package KB; ship it as assets.
        getByName("main").assets.srcDir(rootProject.file("knowledge"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":kit:core"))
    implementation(project(":kit:ui"))
    implementation(project(":kit:ink"))
    implementation(project(":kit:update"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlin.test)
}
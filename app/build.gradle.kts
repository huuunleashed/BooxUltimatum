import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.booxultimatum"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.booxultimatum"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    // Public releases are signed with a key kept outside the repository. Pass the path to a properties file holding
    // storeFile, storePassword, keyAlias and keyPassword: `.\gradlew.bat assembleRelease -Pbu.signing=<path>`.
    val publishSigning = (findProperty("bu.signing") as String?)?.let { file(it) }?.takeIf { it.isFile }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
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
            // debug key, so it upgrades the developer's installed build in place and keeps its adb grants and Shizuku
            // permission; a public build must use the release key.
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
}

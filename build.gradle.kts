import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Public releases of every suite app are signed with one key kept outside the repository, so the apps can trust each
// other through signature permissions. Pass the path to a properties file holding storeFile, storePassword, keyAlias
// and keyPassword: `.\gradlew.bat assembleRelease -Pbu.signing=<path>`. Without it, release builds use the debug key.
val publishSigning: Properties? = (findProperty("bu.signing") as String?)?.let { file(it) }?.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
extra["publishSigning"] = publishSigning
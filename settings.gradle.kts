pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BooxUltimatum"

// The suite's apps. Each is its own APK, signed with the same key.
include(":hub")          // app.booxultimatum: the hub, with the home screen, sleep screen, Instant ink and tweaks
include(":nib")          // app.booxultimatum.nib: the drawing app
include(":nib-engine")   // Nib's drawing model, pure Kotlin so it tests on the JVM

// The kit: libraries every suite app builds on.
include(":kit:core")     // the tablet profile, the suite registry, shared plumbing
include(":kit:log")      // structured logs, crash capture and export
include(":kit:ui")       // the Braun Instrument design system
include(":kit:ink")      // the Onyx pen path: SurfaceFlinger's handwriting calls and the pen's input node
include(":kit:update")   // releases, verification and installs for every suite app
pluginManagement {
    repositories {
        maven("https://maven.2b2t.vc/releases")
        maven("https://maven.kikugie.dev/snapshots") {
            name = "KikuGie Snapshots"
        }
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.8.3"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        versions("1.21.4", "1.21.8", "1.21.11", "26.1.2", "26.2.0")
        vcsVersion = "1.21.4"
    }
}

rootProject.name = ext.properties["plugin_name"] as String

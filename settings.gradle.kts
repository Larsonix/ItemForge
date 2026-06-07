pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provision Java toolchain from Adoptium/Azul if not locally installed
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ItemForge"

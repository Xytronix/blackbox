pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("sh.harold.hytale.run") version "0.1.1"
    }
}

rootProject.name = "blackbox"

include(":blackbox-core")
include(":blackbox-hytale")

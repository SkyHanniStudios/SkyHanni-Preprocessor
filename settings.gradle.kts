pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.deftu.dev/releases/")
        maven("https://maven.deftu.dev/snapshots/")
        maven("https://jitpack.io/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.architectury.dev/")
        maven("https://repo.essential.gg/repository/maven-public/")
    }
}

rootProject.name = "studios.skyhanni.preprocess.gradle.plugin"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version ("0.7.0")
}

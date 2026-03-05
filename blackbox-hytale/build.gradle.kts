import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.the
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.testing.Test

plugins {
    java
    id("sh.harold.hytale.run")
}

val hytaleServerVersion = providers
    .gradleProperty("hytaleServerVersion")
    .orElse("2026.02.19-1a311a592")

repositories {
    mavenCentral()
    maven("https://maven.hytale.com/release")
    maven("https://maven.hytale.com/pre-release")
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:${hytaleServerVersion.get()}")
    implementation(project(":blackbox-core"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("com.hypixel.hytale:Server:${hytaleServerVersion.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<ProcessResources>("processResources") {
    filesMatching("manifest.json") {
        expand(mapOf("version" to rootProject.version.toString()))
    }
}

tasks.named<Test>("test") {
    jvmArgs("-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager")
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(project(":blackbox-core").the<JavaPluginExtension>().sourceSets.getByName("main").output)
}

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("recordCodexEdit") {
    group = "flowmetric"
    description = "Record an explicit Codex patch event into .flowmetric/events.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.flowmetric.shared.tracking.CodexPatchRecorderMain")
    args(
        providers.gradleProperty("flowmetricProjectRoot").orNull ?: "",
        providers.gradleProperty("flowmetricFilePath").orNull ?: "",
        providers.gradleProperty("flowmetricBeforeFile").orNull ?: "",
        providers.gradleProperty("flowmetricSourceLabel").orNull ?: "Codex",
        providers.gradleProperty("flowmetricAgentModel").orNull ?: "",
    )
}

tasks.register<JavaExec>("recordCodexBatch") {
    group = "flowmetric"
    description = "Record explicit Codex patch events for multiple files into .flowmetric/events.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.flowmetric.shared.tracking.CodexPatchBatchRecorderMain")
    args(
        providers.gradleProperty("flowmetricProjectRoot").orNull ?: "",
        providers.gradleProperty("flowmetricManifestFile").orNull ?: "",
        providers.gradleProperty("flowmetricSourceLabel").orNull ?: "Codex",
        providers.gradleProperty("flowmetricAgentModel").orNull ?: "",
    )
}

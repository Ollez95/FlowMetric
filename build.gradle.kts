plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
    kotlin("plugin.compose") version "2.3.0" apply false
    id("org.jetbrains.compose") version "1.9.1" apply false
    id("org.jetbrains.intellij.platform") version "2.11.0" apply false
}

allprojects {
    group = "com.flowmetric"
    version = "0.1.0"

    repositories {
        mavenCentral()
        google()
        maven("https://packages.jetbrains.team/maven/p/compose/dev")
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://www.jetbrains.com/intellij-repository/snapshots")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

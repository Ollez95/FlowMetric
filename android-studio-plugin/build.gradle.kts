val androidStudioLocalPath = providers.gradleProperty("androidStudioLocalPath")
    .orElse("/Applications/Android Studio.app")
    .get()

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":shared-core"))
    intellijPlatform {
        local(androidStudioLocalPath)
        bundledPlugin("org.jetbrains.android")
        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
}

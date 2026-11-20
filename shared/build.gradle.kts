plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

android {
    namespace = "com.deepseek.client.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }
}

kotlin {
    jvmToolchain(17)
    jvm()
    js(IR) {
        useEsModules()
        browser()
        binaries.library()
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "DeepSeekShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("io.ktor:ktor-client-core:3.3.1")
        }

        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.3.1") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.1") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.3.1") }
        jsMain.dependencies { implementation("io.ktor:ktor-client-js:3.3.1") }
        jvmTest.dependencies { implementation("com.squareup.okhttp3:mockwebserver:5.1.0") }
        jsTest.dependencies { implementation(kotlin("test")) }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}


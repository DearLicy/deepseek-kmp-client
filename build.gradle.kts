plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.2.20" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
}


// All generated build products stay on the project volume, inside .tmp.
allprojects {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".tmp/build/${project.name}"))
}

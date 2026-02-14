// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.3" apply false  // Updated to latest stable
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false  // Updated to Kotlin 2.1
    id("com.google.dagger.hilt.android") version "2.52" apply false  // Updated
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false  // Updated for Kotlin 2.1
    id("androidx.navigation.safeargs.kotlin") version "2.8.5" apply false  // Updated
    id("com.google.gms.google-services") version "4.4.2" apply false  // Updated
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)  // Updated from buildDir (deprecated)
}

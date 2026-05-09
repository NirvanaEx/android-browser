// Top-level build file. Plugin versions are declared here and applied per-module.
//
// AGP 8.9+ is required because android-components 150.x transitively pulls
// androidx.activity:1.13.0 which targets compileSdk 36 and rejects older AGPs.
plugins {
    id("com.android.application") version "8.9.1" apply false
    // Kotlin plugin must match the stdlib version transitively pulled by
    // android-components 150.x (kotlin-stdlib 2.3.20). Mismatches between
    // compiler 2.0.x and stdlib 2.3.x cause "source must not be null"
    // K2 compiler crashes during analysis.
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.upgrid.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.upgrid.browser"
        minSdk = 26          // Android 8.0+ — GeckoView's floor.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // GeckoView ships native code for these ABIs; keep splits enabled for size.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with debug key for now; add real signing config before publishing.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        // AGP 8.x defaults this to false. We read BuildConfig.DEBUG in
        // BrowserComponents to gate verbose console output from the engine.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
            // OkHttp 5 + jspecify ship duplicate OSGi manifests; pick any one.
            pickFirsts += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/*/OSGI-INF/**",
            )
        }
    }
}

// Kotlin 2.3 removed `kotlinOptions { jvmTarget = "..." }` — use the new DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Pin the android-components version — every a-c artifact MUST match.
// Bump together. See https://maven.mozilla.org/maven2/org/mozilla/components/
val androidComponentsVersion = "150.0.2"

dependencies {
    // --- AndroidX baseline ---
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.google.android.material:material:1.12.0")

    // --- Mozilla browser engine (transitively pulls geckoview-stable) ---
    implementation("org.mozilla.components:browser-engine-gecko:$androidComponentsVersion")
    implementation("org.mozilla.components:concept-engine:$androidComponentsVersion")
    implementation("org.mozilla.components:concept-fetch:$androidComponentsVersion")

    // --- Browser state / session plumbing ---
    implementation("org.mozilla.components:browser-state:$androidComponentsVersion")
    implementation("org.mozilla.components:browser-session-storage:$androidComponentsVersion")
    implementation("org.mozilla.components:feature-session:$androidComponentsVersion")
    implementation("org.mozilla.components:feature-tabs:$androidComponentsVersion")

    // --- UI: omnibar + tabs tray + favicons + page-features ---
    implementation("org.mozilla.components:browser-toolbar:$androidComponentsVersion")
    implementation("org.mozilla.components:browser-tabstray:$androidComponentsVersion")
    implementation("org.mozilla.components:browser-icons:$androidComponentsVersion")
    implementation("org.mozilla.components:feature-toolbar:$androidComponentsVersion")
    // FindInPageBar + feature wiring for the menu's "Find in page" action.
    implementation("org.mozilla.components:feature-findinpage:$androidComponentsVersion")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // --- Web extensions (this is what gives us uBlock Origin) ---
    // The actual install/enable APIs live on `concept-engine.Engine` (already pulled);
    // `feature-addons` adds the higher-level AddonManager + signed AMO list.
    implementation("org.mozilla.components:feature-addons:$androidComponentsVersion")

    // --- HTTP client used by feature-addons to download XPI ---
    implementation("org.mozilla.components:lib-fetch-okhttp:$androidComponentsVersion")

    // --- Support utilities ---
    implementation("org.mozilla.components:support-base:$androidComponentsVersion")
    implementation("org.mozilla.components:support-ktx:$androidComponentsVersion")
    implementation("org.mozilla.components:support-utils:$androidComponentsVersion")

    // Coroutines for async work.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

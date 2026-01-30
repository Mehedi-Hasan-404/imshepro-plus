plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("kapt")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("kotlin-parcelize")
    id("androidx.navigation.safeargs.kotlin")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.livetvpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.livetvpro"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
        
        // ✅ CRITICAL: Support for ALL architectures
        // armeabi-v7a: 32-bit ARM phones/tablets
        // arm64-v8a: 64-bit ARM phones/tablets (most modern devices)
        // x86: 32-bit x86 emulators, some Intel tablets, older Android TV
        // x86_64: 64-bit emulators, Intel Chromebooks, modern Android TV/Fire TV
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        
        externalNativeBuild {
            cmake {
                cppFlags += ""
                // ✅ Build native libs for all ABIs
                arguments += listOf(
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    // Signing configuration for release builds
    signingConfigs {
        create("release") {
            // Read from environment variables (GitHub Actions)
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../release-keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            
            // ✅ Include all ABIs in release builds
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            
            // ✅ For faster debug builds, you can limit to specific ABIs
            // Uncomment to only build for your test device during development:
            // ndk {
            //     abiFilters += listOf("arm64-v8a", "x86_64")
            // }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        
        freeCompilerArgs += listOf(
            "-opt-in=androidx.media3.common.util.UnstableApi"
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        
        // ✅ Ensure native libraries are properly packaged for all ABIs
        jniLibs {
            useLegacyPackaging = false
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    // ✅ OPTIONAL: Create separate APKs per ABI to reduce download size
    // Users will only download the APK for their device architecture
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true // Also creates a universal APK with all ABIs
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // DrawerLayout
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ExoPlayer (Media3)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")

    // OkHttp & Retrofit
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // SwipeRefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Chrome Custom Tabs
    implementation("androidx.browser:browser:1.7.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-config-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Lottie Animations
    implementation("com.airbnb.android:lottie:6.3.0")
    
    // ✅ Leanback for Android TV support (OPTIONAL - add if you want TV-optimized UI)
    // implementation("androidx.leanback:leanback:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

kapt {
    correctErrorTypes = true
}

// ✅ Handle version codes for ABI splits (ensures unique version codes per ABI)
android.applicationVariants.all {
    outputs.all {
        val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val abiName = output.getFilter("ABI")
        if (abiName != null) {
            val abiVersionCode = when (abiName) {
                "armeabi-v7a" -> 1
                "arm64-v8a" -> 2
                "x86" -> 3
                "x86_64" -> 4
                else -> 0
            }
            output.versionCodeOverride = (versionCode ?: 0) * 10 + abiVersionCode
        }
    }
}

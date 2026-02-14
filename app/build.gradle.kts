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
    compileSdk = 35  // Updated to latest

    defaultConfig {
        applicationId = "com.livetvpro"
        minSdk = 24
        targetSdk = 35  // Updated to latest
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
        
        // NDK Configuration
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        
        // CMake Configuration
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf(
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
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
            
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        compose = true  // ⚡ CRITICAL: Required for PlayerActivity Compose controls
    }

    composeOptions {
        // Must match Kotlin version compatibility
        // Kotlin 1.9.20 -> Compose Compiler 1.5.4
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        
        jniLibs {
            useLegacyPackaging = false
        }
    }
    
    // Native Build Configuration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    // ABI Splits Configuration
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")  // Updated
    implementation("androidx.appcompat:appcompat:1.7.0")  // Updated
    implementation("com.google.android.material:material:1.12.0")  // Updated
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")  // Updated
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    // Lifecycle Components
    val lifecycleVersion = "2.8.7"  // Updated
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")  // For Compose

    // Navigation Components
    val navVersion = "2.8.5"  // Updated
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Jetpack Compose - CRITICAL for PlayerActivity
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    
    // Material 3 for Compose (used in PlayerControls.kt)
    implementation("androidx.compose.material3:material3")
    
    // Material Icons Extended (for Icons.Filled.* in PlayerControls)
    implementation("androidx.compose.material:material-icons-extended")
    
    // Activity Compose integration
    implementation("androidx.activity:activity-compose:1.9.3")
    
    // Compose Runtime
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.runtime:runtime-livedata")
    
    // Compose Tooling (debug builds only)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Media3 (ExoPlayer) - Latest Stable
    val media3Version = "1.5.0"  // Updated to latest stable
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Network - OkHttp & Retrofit
    val okhttpVersion = "4.12.0"
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okhttpVersion")
    
    val retrofitVersion = "2.11.0"  // Updated
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitVersion")

    // Coroutines
    val coroutinesVersion = "1.9.0"  // Updated
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")

    // Dependency Injection - Hilt
    val hiltVersion = "2.52"  // Updated
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    kapt("com.google.dagger:hilt-compiler:$hiltVersion")

    // Image Loading - Glide
    val glideVersion = "4.16.0"
    implementation("com.github.bumptech.glide:glide:$glideVersion")
    kapt("com.github.bumptech.glide:compiler:$glideVersion")
    
    // SVG Support for Glide
    implementation("com.caverock:androidsvg-aar:1.4")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // SwipeRefreshLayout
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // JSON - Gson
    implementation("com.google.code.gson:gson:2.11.0")  // Updated

    // Logging - Timber
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Browser
    implementation("androidx.browser:browser:1.8.0")  // Updated

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))  // Updated
    implementation("com.google.firebase:firebase-config-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Lottie Animations
    implementation("com.airbnb.android:lottie:6.6.0")  // Updated

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")  // Updated
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")  // Updated
}

kapt {
    correctErrorTypes = true
}

// Version Code Configuration for ABI Splits
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters.find { 
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI 
            }?.identifier
            
            if (abiName != null) {
                val abiVersionCode = when (abiName) {
                    "armeabi-v7a" -> 1
                    "arm64-v8a" -> 2
                    "x86" -> 3
                    "x86_64" -> 4
                    else -> 0
                }
                val newVersionCode = (variant.outputs.first().versionCode.orNull ?: 1) * 10 + abiVersionCode
                output.versionCode.set(newVersionCode)
            }
        }
    }
}

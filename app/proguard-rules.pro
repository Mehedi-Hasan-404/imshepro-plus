# ========== BASIC OBFUSCATION ==========

# Keep Kotlin metadata
-keepclassmembers class kotlin.Metadata { *; }

# Keep Hilt/Dagger
-keep class dagger.hilt.** { *; }
-keep class dagger.hilt.internal.** { *; }
-keep class javax.inject.** { *; }

# Keep data models
-keepclassmembers class com.livetvpro.data.models.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep classes that use native code
-keep class com.livetvpro.data.repository.NativeDataRepository {
    <init>(...);
    native <methods>;
}

-keep class com.livetvpro.utils.NativeListenerManager {
    <init>(...);
    native <methods>;
}

# ========== OBFUSCATION SETTINGS ==========

# Make code harder to understand when decompiled
-repackageclasses ''
-allowaccessmodification
-overloadaggressively

# Optimization
-optimizationpasses 5

# Remove debugging info
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Keep only essential annotations
-keepattributes *Annotation*,Signature,Exceptions

# ========== ANDROID COMPONENTS ==========

# Keep Activities, Services, Receivers
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application

# Keep Fragment constructors
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    public <init>();
}

# Keep View constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ========== LIBRARIES ==========

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Retrofit
-keepattributes Signature
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ========== REMOVE LOGGING ==========

# Remove Timber logging in release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** v(...);
}

# Remove Android Log
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# ========== WARNINGS ==========

-dontwarn **
-ignorewarnings

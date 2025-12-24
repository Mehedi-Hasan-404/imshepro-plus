# Keep Kotlin metadata
-keepclassmembers class kotlin.Metadata { *; }

# Keep Hilt / Dagger generated classes used at runtime
-keep class dagger.hilt.** { *; }
-keep class dagger.hilt.internal.** { *; }

# Keep model classes used by Gson / Firebase serialization
-keepclassmembers class com.livetvpro.data.models.** { *; }
-keepclassmembers class com.livetvpro.** { *; }

# Remove logging in release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# ========== SECURITY ENHANCEMENTS ==========

# Obfuscate all package names and class names
-repackageclasses 'o'
-allowaccessmodification

# Keep native methods (required for JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Aggressive obfuscation
-optimizationpasses 5
-overloadaggressively

# Obfuscate NetworkModule but keep JNI method
-keep class com.livetvpro.di.NetworkModule {
    native <methods>;
}
-keepclassmembers class com.livetvpro.di.NetworkModule {
    native <methods>;
}

# Remove source file names and line numbers
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Obfuscate method names
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# String encryption (makes strings harder to read)
-adaptclassstrings

# Remove all metadata except what's needed for the app to work
-dontnote **
-dontwarn **
-ignorewarnings

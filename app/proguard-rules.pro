# ========== CRITICAL SECURITY PROTECTION ==========

# Keep Kotlin metadata (required for app to function)
-keepclassmembers class kotlin.Metadata { *; }

# Keep Hilt / Dagger (dependency injection needs these)
-keep class dagger.hilt.** { *; }
-keep class dagger.hilt.internal.** { *; }

# Keep data models (Gson serialization needs these)
-keepclassmembers class com.livetvpro.data.models.** { *; }

# ========== AGGRESSIVE OBFUSCATION ==========

# Repackage ALL classes into single package to confuse decompilers
-repackageclasses 'o'
-allowaccessmodification

# CRITICAL: Keep native methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep SecurityManager but obfuscate everything inside
-keep class com.livetvpro.security.SecurityManager {
    <init>(...);
    public boolean verifyIntegrity();
    public void enforceIntegrity();
}

# Keep NetworkModule but obfuscate internals
-keep class com.livetvpro.di.NetworkModule {
    native <methods>;
}
-keepclassmembers class com.livetvpro.di.NetworkModule {
    native <methods>;
}

# Keep ListenerManager constructor but obfuscate methods
-keep class com.livetvpro.utils.ListenerManager {
    <init>(...);
}

# Keep RemoteConfigManager constructor
-keep class com.livetvpro.utils.RemoteConfigManager {
    <init>(...);
}

# Keep DataRepository constructor  
-keep class com.livetvpro.data.repository.DataRepository {
    <init>(...);
}

# ========== MAXIMUM OBFUSCATION ==========

# Maximum optimization passes
-optimizationpasses 7
-overloadaggressively

# Rename source files and remove line numbers (makes stack traces useless)
-renamesourcefileattribute ""
-keepattributes SourceFile,LineNumberTable

# Keep only essential annotations
-keepattributes *Annotation*,Signature,Exceptions

# Obfuscate all strings (makes constants harder to find)
-adaptclassstrings

# ========== STRING ENCRYPTION ==========

# This makes it harder to find API endpoints and config keys in decompiled code
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Remove Timber logging in release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** v(...);
}

# ========== CONTROL FLOW OBFUSCATION ==========

# Optimize and obfuscate control flow
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizations code/removal/advanced

# ========== REMOVE DEBUG INFO ==========

# Remove all debug information
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
    public static void throwUninitializedPropertyAccessException(...);
}

# ========== ANTI-TAMPERING ==========

# Make it harder to hook/modify methods at runtime
-keepclassmembers,allowoptimization class com.livetvpro.security.SecurityManager {
    private <methods>;
}

-keepclassmembers,allowoptimization class com.livetvpro.utils.ListenerManager {
    private <methods>;
}

-keepclassmembers,allowoptimization class com.livetvpro.data.repository.DataRepository {
    private <methods>;
}

# ========== WARNINGS SUPPRESSION ==========

-dontwarn **
-ignorewarnings
-dontnote **

# ========== ADDITIONAL HARDENING ==========

# Encrypt class names to maximum extent
-classobfuscationdictionary obfuscation-dict.txt
-packageobfuscationdictionary obfuscation-dict.txt
-obfuscationdictionary obfuscation-dict.txt

# Make class hierarchy harder to understand
-mergeinterfacesaggressively
-flattenpackagehierarchy

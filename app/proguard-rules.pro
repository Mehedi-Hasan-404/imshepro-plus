# Keep Kotlin metadata
-keepclassmembers class kotlin.Metadata { *; }

# Keep Hilt / Dagger generated classes used at runtime
-keep class dagger.hilt.** { *; }
-keep class dagger.hilt.internal.** { *; }

# Keep model classes used by Gson / Firebase serialization
-keepclassmembers class com.livetvpro.data.models.** { *; }
-keepclassmembers class com.livetvpro.** { *; }

-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

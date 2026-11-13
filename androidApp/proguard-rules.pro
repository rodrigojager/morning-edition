# R8 rules for the release build (minified, optimized, resources shrunk).
# Libraries ship most of their own rules; these keep what is reached from native code or by generated serializers.

# QuickJS (the collector engine): its native library calls back into these classes by name through JNI.
-keep class com.dokar.quickjs.** { *; }

# Bundled SQLite: the native driver is bound through JNI.
-keep class androidx.sqlite.driver.bundled.** { *; }

# kotlinx.serialization: keep the generated serializers of the app's @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class dobra.** {
    static **$Companion Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class dobra.**$$serializer { *; }
-keepclassmembers class dobra.** {
    *** Companion;
}
-keepclasseswithmembers class dobra.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager instantiates the job by its class name.
-keep class dobra.app.work.EditionWorker { <init>(...); }

# ---------------------------------------------------------------------------
# Tara Core release rules.
#
# R8 is on for release. Everything below is a class R8 cannot see being used,
# because the reference is by name at runtime rather than in bytecode.
# ---------------------------------------------------------------------------

# --- AIDL contract -------------------------------------------------------
# Parcelables are reconstructed reflectively by the framework, and the Stub/Proxy
# pair is looked up by interface descriptor string.
-keep class dev.taracore.api.** { *; }
-keep interface dev.taracore.api.** { *; }

# --- JNI -----------------------------------------------------------------
# Called from C++ by exact name and signature. Renaming any of these turns into a
# NoSuchMethodError at the first inference request, not at build time.
-keepclasseswithmembernames class dev.taracore.engine.LlamaEngine {
    native <methods>;
}
-keep class dev.taracore.engine.NativeLoadResult { <init>(...); }
-keep class dev.taracore.engine.GenStats { <init>(...); }
-keep interface dev.taracore.engine.TokenListener { *; }

# --- Room ----------------------------------------------------------------
-keep class dev.taracore.service.model.ModelEntity { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- kotlinx.serialization ----------------------------------------------
# Serializers are resolved through a synthetic Companion.serializer() method.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.taracore.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.taracore.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor ----------------------------------------------------------------
# Ktor resolves engines through java.util.ServiceLoader and reflects over
# attribute keys; it also references JVM-only classes that do not exist on Android.
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.server.engine.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**

# --- OkHttp / Okio -------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- WorkManager ---------------------------------------------------------
-keep class dev.taracore.service.model.ModelDownloadWorker { <init>(...); }

# --- App entry points ----------------------------------------------------
# Instantiated by the system from a manifest name string.
-keep class dev.taracore.app.TaraCoreApplication { *; }
-keep class dev.taracore.app.MainActivity { *; }
-keep class dev.taracore.app.widget.PerformanceWidget { *; }
-keep class dev.taracore.service.TaraCoreService { *; }
-keep class dev.taracore.service.BootReceiver { *; }

# --- Crash readability ---------------------------------------------------
# Keep line numbers so a Play Vitals stack trace can be deobfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

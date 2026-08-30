-keep class dev.taracore.service.TaraCoreService { *; }
# kotlinx.serialization generates serializers reflectively referenced by name.
-keepclassmembers class dev.taracore.service.http.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

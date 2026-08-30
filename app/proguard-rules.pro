# Room, WorkManager and kotlinx.serialization all resolve classes by name.
-keep class dev.taracore.service.model.** { *; }
-keep class dev.taracore.service.http.** { *; }
-keepclassmembers class ** {
    @androidx.room.* <methods>;
}

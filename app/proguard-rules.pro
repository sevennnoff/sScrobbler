# Protect 100% of sScrobbler application classes and members
-keep class com.sscrobbler.app.** { *; }
-keepclassmembers class com.sscrobbler.app.** { *; }

# Keep AndroidX Lifecycle & ViewModel
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.ViewModelProvider$Factory { *; }

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * {
    @androidx.room.* *;
}

# Keep Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses,Signature
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    static kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * extends kotlinx.serialization.KSerializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Keep WorkManager
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn coil.**
-keep class coil.** { *; }

# Compose runtime
-keepattributes EnclosingMethod,InnerClasses

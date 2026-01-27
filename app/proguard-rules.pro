# ==============================================================================
# NetSpeed Lite - Essential Keep Rules
# ==============================================================================

# 1. Keep the Service and Receiver so the system can find them
# This prevents the app from crashing when starting the background monitor
-keep class com.krishna.netspeedlite.SpeedService { *; }
-keep class com.krishna.netspeedlite.BootReceiver { *; }

# 2. Keep the MainActivity (Standard entry point)
-keep class com.krishna.netspeedlite.MainActivity { *; }

# 3. Keep Notification and Icon classes
# Required because the speed icon is generated dynamically at runtime
-keep class androidx.core.app.NotificationCompat** { *; }
-keep class androidx.core.graphics.drawable.IconCompat** { *; }

# 4. Preserve debugging information for Play Store crash reports
-keepattributes SourceFile, LineNumberTable

# 5. Prevent shrinking of the special property required for Android 14+
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 6. Optimization: Allow aggressive shrinking but keep these naming patterns
-dontwarn java.nio.file.**
-dontnote okhttp3.**

# ==============================================================================
# PRODUCTION OPTIMIZATION - Reduces False Positive Virus Detections
# ==============================================================================

# 7. Strip ALL Log calls from release builds (important for security scanners)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# 8. Remove debug assertions
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
}
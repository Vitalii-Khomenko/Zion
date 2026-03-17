# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ========== MEDIA3 / EXOPLAYER RULES ==========
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keepnames class androidx.media3.session.MediaSession
-keepnames class androidx.media3.common.Player
-keepnames class androidx.media3.exoplayer.ExoPlayer

# ========== LIFECYCLE RULES ==========
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class androidx.lifecycle.* { *; }

# ========== COROUTINES RULES ==========
-keepnames class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }

# ========== COMPOSE RULES ==========
-keep class androidx.compose.** { *; }
-keepnames class androidx.compose.runtime.Composer

# ========== APP-SPECIFIC RULES ==========
-keep class com.example.zion.model.** { *; }
-keep class com.example.zion.MusicViewModel { *; }
-keep class com.example.zion.PlaybackService { *; }
-keep class com.example.zion.MainActivity { *; }
-keep class com.example.zion.util.CueParser { *; }

# ========== GUAVA RULES ==========
-keep class com.google.common.util.concurrent.** { *; }
-keep interface com.google.common.util.concurrent.** { *; }

# ========== KOTLIN RULES ==========
-keep class kotlin.** { *; }
-keep class kotlin.reflect.** { *; }
-keepclassmembers class * {
    kotlin.jvm.internal.NoWhenBranchMatchedException <init>(...);
}

# ========== GENERAL RULES ==========
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable

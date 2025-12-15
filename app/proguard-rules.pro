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

# --- VLC / LibVLC (org.videolan.android:libvlc-all) ---
# Release build uses R8; LibVLC relies on JNI + reflection. Keep aggressively to avoid blank player in release.
-dontwarn org.videolan.**
-keep class org.videolan.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
	native <methods>;
}

# Keep LibVLC entry points explicitly
-keep class org.videolan.libvlc.LibVLC { *; }
-keep class org.videolan.libvlc.Media { *; }
-keep class org.videolan.libvlc.MediaPlayer { *; }
-keep class org.videolan.libvlc.util.VLCVideoLayout { *; }
# Keep R8 focused on removing unreachable code. Avoid bytecode transformations
# and name obfuscation while this build is being compatibility-tested.
-dontoptimize
-dontobfuscate

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

# Preserve source file and line numbers for readable release crash stack traces
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Moshi @JsonClass models and KSP-generated JsonAdapters
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <fields>;
}
-keep class com.nitrous.tessellate.settings.GameSettings { *; }
-keep class com.nitrous.tessellate.ui.UiLayoutConfig { *; }
-keep class com.nitrous.tessellate.ui.HotbarUiLayout { *; }
-keep class com.nitrous.tessellate.ui.UiUvRect { *; }

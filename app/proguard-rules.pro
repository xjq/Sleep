# Personal-use app ships without minify by default.
# Keep Gson DTOs in case minify is later enabled.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.self.sleepcollect.data.remote.** { *; }
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

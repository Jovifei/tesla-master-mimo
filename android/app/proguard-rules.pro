# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Moshi
-keep class com.matelink.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

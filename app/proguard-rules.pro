# ProGuard / R8 rules for Cleanbook Full Offline APK
# Keeps all JNI bridges (llama.cpp + ONNX Runtime), Room DB, PDFBox, and Jsoup intact
# while allowing R8 to strip unused Kotlin/Compose/Material Icons code & resources.

-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# ------------------------------------------------------------------------------
# 1. Preserve ALL native JNI methods across every class
# ------------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ------------------------------------------------------------------------------
# 2. llama.cpp Kotlin/JNI wrapper (org.codeshipping:llama-kotlin-android)
# ------------------------------------------------------------------------------
-keep class org.codeshipping.llamakotlin.** { *; }
-dontwarn org.codeshipping.llamakotlin.**

# ------------------------------------------------------------------------------
# 3. ONNX Runtime Android JNI wrapper (com.microsoft.onnxruntime:onnxruntime-android)
# ------------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ------------------------------------------------------------------------------
# 4. Room Database, Entities, DAOs
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep class com.example.data.** { *; }

# ------------------------------------------------------------------------------
# 5. PDFBox Android & FontBox (uses reflection for encodings/cmaps/fonts)
# ------------------------------------------------------------------------------
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn org.bouncycastle.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**

# ------------------------------------------------------------------------------
# 6. Jsoup & Kotlin Coroutines
# ------------------------------------------------------------------------------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep JNI bridge classes for sherpa-onnx
-keep class com.k2fsa.sherpa.onnx.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.forge.audiobookforge.**$$serializer { *; }
-keepclassmembers class com.forge.audiobookforge.** { *** Companion; }
-keepclasseswithmembers class com.forge.audiobookforge.** { kotlinx.serialization.KSerializer serializer(...); }

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

# PDFBox: font/resource loading uses reflection internally
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.brotli.**
-dontwarn java.awt.**

# JP2/JPEG2000: optional image-only path in pdfbox; text extraction never reaches it
-dontwarn com.gemalto.jp2.**

# Project-specific R8 rules for the minified release build.
#
# Retrofit, OkHttp and MMKV ship their own consumer rules inside their AAR/JAR
# artifacts, so only the rules R8 cannot infer are listed here.

# Release loader 通过 payload.bin 的类名和无参构造器恢复原入口。
-keep class moe.yumeyuka.yumehub.App { *; }
-keep class androidx.core.app.CoreComponentFactory { *; }

# Annotations and generic signatures are read reflectively by Retrofit and by
# kotlinx.serialization's descriptor lookup.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

# kotlinx.serialization: generated serializers are looked up reflectively, so
# keep them together with the Companion.serializer() bridges they are reached by.
-keep,includedescriptorclasses class moe.yumeyuka.yumehub.**$$serializer { *; }
-keepclassmembers class moe.yumeyuka.yumehub.** {
    *** Companion;
}
-keepclasseswithmembers class moe.yumeyuka.yumehub.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

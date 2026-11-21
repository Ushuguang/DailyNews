# 保留序列化模型
-keepclassmembers class com.dailypush.news.data.** { *; }
-keep,includedescriptorclasses class com.dailypush.news.**$$serializer { *; }
-keepclassmembers class com.dailypush.news.** {
    *** Companion;
}
-keepclasseswithmembers class com.dailypush.news.** {
    kotlinx.serialization.KSerializer serializer(...);
}

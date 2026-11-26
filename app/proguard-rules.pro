-keep class com.example.mediahub.** { *; }
-keep class com.dokar.quickjs.** { *; }
-keep class org.jsoup.** { *; }
-keep class org.seimicrawler.xpath.** { *; }
-keep class com.jayway.jsonpath.** { *; }
-keep class net.minidev.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.antlr.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepattributes *Annotation*, Signature, InnerClasses

# Jayway JsonPath 的可选 JSON 提供者（本项目只用默认的 json-smart）
-dontwarn com.google.gson.**
-dontwarn org.apache.tapestry5.json.**
-dontwarn com.fasterxml.jackson.**
-dontwarn jakarta.json.**
-dontwarn javax.json.**
-dontwarn org.codehaus.jettison.**
-dontwarn org.json.**
-dontwarn com.jayway.jsonpath.spi.json.**
-dontwarn com.jayway.jsonpath.spi.mapper.**
# JsoupXpath / json-smart 的其他可选依赖
-dontwarn org.apache.commons.**
-dontwarn net.minidev.asm.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.jetbrains.annotations.**

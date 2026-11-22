# WebView 通过运行时注解和方法名发现 JS 桥，仅保留本模块被标注的方法
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class moe.yumeyuka.yumehub.feature.markdown.** {
    @android.webkit.JavascriptInterface <methods>;
}

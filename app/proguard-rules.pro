# Keep JavaScript interface methods
-keepclassmembers class com.zellijconnect.app.ClipboardBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep BuildConfig
-keep class com.zellijconnect.app.BuildConfig { *; }

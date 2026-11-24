-keepattributes JavascriptInterface
-keepattributes *Annotation*

-keepclassmembers class pk.cinejoy.tv.MainActivity$CineJoyNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn androidx.core.splashscreen.**

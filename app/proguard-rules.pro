-keepattributes JavascriptInterface
-keepattributes *Annotation*, Signature

-keepclassmembers class pk.cinejoy.tv.MainActivity$CineJoyNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class androidx.media3.datasource.okhttp.** { *; }
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.ui.** { *; }

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**
-dontwarn androidx.media3.datasource.okhttp.**
-dontwarn androidx.core.splashscreen.**

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

-assumenosideeffects class java.io.PrintStream {
    public void print(...);
    public void println(...);
}

-assumenosideeffects class kotlin.io.ConsoleKt {
    public static final void print(...);
    public static final void println(...);
}

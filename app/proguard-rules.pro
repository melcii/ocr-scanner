# Methods called from JavaScript must keep their names.
-keepclassmembers class org.localocr.scanner.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

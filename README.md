# OCR Scanner (Android, fully offline)

Image-to-text scanner. Tesseract OCR (WebAssembly) + English & Turkish models ship inside the APK;
the app refuses every network request, so images never leave the phone.

Features: in-app camera with flash, gallery import, rotate, English / Türkçe / both, editable result, copy & share.
Share any image to "OCR Scanner" from another app (e.g. the screenshot preview) to scan it instantly.
Optional "Screenshot alerts" (off by default): a notification after each screenshot offering to scan it. It uses Android's content-change job trigger, so nothing runs in the background between screenshots; it needs photo + notification permission.

## Get the APK — pick one
1. **GitHub (no installs):** push this folder to a GitHub repo → Actions tab → "Build APK" runs automatically →
   download `OCRScanner-apk` from the run (~10 MB APK inside the zip).
2. **Android Studio:** File → Open this folder → Build → Build APK(s).
3. **Command line with the SDK:** `./build-local.sh` (needs build-tools + platform 37).

Install: copy the APK to the phone and open it (allow "install unknown apps" when asked). Android 7.0+, built for Android 17 (API 37).

## Layout
- `app/src/main/assets/www/index.html` — the whole UI + OCR logic
- `app/src/main/java/.../MainActivity.java` — WebView shell: serves bundled files, blocks network, camera/gallery/copy/share
- To add a language: drop `<code>.traineddata.gz` (from npm `@tesseract.js-data/<code>`, folder `4.0.0_best_int`) into `www/lang/` and add a chip in `index.html`.

#!/usr/bin/env bash
# Build the APK without Gradle, using only Android SDK build-tools.
# Needs: JDK 17+, ANDROID_HOME with build-tools/<ver> and platforms/android-37 installed.
set -euo pipefail
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
JAR="$ANDROID_HOME/platforms/android-37/android.jar"
OUT=build; rm -rf $OUT; mkdir -p $OUT/classes $OUT/dex
"$BT/aapt2" compile --dir app/src/main/res -o $OUT/res.zip
sed 's#<manifest #<manifest package="org.localocr.scanner" #' app/src/main/AndroidManifest.xml > $OUT/AndroidManifest.xml
"$BT/aapt2" link -o $OUT/base.apk -I "$JAR" --manifest $OUT/AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 37 \
  --version-code 3 --version-name 1.2 -A app/src/main/assets -0 gz $OUT/res.zip
javac -Xlint:-options --release 8 -classpath "$JAR" -d $OUT/classes app/src/main/java/org/localocr/scanner/*.java 2>/dev/null \
 || javac -source 8 -target 8 -bootclasspath "$JAR" -d $OUT/classes app/src/main/java/org/localocr/scanner/*.java
"$BT/d8" --min-api 24 --lib "$JAR" --output $OUT/dex $(find $OUT/classes -name '*.class')
(cd $OUT/dex && zip -q ../base.apk classes.dex)
"$BT/zipalign" -f 4 $OUT/base.apk $OUT/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias ocr -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=OCR Scanner"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out OCRScanner.apk $OUT/aligned.apk
echo "Built: $(pwd)/OCRScanner.apk"

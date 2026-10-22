#!/usr/bin/env bash
# 안드로이드 SDK/Gradle 없이 음성 노트 APK를 만든다.
# 필요한 것(환경 변수로 경로 지정):
#   AAPT2        aapt2 실행 파일
#   ANDROID_JAR  android.jar (리소스 포함, 예: SDK platforms/android-35/android.jar)
#   DX_JAR       dx.jar (com.jakewharton.android.repackaged:dalvik-dx)
#   APKSIG_JAR   apksig.jar (com.android.tools.build:apksig)
#   KEYSTORE     서명 키(PKCS12), KEY_PASS 암호, KEY_ALIAS 별칭 — 키가 없으면 새로 만든다
# 결과: android/out/VoiceNote.apk
set -euo pipefail
cd "$(dirname "$0")"
: "${AAPT2:?}" "${ANDROID_JAR:?}" "${DX_JAR:?}" "${APKSIG_JAR:?}"
KEYSTORE=${KEYSTORE:-voicenote-release.p12}
KEY_PASS=${KEY_PASS:-voicenote}
KEY_ALIAS=${KEY_ALIAS:-voicenote}
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}

B=build
rm -rf "$B" && mkdir -p "$B/res" "$B/assets/www" "$B/classes" out

# 1. 리소스와 웹앱
cp -r res/. "$B/res/"
python3 tools/make_icons.py "$B/res"
cp ../index.html "$B/assets/www/"
"$AAPT2" compile --dir "$B/res" -o "$B/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A "$B/assets" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o "$B/base.apk" "$B/res.zip"

# 2. 자바 → dex
javac -nowarn -Xlint:-options --release 8 -cp "$ANDROID_JAR" -d "$B/classes" \
  $(find src -name '*.java')
java -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=24 --output="$B/classes.dex" "$B/classes"
(cd "$B" && cp base.apk unsigned.apk && zip -q -j unsigned.apk classes.dex)
python3 tools/zipalign.py "$B/unsigned.apk" "$B/aligned.apk"

# 3. 서명
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$KEY_PASS" -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=VoiceNote" 2>/dev/null
  echo "새 서명 키를 만들었습니다: $KEYSTORE (앱을 업데이트하려면 이 키를 보관하세요)"
fi
javac -nowarn -cp "$APKSIG_JAR" -d "$B/tools" tools/Sign.java
# 오래된 apksig는 JDK 내부 클래스를 쓰므로 9 이상에서는 열어 줘야 한다
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
  --add-exports java.base/sun.security.util=ALL-UNNAMED -cp "$APKSIG_JAR:$B/tools" Sign "$KEYSTORE" "$KEY_PASS" "$KEY_ALIAS" "$B/aligned.apk" out/VoiceNote.apk
ls -la out/VoiceNote.apk

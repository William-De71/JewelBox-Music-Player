#!/usr/bin/env bash
# Build the debug APK, install it on the USB-connected phone and launch the app.
#   ./run-phone.sh          build + install + launch
#   ./run-phone.sh --logs   same, then tail the app's logcat (Ctrl-C to stop)
#
# The debug build uses its own application id (com.jewelbox.player.debug), so it
# installs alongside the release app ("JewelBox dev" vs "JewelBox" launchers).
set -euo pipefail
cd "$(dirname "$0")"

# JDK: Gradle/AGP need 17-21; default to Android Studio's bundled JBR (the system
# JDK 25 is too new). Override by exporting JAVA_HOME before calling the script.
JBR=/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/jbr
if [ -z "${JAVA_HOME:-}" ] && [ -x "$JBR/bin/java" ]; then
  export JAVA_HOME="$JBR"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.jewelbox.player.debug

# One connected device required.
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "❌ Aucun téléphone détecté. Branche-le en USB avec le débogage activé," >&2
  echo "   puis vérifie avec : $ADB devices" >&2
  exit 1
fi

echo "📦 Build + installation…"
./gradlew :app:installDebug

echo "🚀 Lancement…"
"$ADB" shell am start -n "$PKG/com.jewelbox.player.MainActivity" >/dev/null

if [ "${1:-}" = "--logs" ]; then
  echo "📋 Logs (Ctrl-C pour quitter) :"
  "$ADB" logcat -v brief | grep -iE "jewelbox|AndroidRuntime|FATAL"
fi

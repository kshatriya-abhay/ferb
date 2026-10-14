#!/usr/bin/env bash
# Fails if an APK can use the network: an INTERNET permission, or networking calls in its native code.
#   scripts/check-offline.sh [app.apk]   (default: the debug APK)
set -euo pipefail
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
AAPT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
if "$AAPT" dump permissions "$APK" | grep -q 'android.permission.INTERNET'; then echo "FAIL: INTERNET permission"; exit 1; fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" 'lib/*' -d "$TMP"
NM=$(ls -d "$ANDROID_HOME"/ndk/*/ | sort -V | tail -1)toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm
for so in "$TMP"/lib/*/*.so; do
  if "$NM" -D --undefined-only "$so" | grep -Ew 'socket|connect|getaddrinfo|gethostbyname|sendto|recvfrom'; then echo "FAIL: networking symbols in $so"; exit 1; fi
done
echo "Offline: no INTERNET permission, no networking calls in native code ($(basename "$APK"))."

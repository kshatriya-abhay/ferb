#!/usr/bin/env bash
# Fails if an APK can use the network: an INTERNET permission, or networking calls in its native code.
#   scripts/check-offline.sh [app.apk]   (default: the debug APK)
set -euo pipefail
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
AAPT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
# Fails closed (review P2-164): aapt2 that can't run, or a dump without the package, is a failure,
# not an APK with no INTERNET. As a pipeline in an if, a failed aapt2 passed.
PERMS=$("$AAPT" dump permissions "$APK") || { echo "FAIL: aapt2 couldn't read $APK's permissions ($AAPT)"; exit 1; }
grep -q '^package:' <<< "$PERMS" || { echo "FAIL: aapt2 printed no package line for $APK"; exit 1; }
if grep -q 'android.permission.INTERNET' <<< "$PERMS"; then echo "FAIL: INTERNET permission"; exit 1; fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" 'lib/*' -d "$TMP"
NM=$(ls -d "$ANDROID_HOME"/ndk/*/ | sort -V | tail -1)toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm
for so in "$TMP"/lib/*/*.so; do
  SYMS=$("$NM" -D --undefined-only "$so") || { echo "FAIL: llvm-nm couldn't read $so ($NM)"; exit 1; }
  if grep -Ew 'socket|connect|getaddrinfo|gethostbyname|sendto|recvfrom' <<< "$SYMS"; then echo "FAIL: networking symbols in $so"; exit 1; fi
done
echo "Offline: no INTERNET permission, no networking calls in native code ($(basename "$APK"))."

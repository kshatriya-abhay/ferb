#!/usr/bin/env bash
# Checks a minified (R8) APK for what reflection needs and R8 can't see: every class the manifest
# names for creating by reflection must be in the dex with a public no-argument constructor.
#   - ML Kit's component registrars (meta-data "com.google.firebase.components:<class>"): without
#     one, Gemini Nano's status check throws and Ferb says it can't run (release 0.1, 2026-10-30).
# Usage: scripts/check-release.sh APK   (mise run smoke and scripts/release.sh run it)
set -euo pipefail
APK=${1:?usage: check-release.sh APK}
SDK=${ANDROID_HOME/#\~/$HOME}
TOOLS=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# The registrars the manifest names.
"$TOOLS/aapt2" dump xmltree --file AndroidManifest.xml "$APK" \
  | sed -n 's/.*android:name(0x01010003)="com\.google\.firebase\.components:\([^"]*\)".*/\1/p' | sort -u > "$WORK/registrars"
[ -s "$WORK/registrars" ] || { echo "FAIL: no ML Kit component registrars in the manifest of $APK"; exit 1; }

unzip -q -o "$APK" 'classes*.dex' -d "$WORK"
for dex in "$WORK"/classes*.dex; do "$TOOLS/dexdump" "$dex" 2>/dev/null; done > "$WORK/dex.txt"

fail=0
while read -r class; do
  descriptor="L${class//.//};"
  # The class's block, up to the next class: a public (0x0001) <init> with type ()V among its direct methods.
  if awk -v d="$descriptor" '
      $0 ~ /Class descriptor/ { inside = index($0, "\x27" d "\x27") > 0 }
      inside && /name          : \x27<init>\x27/ { init = 1; next }
      inside && init && /type          : \x27\(\)V\x27/ { ctor = 1 }
      inside && init && /access        :/ { if (ctor && $0 ~ /PUBLIC/) found = 1; init = 0; ctor = 0 }
      END { exit found ? 0 : 1 }' "$WORK/dex.txt"; then
    echo "Kept: $class with its public no-argument constructor"
  else
    echo "FAIL: $class (named in the manifest, created by reflection) has no public no-argument constructor in $APK: check app/proguard-rules.pro"
    fail=1
  fi
done < "$WORK/registrars"
exit $fail

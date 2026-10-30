#!/usr/bin/env bash
# Builds, signs and checks a release APK into dist/: mise run release. Publishing is separate
# (scripts/publish.sh), and only ever run by the maintainer.
#
# Signing is local, with the maintainer's key; nothing secret goes into the repo, a file or a log:
#   FERB_RELEASE_KEYSTORE        the keystore (default ~/.android/keystores/release-key-abhay.jks)
#   FERB_RELEASE_KEY_ALIAS       the key's alias
#   FERB_RELEASE_STORE_PASSWORD  the keystore's password
#   FERB_RELEASE_KEY_PASSWORD    the key's password, when not the keystore's
# The alias and keystore password are asked for when not set (the password without echo). Gradle builds the APK unsigned, so it never
# sees them; apksigner reads the passwords from the environment, not its command line. The same
# names and checks are used by Ferb Link's release, so both apps carry the same certificate,
# whose SHA-256 is committed in release-cert.sha256 (public, like any APK's certificate).
# FERB_RELEASE_CERT_FILE, for tests with a throwaway key only, reads another file instead.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYSTORE=${FERB_RELEASE_KEYSTORE:-$HOME/.android/keystores/release-key-abhay.jks}
[ -f "$KEYSTORE" ] || { echo "No keystore at $KEYSTORE (set FERB_RELEASE_KEYSTORE)"; exit 1; }
if [ -z "${FERB_RELEASE_KEY_ALIAS:-}" ]; then read -r -p "Key alias: " FERB_RELEASE_KEY_ALIAS; fi
if [ -z "${FERB_RELEASE_STORE_PASSWORD:-}" ]; then read -r -s -p "Keystore password: " FERB_RELEASE_STORE_PASSWORD; echo; fi
# The key's password is the keystore's (PKCS12 keystores need them equal) unless set apart.
export FERB_RELEASE_STORE_PASSWORD FERB_RELEASE_KEY_PASSWORD="${FERB_RELEASE_KEY_PASSWORD:-$FERB_RELEASE_STORE_PASSWORD}"

CERT_FILE=${FERB_RELEASE_CERT_FILE:-release-cert.sha256}
TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
UNSIGNED=app/build/outputs/apk/release/app-release-unsigned.apk
OUT=dist/ferb-$VERSION.apk

./gradlew --no-daemon -q assembleRelease
mkdir -p dist
rm -f "$OUT" "$OUT.sha256" dist/aligned.apk
"$TOOLS/zipalign" -P 16 -f 4 "$UNSIGNED" dist/aligned.apk
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$FERB_RELEASE_KEY_ALIAS" \
  --ks-pass env:FERB_RELEASE_STORE_PASSWORD --key-pass env:FERB_RELEASE_KEY_PASSWORD --out "$OUT" dist/aligned.apk
rm -f dist/aligned.apk "$OUT.idsig"

CERT=$("$TOOLS/apksigner" verify --print-certs "$OUT" | sed -n 's/.*[Ss]igner.*certificate SHA-256 digest: //p' | head -1)
[ -n "$CERT" ] || { echo "FAIL: apksigner found no signer"; exit 1; }
if [ -f "$CERT_FILE" ]; then
  [ "$CERT" = "$(tr -d ' \n' < "$CERT_FILE")" ] || { echo "FAIL: signed with $CERT, not the release certificate in $CERT_FILE"; rm -f "$OUT"; exit 1; }
  echo "Certificate: $CERT (matches $CERT_FILE)"
else
  echo "$CERT" > "$CERT_FILE"
  echo "Certificate: $CERT"
  echo "First release: wrote $CERT_FILE. Check it's the release key's, commit it (Ferb Link's must hold the same line), and run this again."
  rm -f "$OUT"; exit 1
fi

scripts/check-offline.sh "$OUT"
scripts/check-release.sh "$OUT"
(cd dist && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "Built $OUT ($(du -h "$OUT" | cut -f1)), SHA-256 in $OUT.sha256"

#!/usr/bin/env bash
# Publishes dist/ferb-<version>.apk as a GitHub release: mise run release:publish. Run by the
# maintainer only, after mise run release. Refuses an APK whose certificate isn't the release one,
# or that wasn't built from HEAD (release.sh's .commit); the release's tag is put on that commit.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -t 0 ] || { echo "Publishing needs a person at a terminal."; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit first: the tree isn't clean."; exit 1; }
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse @{u})" ] || { echo "Push first: main isn't the same as origin/main."; exit 1; }
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
APK=dist/ferb-$VERSION.apk
[ -f "$APK" ] || { echo "No $APK: run mise run release first"; exit 1; }
BUILT=$(cat "dist/ferb-$VERSION.commit" 2>/dev/null || true)
[ "$BUILT" = "$(git rev-parse HEAD)" ] || { echo "$APK was built from ${BUILT:-an unknown commit}, not HEAD $(git rev-parse HEAD): check out that commit, or run mise run release again"; exit 1; }
[ -f release-cert.sha256 ] || { echo "No release-cert.sha256: commit the release certificate first"; exit 1; }
TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)
CERT=$("$TOOLS/apksigner" verify --print-certs "$APK" | sed -n 's/.*[Ss]igner.*certificate SHA-256 digest: //p' | head -1)
[ "$CERT" = "$(tr -d ' \n' < release-cert.sha256)" ] || { echo "FAIL: $APK isn't signed with the release certificate"; exit 1; }
(cd dist && sha256sum -c --quiet "$(basename "$APK").sha256")
if gh release view "v$VERSION" >/dev/null 2>&1; then echo "Release v$VERSION exists already: bump versionName"; exit 1; fi
read -r -p "Publish Ferb v$VERSION to GitHub Releases? Type the version to confirm: " typed
[ "$typed" = "$VERSION" ] || { echo "Not published."; exit 1; }
gh release create "v$VERSION" "$APK" "$APK.sha256" --target "$BUILT" --title "Ferb $VERSION" --generate-notes

#!/usr/bin/env bash
# The release notes for a version: CHANGELOG.md's "## <version>" section, its heading left out, up to
# the next "## " (### subheadings stay in), blank lines trimmed at both ends. Fails if there's none.
#   scripts/changelog-section.sh <version> [CHANGELOG.md]
# Ferb Link's release/changelog-section.sh reads its changelog the same way.
set -euo pipefail
VERSION=${1:?version}
FILE=${2:-$(dirname "$0")/../CHANGELOG.md}
[ -f "$FILE" ] || { echo "No $FILE" >&2; exit 1; }
BODY=$(awk -v h="## $VERSION" '$0==h{f=1;next} f&&/^## /{exit} f' "$FILE" | sed -e '/./,$!d' | tac | sed -e '/./,$!d' | tac)
[ -n "$BODY" ] || { echo "No \"## $VERSION\" section with notes in $FILE: write it before publishing" >&2; exit 1; }
printf '%s\n' "$BODY"

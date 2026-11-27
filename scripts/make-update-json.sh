#!/bin/sh
# Writes update.json for a GitHub release of the sideload build.
# Usage: scripts/make-update-json.sh CHANGELOG_FILE
# CHANGELOG_FILE is Serbian (release-notes/<version>.md); translations next to it as <version>.<lang>.md
# (en, de, sk) go into "changelogs", which the app shows in its own language.
# Reads the version from app/build.gradle.kts and the APK from the release output folder.
set -e
cd "$(dirname "$0")/.."
CHANGELOG_FILE="$1"
[ -f "$CHANGELOG_FILE" ] || { echo "usage: $0 CHANGELOG_FILE" >&2; exit 1; }
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
NAME=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
APK="app/build/outputs/apk/sideload/release/TIMOK-Player-$NAME.apk"
[ -f "$APK" ] || { echo "missing $APK (build and copy the release APK first)" >&2; exit 1; }
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
URL="https://github.com/sasasimonovic88/timok-player/releases/download/v$NAME/TIMOK-Player-$NAME.apk"
OUT="app/build/outputs/apk/sideload/release/update.json"
python3 - "$CODE" "$NAME" "$URL" "$SHA" "$CHANGELOG_FILE" "$OUT" <<'PY'
import glob, json, os, sys
code, name, url, sha, changelog, out = sys.argv[1:]
read = lambda path: open(path, encoding="utf-8").read().strip()
base = changelog[:-3] if changelog.endswith(".md") else changelog
changelogs = {"sr": read(changelog)}
for path in sorted(glob.glob(glob.escape(base) + ".??.md")):
    lang = path[len(base) + 1:-3]
    if lang.isalpha(): changelogs[lang] = read(path)
data = {"versionCode": int(code), "versionName": name, "apkUrl": url, "sha256": sha,
        "changelog": changelogs["sr"], "changelogs": changelogs}
print("changelogs:", ", ".join(changelogs))
open(out, "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
print(out)
PY

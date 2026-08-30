#!/bin/bash
# Update the Android client version. Linux client support has been removed.
set -euo pipefail
cd "$(dirname "$0")"

NEW="${1:-}"
if [ -z "$NEW" ]; then
  read -rp "New Android version (for example 0.3.0): " NEW
fi
NEW="${NEW#v}"
NEW="${NEW#V}"
if ! printf '%s' "$NEW" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "Version must use MAJOR.MINOR.PATCH"
  exit 1
fi

OLD_CODE=$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' android/app/build.gradle.kts | grep -oE '[0-9]+$')
NEW_CODE=$((OLD_CODE + 1))
sed -i -E "s/(versionName[[:space:]]*=[[:space:]]*\")[^\"]+(\")/\1${NEW}\2/" android/app/build.gradle.kts
sed -i -E "s/(versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1${NEW_CODE}/" android/app/build.gradle.kts
sed -i -E "s/(<string name=\"app_version\">)[0-9]+\.[0-9]+\.[0-9]+/\1${NEW}/" android/app/src/main/res/values/strings.xml
echo "Android version: ${NEW}, build: ${NEW_CODE}"

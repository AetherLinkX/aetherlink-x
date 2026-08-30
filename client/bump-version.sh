#!/bin/bash
# bump-version.sh — set the app version everywhere in one go (Android / iOS / Linux).
#
# Touches ONLY the version fields below, each replacement anchored to its exact key:
#   android/app/build.gradle.kts                 versionName + versionCode (+1)
#   android/app/src/main/res/values/strings.xml  <string name="app_version">
#   ios/project.yml                              MARKETING_VERSION + CURRENT_PROJECT_VERSION (+1)
#   ios/App/ContentView.swift                    the About "Version" row
#   linux/build.gradle.kts                       version = "…"
#   linux/src/main/kotlin/com/palazik/vpn/AppDirs.kt  APP_VERSION
#
# Usage: ./bump-version.sh [2.0.3]   (prompts if no argument; leading v/V is fine)
set -euo pipefail
cd "$(dirname "$0")"

NEW="${1:-}"
if [ -z "$NEW" ]; then
  read -rp "New app version (e.g. 2.0.3): " NEW
fi
NEW="${NEW#v}"; NEW="${NEW#V}"

if ! printf '%s' "$NEW" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "❌ Version must look like 2.0.3 (MAJOR.MINOR.PATCH)"; exit 1
fi

# replace <file> <sed-expression> — applies one anchored substitution and shows the result.
replace() {
  local file="$1" expr="$2"
  sed -i -E "$expr" "$file"
  grep -nE "$3" "$file" | sed "s|^|  $file:|"
}

echo "→ Setting version to ${NEW}"

# ── Android ──────────────────────────────────────────────────────────────────
replace android/app/build.gradle.kts \
  "s/(versionName[[:space:]]*=[[:space:]]*\")[^\"]+(\")/\1${NEW}\2/" \
  "versionName"

OLD_CODE=$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' android/app/build.gradle.kts | grep -oE '[0-9]+$')
NEW_CODE=$((OLD_CODE + 1))
replace android/app/build.gradle.kts \
  "s/(versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1${NEW_CODE}/" \
  "versionCode"

# Only the X.Y.Z part is replaced, so a suffix like " Beta" survives.
replace android/app/src/main/res/values/strings.xml \
  "s/(<string name=\"app_version\">)[0-9]+\.[0-9]+\.[0-9]+/\1${NEW}/" \
  "app_version"

# ── iOS ──────────────────────────────────────────────────────────────────────
replace ios/project.yml \
  "s/(MARKETING_VERSION:[[:space:]]*\")[^\"]+(\")/\1${NEW}\2/" \
  "MARKETING_VERSION"

replace ios/project.yml \
  "s/(CURRENT_PROJECT_VERSION:[[:space:]]*\")[0-9]+(\")/\1${NEW_CODE}\2/" \
  "CURRENT_PROJECT_VERSION"

replace ios/App/ContentView.swift \
  "s/(Text\(\"Version\"\); Spacer\(\); Text\(\")[^\"]+(\"\))/\1${NEW}\2/" \
  "Text\(\"Version\"\)"

# ── Linux ────────────────────────────────────────────────────────────────────
replace linux/build.gradle.kts \
  "s/^(version = \")[^\"]+(\")/\1${NEW}\2/" \
  "^version = "

replace linux/src/main/kotlin/com/palazik/vpn/AppDirs.kt \
  "s/(const val APP_VERSION = \")[^\"]+(\")/\1${NEW}\2/" \
  "APP_VERSION"

echo ""
echo "✓ Version set to ${NEW} (build number ${NEW_CODE}) in all three apps."
echo "  Review with:  git diff"
echo "  Then commit, push, and run the Release workflow with tag v${NEW}."

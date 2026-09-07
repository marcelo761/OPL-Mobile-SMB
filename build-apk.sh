#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle não encontrado. Abra o projeto no Android Studio ou use o workflow .github/workflows/android.yml"
  exit 1
fi
gradle :app:assembleDebug
printf '\nAPK: %s\n' "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"

#!/usr/bin/env bash
set -euo pipefail

mkdir -p player-journey
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

for appearance in light dark
do
  if [[ "$appearance" == light ]]; then
    adb shell cmd uimode night no
  else
    adb shell cmd uimode night yes
    adb shell wm size 720x1440
    adb shell wm density 320
    adb shell settings put system font_scale 1.3
  fi
  adb shell am instrument -w -r com.sscrobbler.app.preview.test/androidx.test.runner.AndroidJUnitRunner | tee "player-journey/$appearance.log"
  adb exec-out run-as com.sscrobbler.app.preview tar -C files -cf - screenshots | tar -xf - -C player-journey
  grep -Eq '^OK \(1 test\)' "player-journey/$appearance.log"
done

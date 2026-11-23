#!/usr/bin/env bash
# Installs the debug APK on the emulator, captures screenshots and runs the
# image recognition on two photos from Wikimedia Commons.
set -x
mkdir -p shots
PKG=se.jonsolberg.lamell
adb shell settings put global hide_error_dialogs 1
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
adb install -r app/build/outputs/apk/debug/app-debug.apk
shot() { adb exec-out screencap -p > "shots/$1.png"; }
close() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS; sleep 1; }
launch() { adb shell am start -n $PKG/.MainActivity "$@" >/dev/null; sleep 7; close; }

launch
shot 00-disclaimer
# "Jag förstår"
adb shell input tap 540 1650; sleep 1
shot 01-species
adb shell input swipe 540 1800 540 800 600; sleep 1.5
shot 02-species-scrolled

# Recognition: copy a photo into the app's storage and open it on the identify screen.
for f in testphotos/*.jpg; do
  id=t$(basename "$f" .jpg | tr -dc 'a-zA-Z')
  adb push "$f" /data/local/tmp/$id.jpg
  adb shell run-as $PKG mkdir -p files/photos
  adb shell run-as $PKG cp /data/local/tmp/$id.jpg files/photos/$id.jpg
  adb shell run-as $PKG cp /data/local/tmp/$id.jpg files/photos/${id}_t.jpg
  adb shell am force-stop $PKG
  launch --es testphoto $id
  sleep 25
  shot "10-identify-$id"
  adb shell input swipe 540 1800 540 700 600; sleep 1.5
  shot "11-identify-$id-results"
done

adb shell am force-stop $PKG
adb shell cmd uimode night yes; sleep 2
launch
shot 20-dark
adb shell cmd uimode night no
adb logcat -d | grep -E "AndroidRuntime|FATAL|onnx|Classifier" > shots/log.txt || true
exit 0

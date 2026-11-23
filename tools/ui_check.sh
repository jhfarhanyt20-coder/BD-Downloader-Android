#!/usr/bin/env bash
# Walks through VibeDownloader on the emulator and saves screenshots, the visible
# texts of each screen and the app's log. Never stops on a failed step: every
# step is recorded, so one run shows everything.
#   bash tools/ui_check.sh <app.apk> <output-dir>
APK="$1"; OUT="${2:-shots}"
PKG=com.vibecoder.vibedownloader
mkdir -p "$OUT"
n=0
shot() { n=$((n+1)); f=$(printf "%s/%02d-%s" "$OUT" "$n" "$1"); adb exec-out screencap -p > "$f.png"; python3 tools/ui_tap.py --dump > "$f.txt" 2>&1 || true; echo "screenshot $f"; }
tap() { python3 tools/ui_tap.py "$1" || echo "WARN could not tap: $1"; sleep "${2:-2}"; }
up() { adb shell input swipe 540 1800 540 700 400; sleep 1; }
step() { echo; echo "=== $*"; }
# same quoting as before: the value is one word for the phone's shell (spaces escaped with \ )
share() { adb shell am start -a android.intent.action.SEND -t text/plain   --es android.intent.extra.TEXT "$1" -n "$PKG/.ShareActivity" >/dev/null; }

adb wait-for-device
adb shell settings put global window_animation_scale 1
adb logcat -c
step "install"
adb install -r -g "$APK" 2>&1 | tail -1          # -g grants runtime permissions (notifications)

step "launch"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6; shot launch

step "screens (4 tabs)"
tap "New";       shot new-page
tap "Browser";   shot browser-home
tap "Settings";  shot settings
up; shot settings-scrolled
tap "Change" 4; shot folder-picker; adb shell input keyevent KEYCODE_BACK; sleep 2; shot settings-after-picker
up; shot settings-smart-button
up; shot storage-card
up; up; shot settings-bottom
tap "Downloads"; shot downloads

step "share a direct file link: the small sheet opens on top (10 MB test file)"
adb shell input keyevent KEYCODE_HOME; sleep 2   # share from "another app": the sheet must sit on top of it
share "https://proof.ovh.net/files/10Mb.dat"
sleep 5; shot share-sheet-file
tap "Download" 3; shot share-sheet-closed
sleep 15
adb shell cmd statusbar expand-notifications; sleep 2; shot notification-shade; adb shell cmd statusbar collapse
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3; tap "Downloads" 2; shot downloads-after-share

step "duplicate: share the same 10 MB link again (plus a tracking parameter)"
adb shell input keyevent KEYCODE_HOME; sleep 2
share "https://proof.ovh.net/files/10Mb.dat?utm_source=test"
sleep 6; shot share-sheet-duplicate
adb shell input keyevent KEYCODE_BACK; sleep 2

step "share text with 3 direct links: the multi-link queue"
share "Files:\ https://proof.ovh.net/files/1Mb.dat\ https://proof.ovh.net/files/10Mb.dat\ https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"
sleep 6; shot share-sheet-multi
tap "to queue" 3; shot share-sheet-multi-added
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3; tap "Downloads" 2; shot downloads-queue
sleep 10; shot downloads-queue-later
adb shell cmd statusbar expand-notifications; sleep 2; shot notification-shade-many; adb shell cmd statusbar collapse

step "built-in browser: example.com, then a page with direct file links"
tap "Browser" 2
tap "Address bar" 1; adb shell input text "https://example.com"; adb shell input keyevent 66
sleep 10; shot browser-example
tap "Address bar" 1; tap "Clear address" 1; adb shell input text "https://proof.ovh.net/files/"; adb shell input keyevent 66
sleep 15; shot browser-file-list
if python3 tools/ui_tap.py "1Mb.dat"; then sleep 10; else
  tap "Address bar" 1; tap "Clear address" 1; adb shell input text "https://proof.ovh.net/files/1Mb.dat"; adb shell input keyevent 66; sleep 12
fi
shot browser-download-sheet
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 2; shot browser-after-back

step "light theme"
tap "Settings" 2
tap "Light" 3; shot settings-light
tap "Downloads" 2; shot downloads-light
tap "New" 2; shot new-light
tap "Browser" 2; shot browser-light
adb shell input keyevent KEYCODE_HOME; sleep 2
share "https://proof.ovh.net/files/1Mb.dat"
sleep 5; shot share-sheet-light
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3
tap "Settings" 2; tap "Dark" 3; shot settings-dark-again

step "share a YouTube link to the sheet (may be blocked for cloud computers)"
adb shell input keyevent KEYCODE_HOME; sleep 2
share "https://www.youtube.com/watch?v=jNQXAC9IVRw"
for i in 1 2 3 4 5; do
  sleep 20
  python3 tools/ui_tap.py --exists "Looking at the link" || python3 tools/ui_tap.py --exists "Still looking" || python3 tools/ui_tap.py --exists "Trying another way" || break
done
shot share-sheet-youtube
tap "Audio only" 2; tap "Download" 4; shot share-sheet-youtube-after
adb shell input keyevent KEYCODE_BACK; sleep 1

step "files saved"
adb shell ls -la /sdcard/Download/VibeDownloader/ > "$OUT/saved-files.txt" 2>&1 || true
cat "$OUT/saved-files.txt"

step "logs"
adb logcat -d -v time > "$OUT/logcat-all.txt"
grep -E "AndroidRuntime|FATAL|VibeDownloader|python.stderr|python.stdout" "$OUT/logcat-all.txt" | tail -600 > "$OUT/logcat-app.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat-all.txt"; then echo "CRASH FOUND"; grep -A30 "FATAL EXCEPTION" "$OUT/logcat-all.txt" | head -60; fi
exit 0

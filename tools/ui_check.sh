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
step() { echo; echo "=== $*"; }

adb wait-for-device
adb shell settings put global window_animation_scale 1
adb logcat -c
step "install"
adb install -r -g "$APK" 2>&1 | tail -1          # -g grants runtime permissions (notifications)

step "launch"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 6; shot launch

step "screens"
tap "New";       shot new-page
tap "Settings";  shot settings
adb shell input swipe 540 1800 540 600 400; sleep 1; shot settings-scrolled
tap "Downloads"; shot downloads

step "share a direct file link (10 MB test file)"
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://proof.ovh.net/files/10Mb.dat" -n "$PKG/.MainActivity" >/dev/null
sleep 4; shot shared-direct-link
tap "Start download" 6; shot direct-downloading
sleep 20; tap "Downloads" 2; shot direct-after-20s
adb shell cmd notification list 2>/dev/null | grep -i vibe | head -5 > "$OUT/notifications.txt" || true
adb shell cmd statusbar expand-notifications; sleep 2; shot notification-shade; adb shell cmd statusbar collapse

step "share a YouTube link (may be blocked for cloud computers; still shows the app's behaviour)"
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=jNQXAC9IVRw" -n "$PKG/.MainActivity" >/dev/null
# wait up to 150 s for the app to finish looking at the link (screens every 30 s)
for i in 1 2 3 4 5; do
  sleep 30
  python3 tools/ui_tap.py --exists "Looking at the link" || python3 tools/ui_tap.py --exists "Still looking" || python3 tools/ui_tap.py --exists "Trying another way" || break
  shot "youtube-wait-${i}"
done
shot youtube-info
tap "Audio only" 2; shot youtube-audio-picked
tap "Start download" 8; sleep 45; tap "Downloads" 2; shot youtube-after-start

step "files saved"
adb shell ls -la /sdcard/Download/VibeDownloader/ > "$OUT/saved-files.txt" 2>&1 || true
cat "$OUT/saved-files.txt"

step "logs"
adb logcat -d -v time > "$OUT/logcat-all.txt"
grep -E "AndroidRuntime|FATAL|VibeDownloader|python.stderr|python.stdout" "$OUT/logcat-all.txt" | tail -400 > "$OUT/logcat-app.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat-all.txt"; then echo "CRASH FOUND"; grep -A30 "FATAL EXCEPTION" "$OUT/logcat-all.txt" | head -60; fi
exit 0

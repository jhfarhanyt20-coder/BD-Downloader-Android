#!/usr/bin/env python3
"""Tap a UI element on the emulator by its visible text or content description.

    python3 tools/ui_tap.py "Settings"            -> taps the first match
    python3 tools/ui_tap.py --exists "Downloads"  -> exit 0 if it is on screen
    python3 tools/ui_tap.py --dump                -> prints every visible text

Uses `adb shell uiautomator dump`, so it works with Compose (texts and
content descriptions are exposed to accessibility).
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def dump():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    start = xml.find("<?xml")
    return ET.fromstring(xml[start:]) if start >= 0 else None


def nodes(root):
    for n in root.iter("node"):
        label = (n.get("text") or "").strip() or (n.get("content-desc") or "").strip()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if label and m:
            x1, y1, x2, y2 = map(int, m.groups())
            yield label, ((x1 + x2) // 2, (y1 + y2) // 2)


def main():
    args = sys.argv[1:]
    root = dump()
    if root is None:
        print("no UI dump")
        sys.exit(2)
    if args and args[0] == "--dump":
        for label, xy in nodes(root):
            print(f"{xy}  {label}")
        return
    exists = args and args[0] == "--exists"
    want = args[-1].lower()
    found = list(nodes(root))
    # an exact match wins, then a text that starts with the word, then one that only contains it
    def rank(n):
        label = n[0].lower()
        return 0 if label == want else 1 if label.startswith(want) else 2
    found.sort(key=rank)
    for label, (x, y) in found:
        if label.lower() == want or want in label.lower():
            if exists:
                sys.exit(0)
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
            print(f"tapped '{label}' at {x},{y}")
            return
    print(f"not found: {args[-1]}")
    sys.exit(1)


if __name__ == "__main__":
    main()

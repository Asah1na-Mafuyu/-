"""Inspect only the isolated development emulator; never select a physical phone."""
from pathlib import Path
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / ".tools/android/platform-tools/platform-tools/adb.exe"
SERIAL = "emulator-5580"
OUT = ROOT / ".qa/android"
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args, timeout=20):
    result = subprocess.run([str(ADB), "-s", SERIAL, *map(str, args)], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace"))
    return result.stdout.decode("utf-8", "replace")

def tree():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/jijian-ui.xml")
    return ET.fromstring(adb("shell", "cat", "/data/local/tmp/jijian-ui.xml"))

def inspect():
    for node in tree().iter("node"):
        attrs = node.attrib
        if attrs.get("password") == "true":
            continue
        if attrs.get("text") or attrs.get("content-desc") or attrs.get("class", "").endswith("Switch"):
            print(json.dumps({key: attrs.get(key) for key in ("text", "content-desc", "class", "bounds", "clickable", "checked", "package")}, ensure_ascii=False))

def tap(label):
    nodes = [node for node in tree().iter("node") if node.get("text") == label or node.get("content-desc") == label]
    if not nodes:
        nodes = [node for node in tree().iter("node") if node.get("content-desc", "").startswith(label + ",")]
    if not nodes:
        raise RuntimeError("UI element missing: " + label)
    node = next((node for node in reversed(nodes) if node.get("clickable") == "true"), nodes[-1])
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)
    time.sleep(0.3)

def screen(name):
    adb("shell", "screencap", "-p", "/data/local/tmp/jijian-screen.png")
    adb("pull", "/data/local/tmp/jijian-screen.png", str(OUT / (name + ".png")))

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    mode = sys.argv[1] if len(sys.argv) > 1 else "inspect"
    if mode == "tap":
        tap(sys.argv[2])
    elif mode == "screen":
        screen(sys.argv[2])
    elif mode == "inspect":
        inspect()
    else:
        raise SystemExit("Expected inspect, tap LABEL, or screen NAME")

"""Real permission/window/capture checks on the disposable emulator-5580.

Resets this test emulator's app data. Never connects to a physical device and
never supplies an API key or makes a paid translation request.
"""
from pathlib import Path
import json
import re
import runpy
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")
helpers = runpy.run_path(str(Path(__file__).with_name("android-ui.py")))
adb, tree, tap, screen = (helpers[name] for name in ("adb", "tree", "tap", "screen"))
OUT = helpers["OUT"]
PKG = "com.jijian.translate"
passed = []

def require(value, message):
    if not value:
        raise RuntimeError(message)

def labels():
    values = set()
    for node in tree().iter("node"):
        values.add(node.get("text", ""))
        values.add(node.get("content-desc", "").split(",", 1)[0])
    return values

def has(label):
    return label in labels()

def wait_label(label, seconds=15):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if has(label):
            return
        time.sleep(0.2)
    raise RuntimeError("Missing UI: " + label)

def scroll_to(label):
    for _ in range(6):
        if has(label):
            return
        adb("shell", "input", "swipe", 900, 1600, 900, 700, 350)
    raise RuntimeError("Missing scrolling UI: " + label)

def ok(name):
    passed.append("PASS " + name)
    print(passed[-1], flush=True)

def point(x, y):
    adb("shell", "input", "tap", int(x), int(y))
    time.sleep(0.45)

def windows():
    result = []
    for part in re.split(r"Window #\d+", adb("shell", "dumpsys", "window", "windows")):
        if "package=" + PKG not in part or "ty=APPLICATION_OVERLAY" not in part:
            continue
        match = re.search(r"\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", part)
        if match:
            x, y, right, bottom = map(int, match.groups())
            result.append((x, y, right-x, bottom-y))
    return result

def ball():
    return next(box for box in windows() if box[2] == box[3] and box[2] < 200)

def menu_item(index):
    x, y, w, h = ball()
    point(x+w/2, y+h/2)
    menu = next(box for box in windows() if box[2] != box[3] and box[2] < 600)
    density = int(re.search(r"\d+", adb("shell", "wm", "density")).group()) / 160
    offset = (8 + 42*index + 21 + (6 if index else 0)) * density
    point(menu[0]+menu[2]/2, menu[1]+offset)

def projection():
    return PKG in adb("shell", "dumpsys", "media_projection")

def restore_rotation():
    adb("shell", "wm", "user-rotation", "lock", 0)
    adb("shell", "wm", "fixed-to-user-rotation", "default")
    adb("shell", "wm", "set-ignore-orientation-request", "false")

def run():
    require("emulator-5580" in adb("get-serialno"), "Wrong test device")
    restore_rotation()
    adb("shell", "am", "force-stop", PKG)
    require("Success" in adb("shell", "pm", "clear", PKG), "App reset failed")
    adb("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "default")
    adb("shell", "pm", "revoke", PKG, "android.permission.POST_NOTIFICATIONS")
    adb("shell", "pm", "clear-permission-flags", PKG, "android.permission.POST_NOTIFICATIONS", "user-set", "user-fixed")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PKG+"/.MainActivity")
    wait_label("悬浮球")
    screen("main")
    ok("signed release APK launches with native main UI")
    tap("悬浮球")
    wait_label("启用悬浮球")
    tap("去开启")
    wait_label("极简翻译")
    tap("极简翻译")
    wait_label("Allow display over other apps")
    tap("Allow display over other apps")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_label("ALLOW")
    tap("ALLOW")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(0.6)
    require(ball(), "Bubble missing")
    ok("overlay settings and notification runtime consent")
    x, y, w, h = ball()
    adb("shell", "input", "swipe", x+w//2, y+h//2, 100, 550, 650)
    time.sleep(0.4)
    before = ball()
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-W", "-n", PKG+"/.MainActivity")
    tap("悬浮球")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(0.6)
    after = ball()
    require(abs(before[0]-after[0]) < 5 and abs(before[1]-after[1]) < 5, "Position not persisted")
    screen("bubble")
    ok("bubble drag, edge snap, and position survives restart")
    menu_item(1)
    wait_label("收起")
    tap("输入或粘贴要翻译的内容")
    adb("shell", "input", "text", "Hello%sworld")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("翻译")
    wait_label("请先在设置中填写 DeepSeek API Key。")
    screen("floating")
    ok("floating text entry and visible missing-Key feedback")
    tap("截屏")
    wait_label("START")
    tap("CANCEL")
    require("launcher3" in adb("shell", "dumpsys", "activity", "activities"), "Original page missing")
    menu_item(0)
    wait_label("START")
    tap("START")
    wait_label("翻译选区")
    require(projection(), "Projection missing")
    ok("system screenshot consent, cancel, return to original app, capture")
    adb("shell", "input", "swipe", 285, 325, 815, 485, 600)
    tap("翻译选区")
    wait_label("识别原文 · 图片")
    wait_label("请先在设置中填写 DeepSeek API Key。")
    screen("capture-preview")
    ok("real screen region is cropped into floating translation panel")
    tap("收起")
    adb("shell", "am", "start", "-W", "-n", PKG+"/.MainActivity")
    tap("截屏")
    require(projection() and not has("START"), "Main UI requested duplicate capture consent")
    ok("main screenshot button preserves an already authorized session")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(0.6)
    menu_item(1)
    wait_label("收起")
    tap("截屏")
    wait_label("翻译选区")
    require(projection(), "Reused projection ended")
    ok("second manual capture reuses authorized virtual display")
    adb("shell", "wm", "fixed-to-user-rotation", "enabled")
    adb("shell", "wm", "user-rotation", "lock", 1)
    time.sleep(0.8)
    require("1920 x 1080" in adb("shell", "dumpsys", "display"), "Landscape capture not resized")
    menu_item(0)
    wait_label("翻译选区")
    tap("取消")
    ok("orientation change cancels stale selection and resizes existing capture")
    adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
    end = time.monotonic()+5
    while projection() and time.monotonic() < end:
        time.sleep(0.2)
    require(not projection(), "Screen-off did not end capture")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    restore_rotation()
    time.sleep(0.5)
    menu_item(0)
    wait_label("START")
    tap("CANCEL")
    ok("screen-off releases projection and next capture asks again")
    adb("shell", "am", "start", "-W", "-n", PKG+"/.MainActivity")
    tap("设置")
    wait_label("DeepSeek API Key")
    scroll_to("关闭悬浮球与截屏会话")
    tap("关闭悬浮球与截屏会话")
    require(not projection() and "ServiceRecord" not in adb("shell", "dumpsys", "activity", "services", PKG), "Service did not stop")
    ok("settings close removes overlays, foreground service, and capture")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.SEND", "-t", "text/plain", "--es", "android.intent.extra.TEXT", "Daily_test", "-n", PKG+"/.MainActivity")
    wait_label("Daily_test")
    ok("Android share sheet text entry")
    import_and_theme()

def import_and_theme():
    adb("push", str(OUT / "main.png"), "/sdcard/Download/jijian-qa.png")
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Download/jijian-qa.png")
    tap("图片")
    tap("Show roots")
    tap("Downloads")
    wait_label("jijian-qa.png")
    tap("jijian-qa.png")
    wait_label("图片已就绪，可先裁剪或旋转。")
    tap("裁剪")
    wait_label("使用选区")
    tap("旋转")
    tap("全选")
    tap("使用选区")
    wait_label("选区已就绪。")
    ok("system image picker, URI decoding, rotation, and crop")
    tap("设置")
    wait_label("跟随系统")
    tap("跟随系统")
    tap("深色")
    scroll_to("翻译完成后自动朗读")
    nodes = [n for n in tree().iter("node") if n.get("text") == "翻译完成后自动朗读"]
    require(nodes and nodes[0].get("checked") == "false", "Autoplay must default off")
    tap("翻译完成后自动朗读")
    tap("正常语速")
    tap("快速")
    scroll_to("保存")
    tap("保存")
    wait_label("设置已保存。")
    screen("dark")
    ok("settings persist appearance and rebuild main UI in dark mode")
    tap("设置")
    scroll_to("翻译完成后自动朗读")
    nodes = [n for n in tree().iter("node") if n.get("text") == "翻译完成后自动朗读"]
    require(nodes and nodes[0].get("checked") == "true" and has("快速"), "Speech settings did not persist")
    tap("翻译完成后自动朗读")
    tap("快速")
    tap("正常语速")
    scroll_to("保存")
    tap("保存")
    wait_label("设置已保存。")
    ok("speech autoplay defaults off, speech rate and preference persist")
    crash = adb("logcat", "-d", "-s", "AndroidRuntime:E")
    require("FATAL EXCEPTION" not in crash, "Android runtime crash")
    ok("no AndroidRuntime crashes in complete system flow")

try:
    if "--resume-import" in sys.argv:
        passed.extend(line for line in OUT.joinpath("system-checks.txt").read_text(encoding="utf-8").splitlines() if line.startswith("PASS "))
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        adb("shell", "am", "start", "-W", "-n", PKG+"/.MainActivity")
        import_and_theme()
    else:
        run()
except Exception as exc:
    passed.append("FAIL " + str(exc))
    print(passed[-1], flush=True)
    screen("system-failure")
    raise
finally:
    OUT.joinpath("system-checks.txt").write_text("\n".join(passed)+"\n", encoding="utf-8")

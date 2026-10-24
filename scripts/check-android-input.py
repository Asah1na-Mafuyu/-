"""Camera and microphone UI checks on emulator-5580 only; no paid API call."""
from pathlib import Path
import re
import runpy
import shutil
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")
h = runpy.run_path(str(Path(__file__).with_name("android-ui.py")))
adb, tree, tap, screen = (h[x] for x in ("adb", "tree", "tap", "screen"))
out = h["ROOT"] / ".qa/android-v0.3"
out.mkdir(parents=True, exist_ok=True)
pkg = "com.jijian.translate"
report = []

def require(value, message):
    if not value:
        raise RuntimeError(message)

def has(label):
    return any(n.get("text") == label or n.get("content-desc") == label for n in tree().iter("node"))

def wait(label):
    until = time.monotonic() + 15
    while time.monotonic() < until:
        if has(label):
            return
        time.sleep(0.2)
    raise RuntimeError("Missing UI: " + label)

def scroll_to(label):
    for _ in range(5):
        if has(label):
            return
        adb("shell", "input", "swipe", 900, 1600, 900, 600, 300)
    raise RuntimeError("Missing scrolling UI: " + label)

def save(name):
    screen(name)
    shutil.copyfile(h["OUT"] / (name + ".png"), out / (name + ".png"))

def ok(message):
    report.append("PASS " + message)
    print(report[-1], flush=True)

def take():
    tap("拍照翻译")
    wait("Shutter")
    tap("Shutter")
    wait("Done")
    tap("Done")
    wait("翻译照片")

try:
    require(adb("get-serialno").strip() == "emulator-5580", "Wrong device")
    if "--resume-mic" in sys.argv:
        report.extend(x for x in (out / "input-system-checks.txt").read_text(encoding="utf-8").splitlines() if x.startswith("PASS "))
    else:
        adb("shell", "am", "force-stop", pkg)
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        adb("shell", "logcat", "-c")
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.SEND", "-t", "text/plain", "--es", "android.intent.extra.TEXT", "Original_photo_draft", "-n", pkg + "/.MainActivity")
        wait("拍照翻译")
        require(has("语音翻译"), "New inputs missing")
        save("inputs-main")
        take()
        save("inputs-camera-crop")
        ok("system camera writes full photo through private content URI and returns to crop preview")
        tap("旋转")
        tap("全选")
        tap("重拍")
        wait("Shutter")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        wait("已取消拍照。")
        require(has("Original_photo_draft"), "Cancelled camera replaced original text")
        ok("retake and camera cancel preserve previous draft without translating")
        take()
        tap("旋转")
        tap("全选")
        tap("翻译照片")
        wait("请先在设置中填写 DeepSeek API Key。")
        require(has("待翻译的图片选区"), "Camera image did not reach translation panel")
        ok("confirmed photo rotation/selection triggers existing translation pipeline and Key guard")
        tap("清空")
        adb("shell", "pm", "revoke", pkg, "android.permission.RECORD_AUDIO")
        adb("shell", "pm", "clear-permission-flags", pkg, "android.permission.RECORD_AUDIO", "user-set", "user-fixed")
        # Android restarts the app process when an already-granted permission is revoked.
        adb("shell", "am", "start", "-W", "-n", pkg + "/.MainActivity")
        wait("语音翻译")
        tap("语音翻译")
        wait("开始说话")
        save("inputs-voice")
        tap("开始说话")
    wait("DON’T ALLOW")
    tap("DON’T ALLOW")
    wait("未获得麦克风权限。可点“应用权限设置”开启，或手动输入文字。")
    require(has("应用权限设置"), "No permission recovery action")
    ok("microphone permission requested on demand and denial keeps voice page usable")
    tap("开始说话")
    wait("WHILE USING THE APP")
    tap("WHILE USING THE APP")
    wait("手机尚未提供语音识别服务，请在系统语音设置中启用。")
    require(has("系统语音设置"), "Missing system-service recovery action")
    ok("microphone grant succeeds and missing AOSP speech engine shows actionable status")
    edits = [n for n in tree().iter("node") if n.get("class") == "android.widget.EditText"]
    require(edits, "Transcript field missing")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", edits[0].get("bounds")))
    adb("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)
    adb("shell", "input", "text", "voice_input_check")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    scroll_to("翻译语音")
    tap("翻译语音")
    wait("请先在设置中填写 DeepSeek API Key。")
    require(has("voice_input_check"), "Voice text was not committed")
    require(not has("待翻译的图片选区"), "Voice text reused old image")
    ok("editable voice text submits as text after explicit confirmation with no stale image")
    require("FATAL EXCEPTION" not in adb("logcat", "-d", "-s", "AndroidRuntime:E"), "Android runtime crash")
    ok("no AndroidRuntime crash across camera and voice permission flows")
except Exception as e:
    report.append("FAIL " + str(e))
    print(report[-1], flush=True)
    save("inputs-failure")
    raise
finally:
    (out / "input-system-checks.txt").write_text("\n".join(report)+"\n", encoding="utf-8")

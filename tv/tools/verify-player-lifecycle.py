"""Run isolated Android device regressions and retain a bounded pass/fail report."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk"))) / "platform-tools/adb.exe"
CLASSES = [
    "cn.hongguo.tv.ui.PlayerLifecycleDeviceTest",
    "cn.hongguo.tv.ui.TvUiDeviceTest",
    "cn.hongguo.tv.ui.AccountFormDeviceTest",
    "cn.hongguo.tv.cache.HistoryStoreDeviceTest",
    "cn.hongguo.tv.account.FavoriteContractDeviceTest",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="127.0.0.1:16384")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--full", action="store_true")
    args = parser.parse_args()
    report = ROOT / "_work/crash-qa"
    report.mkdir(parents=True, exist_ok=True)

    def adb(*parts, timeout=30):
        return subprocess.run([str(ADB), "-s", args.serial, *parts],
                              capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)

    if args.install:
        debug = sorted((ROOT / "app/build/outputs/apk/debug").glob("*-qa-debug.apk"), key=lambda p: p.stat().st_mtime)
        test = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        if not debug or not test.is_file():
            raise RuntimeError("先构建 assembleDebug 与 assembleDebugAndroidTest")
        for package in (debug[-1], test):
            result = adb("install", "-r", str(package), timeout=90)
            if result.returncode or "Success" not in result.stdout:
                raise RuntimeError("独立验收包安装失败：" + result.stdout + result.stderr)
        print("已安装独立 .qa 验收包", flush=True)

    selected = ",".join(CLASSES if args.full else CLASSES[:1])
    result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", selected,
                 "cn.hongguo.tv.qa.test/android.test.InstrumentationTestRunner", timeout=420)
    output = result.stdout + result.stderr
    (report / "device-tests.txt").write_text(output, encoding="utf-8")
    passed = bool(re.search(r"OK \(\d+ tests?\)", output)) and not any(
        marker in output for marker in ("FAILURES!!!", "Process crashed", "INSTRUMENTATION_FAILED"))
    match = re.search(r"OK \((\d+) tests?\)", output) or re.search(r"Tests run: (\d+)", output)
    summary = {"passed": passed, "tests": int(match.group(1)) if match else 0,
               "classes": selected.split(","), "serial": args.serial}
    (report / "device-tests.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    if not passed:
        failures = re.findall(r"INSTRUMENTATION_STATUS: stack=(.*?)(?=INSTRUMENTATION_STATUS:)", output, re.S)
        for failure in failures:
            print(failure[:2200], flush=True)
    tail = output.rfind("INSTRUMENTATION_RESULT:")
    print(output[tail:] if tail >= 0 else output[-4000:], flush=True)
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())

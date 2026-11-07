"""Run the destructive API 23 fixtures only on a disposable x86_64 emulator."""

from pathlib import Path
import json
import os
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android/build/api23-probes"
ADB = os.environ.get("ADB", "adb")
PROBES = ("SubtitleProbe", "HomeProbe", "SourceHostProbe")


def adb(*args, check=True):
    return subprocess.run([ADB, *args], check=check, text=True, capture_output=True,
                          encoding="utf-8", errors="replace", timeout=300).stdout


def main():
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("This destructive fixture is restricted to an emulator")
    if adb("shell", "getprop", "ro.build.version.sdk").strip() != "23":
        raise RuntimeError("API 23 is required to exercise the missing callback regression")
    if adb("shell", "getprop", "ro.product.cpu.abi").strip() != "x86_64":
        raise RuntimeError("The fixture includes only x86_64 QuickJS JNI")
    (OUT / "device.txt").write_text(adb("shell", "getprop"), encoding="utf-8")
    adb("install", "-r", str(OUT / "fixture.apk"))
    adb("install", "-r", str(OUT / "probe.apk"))
    # Keep TV fixtures at a stable landscape size and prevent unrelated upstream traffic.
    adb("shell", "wm", "size", "1920x1080")
    adb("shell", "wm", "density", "240")
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    results = {}
    failures = []
    try:
        for probe in PROBES:
            log = ""
            try:
                adb("shell", "pm", "clear", "com.pilipala.marcomovie")
                adb("logcat", "-c")
                log = adb("shell", "am", "instrument", "-w",
                          "com.pilipala.marcomovie.test/com.pilipala.marcomovie." + probe)
                print(log)
                match = re.search(r"INSTRUMENTATION_STATUS: result=(\{[^\r\n]+\})", log)
                result = json.loads(match[1]) if match else {}
                results[probe] = result
                if result.get("passed") is not True:
                    raise RuntimeError(probe + " failed; see logs and screenshots")
            except Exception as error:
                failures.append(probe)
                results.setdefault(probe, {"passed": False})["runner_error"] = str(error)
                captured = getattr(error, "stdout", None)
                if captured and not log:
                    log = captured.decode("utf-8", errors="replace") if isinstance(captured, bytes) else captured
                print(probe + ": " + str(error))
            finally:
                (OUT / (probe + ".log")).write_text(log, encoding="utf-8")
                try:
                    (OUT / (probe + "-logcat.txt")).write_text(adb("logcat", "-d", check=False), encoding="utf-8")
                    (OUT / "screenshots" / probe).mkdir(parents=True, exist_ok=True)
                    adb("pull", "/sdcard/Android/data/com.pilipala.marcomovie/files/",
                        str(OUT / "screenshots" / probe), check=False)
                except Exception as error:
                    results.setdefault(probe, {})["evidence_error"] = str(error)
                    if probe not in failures:
                        failures.append(probe)
    finally:
        (OUT / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        adb("shell", "wm", "size", "reset", check=False)
        adb("shell", "wm", "density", "reset", check=False)
    if failures:
        raise RuntimeError("API 23 regressions failed: " + ", ".join(failures))


if __name__ == "__main__":
    main()

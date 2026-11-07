"""Run only on a disposable emulator: installs and clears the test application."""
from pathlib import Path
import json
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'android/build/mobile-player-probe'
ADB = os.environ.get('ADB', 'adb')


def adb(*args, check=True):
    return subprocess.run([ADB, *args], check=check, text=True, capture_output=True, timeout=240).stdout


if __name__ == '__main__':
    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('This destructive fixture is restricted to an emulator')
    (OUT/'device.txt').write_text(adb('shell', 'getprop'), encoding='utf-8')
    adb('install', '-r', str(OUT/'layout-only.apk'))
    adb('install', '-r', str(OUT/'probe.apk'))
    adb('shell', 'pm', 'clear', 'com.pilipala.marcomovie')
    log = ''
    try:
        log = adb('shell', 'am', 'instrument', '-w', 'com.pilipala.marcomovie.test/com.pilipala.marcomovie.MobilePlayerProbe')
        print(log)
        match = re.search(r'INSTRUMENTATION_STATUS: result=(\{[^\r\n]+\})', log)
        if not match or json.loads(match[1]).get('passed') is not True:
            raise RuntimeError('Mobile player regression failed; see instrumentation.log and screenshots')
    finally:
        (OUT/'instrumentation.log').write_text(log, encoding='utf-8')
        (OUT/'logcat.txt').write_text(adb('logcat', '-d', check=False), encoding='utf-8')
        adb('pull', '/sdcard/Android/data/com.pilipala.marcomovie/files/', str(OUT/'screenshots'), check=False)

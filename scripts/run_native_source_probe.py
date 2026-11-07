"""Execute real Node JNI checks on a disposable x86_64 emulator; no private networks."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'android/build/native-source-probe'
ADB = os.environ.get('ADB', 'adb')


def adb(*args, check=True, timeout=300):
    return subprocess.run([ADB, *args], check=check, timeout=timeout, capture_output=True,
                          text=True, encoding='utf-8', errors='replace').stdout


def main(page_size):
    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('This destructive probe is restricted to a disposable emulator')
    if adb('shell', 'getprop', 'ro.product.cpu.abi').strip() != 'x86_64':
        raise RuntimeError('The verified Node probe includes only x86_64 libraries')
    # API 23 may report boot_completed while CryptKeeper is still encrypting.
    import run_api23_probes as boot
    boot.OUT = OUT
    boot.wait_for_full_boot()
    actual = adb('shell', 'getconf', 'PAGESIZE').strip()
    if actual != str(page_size):
        raise RuntimeError('Wrong emulator page size: expected ' + str(page_size) + ', got ' + actual)
    (OUT / 'device.txt').write_text(adb('shell', 'getprop') + '\nPAGESIZE=' + actual + '\n', encoding='utf-8')
    adb('install', '-r', str(OUT / 'fixture.apk'))
    adb('install', '-r', str(OUT / 'probe.apk'))
    adb('shell', 'pm', 'clear', 'com.pilipala.marcomovie')
    adb('shell', 'svc', 'wifi', 'disable')
    adb('shell', 'svc', 'data', 'disable')
    adb('logcat', '-c')
    log = ''
    try:
        log = adb('shell', 'am', 'instrument', '-w', '-e', 'page_size', str(page_size),
                  'com.pilipala.marcomovie.test/com.pilipala.marcomovie.NativeSourceProbe')
        print(log)
        match = re.search(r'INSTRUMENTATION_STATUS: result=(\{[^\r\n]+\})', log)
        result = json.loads(match[1]) if match else {'passed': False, 'error': 'No instrumentation result'}
        (OUT / 'results.json').write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        if result.get('passed') is not True:
            raise RuntimeError('Embedded Node source regression failed; see instrumentation and logcat')
    finally:
        (OUT / 'instrumentation.log').write_text(log, encoding='utf-8')
        (OUT / 'logcat.txt').write_text(adb('logcat', '-d', check=False), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--page-size', choices=[4096, 16384], type=int, required=True)
    main(parser.parse_args().page_size)

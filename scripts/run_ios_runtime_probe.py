"""Run the production embedded Node engine/scripts inside an iOS simulator.

This app is a CI-only runtime fixture, not a published Marco Movie package.
It does not establish physical-device playback or Flutter UI compatibility.
"""
from pathlib import Path
import hashlib
import json
import platform
import plistlib
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'verification/ios-runtime'
APP = OUT / 'RuntimeProbe.app'
IDENTIFIER = 'com.pilipala.marcomovie.runtimeprobe'


def run(*args, capture=False, timeout=180):
    print('Running:', ' '.join(map(str,args)), flush=True)
    result = subprocess.run(list(map(str, args)), check=True, text=True,
                            timeout=timeout,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else ''


def main():
    if sys.platform != 'darwin':
        raise SystemExit('This validation requires a macOS host and iOS simulator.')
    OUT.mkdir(parents=True, exist_ok=True)
    from prepare_catpaw_runtime import prepare
    runtime = OUT / 'node'
    prepare('ios', runtime)
    framework = runtime / 'NodeMobile.xcframework/ios-arm64_x86_64-simulator/NodeMobile.framework'
    (APP / 'Frameworks').mkdir(parents=True, exist_ok=True)
    shutil.copytree(framework, APP/'Frameworks/NodeMobile.framework', dirs_exist_ok=True)
    shutil.copytree(ROOT/'shared/catpaw', APP/'runtime', dirs_exist_ok=True)
    shutil.copyfile(ROOT/'scripts/ios_runtime/fixture.cjs', APP/'fixture.cjs')
    shutil.copytree(ROOT/'android/src/main/assets/python-host', APP/'python-host', dirs_exist_ok=True)
    info = {'CFBundleIdentifier': IDENTIFIER, 'CFBundleExecutable': 'RuntimeProbe',
            'CFBundleName': 'RuntimeProbe', 'CFBundlePackageType': 'APPL',
            'CFBundleVersion': '1', 'CFBundleShortVersionString': '1.0',
            'MinimumOSVersion': '14.0', 'UIDeviceFamily': [1, 2],
            'UILaunchScreen': {}, 'NSAppTransportSecurity': {'NSAllowsArbitraryLoads': True},
            'NSLocalNetworkUsageDescription': 'Local runtime regression fixture.'}
    (APP/'Info.plist').write_bytes(plistlib.dumps(info))
    sdk = run('xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path', capture=True)
    arch = 'arm64' if platform.machine() == 'arm64' else 'x86_64'
    classes = ROOT/'flutter/packages/catpaw_runtime/ios/Classes'
    quickjs = ROOT/'flutter/packages/quickjs_engine/native/cxx'
    quickjs_ios = ROOT/'flutter/packages/quickjs_engine/ios/quickjs_engine/quickjs_engine_native/Sources/quickjs_engine_native'
    objects = []
    for name in ['quickjs.c', 'dtoa.c', 'libregexp.c', 'libunicode.c', 'libfastdev_quickjs_runtime.cpp']:
        obj=OUT/(name+'.o'); objects.append(obj)
        run('xcrun', '--sdk', 'iphonesimulator', 'clang++' if name.endswith('.cpp') else 'clang',
            '-target', arch+'-apple-ios14.0-simulator', '-isysroot', sdk, '-O2', '-DNDEBUG=1',
            '-DCONFIG_VERSION="ng-0.14.0"', '-I'+str(quickjs), '-I'+str(quickjs/'quickjs'),
            '-std=c++17' if name.endswith('.cpp') else '-std=c11', '-c', quickjs_ios/name, '-o', obj)
    run('xcrun', '--sdk', 'iphonesimulator', 'clang++', '-fobjc-arc', '-std=c++17',
        '-target', arch+'-apple-ios14.0-simulator', '-isysroot', sdk,
        '-I'+str(classes), '-I'+str(quickjs), '-F'+str(framework.parent), '-framework', 'NodeMobile',
        '-framework', 'Foundation', '-framework', 'UIKit',
        '-Wl,-rpath,@executable_path/Frameworks', ROOT/'scripts/ios_runtime/Probe.mm',
        classes/'CatpawRuntimeEngine.mm', ROOT/'scripts/ios_runtime/QuickJsProbe.mm',
        *objects, '-o', APP/'RuntimeProbe')
    run('codesign', '--force', '--sign', '-', APP/'Frameworks/NodeMobile.framework')
    run('codesign', '--force', '--sign', '-', APP)
    inventory = json.loads(run('xcrun', 'simctl', 'list', 'devices', 'available', '--json', capture=True))
    candidates = [(runtime, device) for runtime, devices in inventory['devices'].items()
                  if '.iOS-' in runtime for device in devices
                  if device.get('isAvailable') and device['name'].startswith('iPhone')]
    if not candidates:
        raise RuntimeError('No available iPhone simulator')
    runtime_name, device = sorted(candidates, key=lambda item: item[0], reverse=True)[0]
    udid = device['udid']
    if device['state'] != 'Booted':
        run('xcrun', 'simctl', 'boot', udid)
    run('xcrun', 'simctl', 'bootstatus', udid, '-b', timeout=360)
    run('xcrun', 'simctl', 'install', udid, APP)
    container = Path(run('xcrun', 'simctl', 'get_app_container', udid, IDENTIFIER, 'data', capture=True))
    result_file = container/'Documents/result.json'
    result_file.unlink(missing_ok=True)
    run('xcrun', 'simctl', 'launch', '--terminate-running-process', udid, IDENTIFIER, timeout=180)
    deadline = time.monotonic()+180
    while not result_file.exists() and time.monotonic() < deadline:
        time.sleep(1)
    if not result_file.exists():
        raise RuntimeError('iOS runtime fixture produced no result; inspect simulator crash diagnostics')
    report = json.loads(result_file.read_text())
    report.update({'simulator': device['name'], 'os_runtime': runtime_name, 'cpu': arch,
                   'commit': run('git', '-C', ROOT, 'rev-parse', 'HEAD', capture=True),
                   'production_inputs': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in [classes/'CatpawRuntimeEngine.mm', *sorted((ROOT/'shared/catpaw').glob('*.cjs')),
                               ROOT/'shared/catpaw/drpy-core.js', ROOT/'shared/catpaw/drpy-assets.json',
                               quickjs/'libfastdev_quickjs_runtime.cpp', quickjs/'quickjs/quickjs.c',
                               *sorted((ROOT/'android/src/main/assets/python-host').glob('*.js'))]}})
    (OUT/'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    print(json.dumps({'passed': report['passed'], 'checks': report['checks'],
                      'runtime': report['runtime'], 'failure': report['failure']}, ensure_ascii=False))
    if not report['passed'] or len(report['checks']) < 22:
        raise RuntimeError('iOS runtime behavior verification failed')


if __name__ == '__main__':
    main()

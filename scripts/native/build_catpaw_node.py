"""Rebuild pinned nodejs-mobile for Android; no prebuilt binary header patching."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from native_elf import inspect_elf

SOURCE_COMMIT = 'd9552e0e01ed5bdbe12a31d1ce6c0877a4f39580'
NDK_VERSION = '28.2.13676358'
ABIS = {'arm': ('armeabi-v7a', 'arm-linux-androideabi'),
        'arm64': ('arm64-v8a', 'aarch64-linux-android'),
        'x86_64': ('x86_64', 'x86_64-linux-android')}


def build(source, ndk, output, arch):
    source, ndk, output = source.resolve(), ndk.resolve(), output.resolve()
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip() != SOURCE_COMMIT:
        raise ValueError('Unexpected Node source revision')
    if NDK_VERSION not in (ndk/'source.properties').read_text():
        raise ValueError('Unexpected NDK revision')
    toolchain = ndk/'toolchains/llvm/prebuilt/linux-x86_64'
    # Keep target and host compilers independent. The upstream configurator
    # overwrites host CC/CXX with GCC; use the pinned NDK host compiler instead.
    config = source/'android_configure.py'
    text = config.read_text()
    for key, command in [('CC_host', 'gcc'), ('CXX_host', 'g++')]:
        old = f"os.environ['{key}'] = os.popen('command -v {command}').read().strip()"
        if text.count(old) != 1:
            raise ValueError('Upstream host compiler patch context changed')
        text = text.replace(old, f"os.environ.setdefault('{key}', os.popen('command -v {command}').read().strip())")
    # The application still supports API 23. Compile against that API so newer
    # Android imports fail at link time instead of failing on an older phone.
    old = 'if int(sys.argv[2]) < 24:'
    if text.count(old) != 1:
        raise ValueError('Upstream minimum API patch context changed')
    text = text.replace(old, 'if int(sys.argv[2]) < 23:')
    config.write_text(text)
    env = dict(os.environ, CC_host=str(toolchain/'bin/clang'), CXX_host=str(toolchain/'bin/clang++'),
               LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384',
               PATH=str(toolchain/'bin')+os.pathsep+os.environ['PATH'])
    subprocess.run(['./android-configure', str(ndk), '23', arch], cwd=source, env=env, check=True)
    subprocess.run(['make', '-j2'], cwd=source, env=env, check=True)
    abi, triple = ABIS[arch]
    library = next((source/p for p in ('out/Release/lib.target/libnode.so', 'out/Release/obj.target/libnode.so') if (source/p).is_file()), None)
    if library is None:
        raise ValueError('Node shared library was not built')
    target = output/'bin'/abi
    target.mkdir(parents=True, exist_ok=True)
    records = []
    for name, path in [('libnode.so', library), ('libc++_shared.so', toolchain/'sysroot/usr/lib'/triple/'libc++_shared.so')]:
        data = path.read_bytes()
        evidence = inspect_elf(data)
        if not evidence['compatible_16kb']:
            raise ValueError('Built library is not 16 KB aligned: '+name)
        shutil.copy2(path, target/name)
        records.append({'name': name, 'sha256': hashlib.sha256(data).hexdigest(), **evidence})
    shutil.copy2(source/'LICENSE', output/'LICENSE')
    (output/f'provenance-{abi}.json').write_text(json.dumps({'source': 'https://github.com/nodejs-mobile/nodejs-mobile', 'commit': SOURCE_COMMIT, 'ndk': NDK_VERSION, 'api': 23, 'abi': abi, 'libraries': records}, indent=2)+'\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--arch', choices=ABIS, required=True)
    args = parser.parse_args()
    build(args.source, args.ndk, args.output, args.arch)

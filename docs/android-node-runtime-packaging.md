# Android Node runtime packaging

The default Android download remains the pinned upstream runtime until a reviewed
runtime archive is published and its URL and SHA-256 are updated in
`scripts/prepare_catpaw_runtime.py`. Merely building aligned libraries does not
change the default download or enable the application on 16 KB devices.

## Build and preserve the original artifacts

`scripts/native/build_catpaw_node.py` fixes the upstream revision, Android API 23,
NDK revision, and compatibility patches. Keep each workflow artifact's original
`bin/<abi>/libnode.so`, `libc++_shared.so`, `provenance-<abi>.json`, and `LICENSE`.
Do not replace the C++ library with one from a different local NDK or change ELF
headers. The 32-bit ARMv7 NDK C++ library legitimately uses 4 KB LOAD alignment;
the Android large-page ABI applies to the 64-bit processes.

Assemble all three artifacts with their actual build run IDs:

```text
python scripts/native/catpaw_runtime_bundle.py \
  --input armeabi-v7a=<arm-artifact-directory> \
  --input arm64-v8a=<arm64-artifact-directory> \
  --input x86_64=<x86_64-artifact-directory> \
  --run armeabi-v7a=<run-id> \
  --run arm64-v8a=<run-id> \
  --run x86_64=<run-id> \
  --output <private-output>/marco-node-android.zip
```

The packer requires all three ABIs. It checks source revision, NDK, API,
compatibility-patch provenance, library digests, architecture, and every 64-bit
LOAD segment. It preserves binary bytes and licenses, rejects unexpected archive
members, and emits a ZIP plus its SHA-256 sidecar. Entry order, permissions and
timestamps are fixed; use the same Python/zlib versions for byte-identical
compressed archive reproduction. The archive records the original CI run IDs.

## Validate a candidate without changing release pins

Set `MARCOMOVIE_ANDROID_RUNTIME_ARCHIVE` to that local ZIP and
`MARCOMOVIE_ANDROID_RUNTIME_SHA256` to its independently recorded SHA-256.
Both variables are required. Then run the ordinary Android build with the same
pinned NDK recorded in the artifacts. This is an explicit verification entry point,
not a fallback download location. The existing default runtime remains selected
when neither variable is set.

The build packages `libnode.so` and `libc++_shared.so` from the verified archive,
and compiles the app's `libmarco_node.so` using the matching NDK. It rechecks all
three binary architectures and the imported hashes. For each packaged 64-bit ABI,
all three libraries must pass ELF alignment checks before the build emits
`assets/catpaw/runtime-capabilities.json`. The capability contains each exact
library SHA-256; the production runtime compares these against installed bytes
before allowing a page size above 4096. No 16 KB capability is issued for ARMv7.

Build evidence is written to
`android/build/catpaw/runtime-build-evidence.json`. A legacy-runtime build removes
stale capability and evidence files from previous builds, so sharing a local cache
cannot enable an unverified runtime.

The TV and mobile APK resource sets are filtered independently. A pure ARMv7 APK
has no large-page capability file; an APK containing ARM64 has only the capability
for its actual packaged three-library set. Final verification reopens the APK and
checks all original runtime hashes, the freshly built bridge hash, capability
contents, ELF architectures and ZIP/LOAD alignment.

## Acceptance and publication boundaries

Run `python scripts/test_catpaw_runtime_bundle.py` for packaging failure cases.
These synthetic fixtures do not execute native code. The separate embedded Node
instrumentation exercises real CatPaw and DRPY calls on API 23/4 KB, API 35/4 KB
and API 35/16 KB emulators. It uses ordinary, non-incremental APK installation;
the experimental x86_64 16 KB image's incremental filesystem can fail while
mapping a tiny JNI library even when the exact same library runs after an
ordinary installation.

The successful isolated Node probe omits unrelated native codecs and QuickJS
libraries. It therefore does not establish whole-APK 16 KB compatibility or
physical Xiaomi device behavior. Preserve the full APK native-library report
and validate other dependencies separately. ARMv7 API 23 remains a supported
build target and must not be replaced with a 64-bit-only package.

After candidate verification, publish the immutable runtime ZIP and SHA sidecar
as a runtime asset and set the fixed Android URL and digest in the existing
`ASSETS` table. This is separate from application versioning and app publication.

`.github/workflows/native-runtime-package.yml` performs the complete candidate
flow on the pinned NDK. It accepts the three completed runtime build run IDs,
validates them as decimal values, downloads the artifacts, creates the archive,
builds both full APKs through the explicit local override, and invokes
`scripts/verify_android_runtime_package.py`. Its Actions artifact contains the
runtime ZIP/SHA sidecar, full candidate APKs, build evidence and an independent
verification report. It neither changes download pins nor publishes an app
release. A report with `node_packaging_verified: true` and
`whole_apk_16kb: false` explicitly lists the other libraries still needing work.
Use the verifier's `--require-whole-apk` option when gating final 16 KB acceptance.

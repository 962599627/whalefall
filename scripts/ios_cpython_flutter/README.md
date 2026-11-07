# Experimental Flutter / CPython coexistence probe

This creates a separate disposable Flutter app, not Marco Movie. It never routes
user subscriptions to CPython or changes the production app or release workflow.
The native bridge accepts **only the bundled `synthetic-v1` fixture**, not arbitrary
code, modules, native libraries, external source endpoints or filesystem paths.

Run on macOS with the reviewed Flutter **3.38.9**, Xcode/iOS Simulator and CocoaPods:

```sh
python scripts/ios_cpython_flutter/run_flutter_probe.py \
  --arch arm64 --output-dir /tmp/cpython-flutter-results \
  --cache-dir /tmp/cpython-downloads
```

Use `--arch x86_64` on an Intel Mac. `--udid` can select an available iPhone
simulator; otherwise the shared runner selects the oldest available iOS >=14.
`--stage-only` is possible on other hosts, and deliberately reports `passed:false`
and `runtime_tested:false`. It cannot establish iOS or Flutter acceptance.

The runner imports `scripts/ios_cpython/run_cpython_probe.py` for the same locked
CPython SDK/wheels, safe extraction, framework mapping/signing and app-specific
simulator diagnostics. It imports `prepare_catpaw_runtime.py` for the existing
pinned Node18 iOS runtime. The production CatPaw native bridge and QuickJS package
are copied to the new temporary project; the source checkout is not modified.
All source hashes and dependency inputs are recorded. No framework binary is
committed here. Only this disposable app is terminated after results/timeouts.

The experimental MethodChannel exposes capabilities/open/call/release, unguessable
session handles, two own-GIL subinterpreters and explicit method/argument gates.
Each fixture source has a separate interpreter and Spider instance; calls are
serialized on one native worker thread. Python source-created threads are disabled
in this narrow experiment. It does not expose cancellation or claim a hard budget.
Python and its native extensions still share the process/app permissions.

The Flutter app starts the existing local embedded Node18 bridge and executes
QuickJS through its real Dart FFI package. Node serves only synthetic localhost
catalog/search/detail/player/binary data. Native CPython requests/bs4 traverse all
six Spider methods; a binary proxy callback verifies exact non-UTF8 bytes, headers,
status and retained same-instance state. This first probe tests the callback
contract, **not a media-player proxy lease or actual media decoding**.

Checks also cover method/source/origin rejection, opaque handles, capacity,
isolated module state, exceptions without session corruption, release/stale-handle
behavior, recreation, and continued Node/QuickJS/UI operation. Validation requires
all 42 named checks, each uniquely true, actual Python 3.14.10, Node18.20.4 and
the selected native architecture. An aggregate passed flag is insufficient.

The same built app runs twice in separate processes: Python first, then Node
first. A fixed signed fixture-order resource selects the order; the non-starting
capability query verifies lazy Python initialization. Both runs record actual
SIGPIPE/SIGINT states before/after Python and after Node/QuickJS, retain separate
results and must pass. HTTP redirects are rejected; the fixture never follows
its deliberate off-loopback redirect negative case.

Artifacts: `inputs.json`, app-emitted `app-result.json`, host `result.json`,
`diagnostics/`, and a local `build.log` for compile failures. SDK/App directories
are test build outputs only. The existing `ios-runtime.yml` workflow can run this
manually with `experiment: cpython_flutter` and the desired `probe_arch`. It first
checks simulator availability and uploads only JSON evidence, app-scoped
diagnostics and the build log. Push events do not run this experiment. It does
not upload an application or publish a release.

Host-only protocol/validator checks:

```sh
python scripts/ios_cpython_flutter/local_checks.py \
  --node /path/to/node18 --cache-dir /tmp/cpython-downloads \
  --output-dir /tmp/cpython-flutter-host-checks
```

Still unverified until separately exercised: actual iPhone/iOS14, background
resume, player memory/threads and playback leases, arbitrary user Python sources,
native third-party wheels, secure filesystem isolation, and hard cancellation of
Python/native work. Existing production script runtimes remain unchanged.

## Recorded simulator result

[Run 42407978951](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42407978951)
at `fc2eaef` completed successfully on ARM64/iOS 18.5. Python-first and Node-first
launches each passed all 42 unique required checks, for 84 in total, with actual
CPython 3.14.10 and Node 18.20.4. All 112 recorded repository-input hashes match
that exact commit. Both launch orders preserve the other engine's SIGINT state
and leave SIGPIPE ignored. The bounded readiness observation preceded the app
run; it was not counted as an application check. Intel Flutter execution and the
production/physical-device limitations above remain unverified. This result
does not enable arbitrary Python subscriptions in Marco Movie.

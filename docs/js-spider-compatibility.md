# JavaScript Spider entrypoints

The shared module boundary accepts ES module default/named exports and CommonJS
exports. Sources may export an object, or a synchronous class with Spider methods;
the class is instantiated once when its retained source session is created.

The host continues to call its existing short entrypoints. When a short method is
absent, the shared facade delegates to the corresponding full method:

| Host call | Full Spider method | Adaptation |
| --- | --- | --- |
| `home(filter)` | `homeContent(filter)` | Preserve the boolean |
| `homeVod()` | `homeVideoContent()` | No arguments |
| `category(id, page, filter, extend)` | `categoryContent(...)` | Page is a string; keep the filter object |
| `detail(id)` | `detailContent(ids)` | A scalar becomes a one-element array; an existing array stays flat and preserves duplicates/order |
| `search(word, quick, page)` | `searchContent(...)` | Page is a string, default `"1"` |
| `play(flag, id, vip)` | `playerContent(...)` | Preserve flag, opaque ID and VIP array |

Existing short methods take precedence. Calls keep the original source instance
as `this`, preserve synchronous values and Promises, and propagate source errors.
The facade does not mutate frozen exports or module namespaces. Missing methods
remain missing; no fabricated lists or playback URLs are returned.

For a source using only full entrypoint names, `init` receives a string. Flutter
keeps the original imported string value separately from the existing normalized
`site.ext`, including leading/trailing whitespace. This survives source copying,
source-session identity and backup/restore. An object-valued `ext` is serialized
as JSON; its original textual formatting is not claimed to be preserved. Legacy
short-name or mixed-name sources retain their previous init argument types.

Legacy `__jsEvalReturn` sources can use the same method aliases, while retaining
their Cat init metadata (`stype`, `skey`, `ext`) and existing detail-array calling
convention. Python, DRPY and CatPaw use their separate existing adapters.

This is entrypoint compatibility, not the complete current FongMi host API.
In particular, `import {Spider} from "catvod"` is not implemented by this change.
It needs a separate compatible host-module integration; the app does not install a virtual CatVod
base class, network/dialog/WASM APIs, or empty stubs. The independent class fixtures
used by these tests declare their own methods. Current full-name signatures and
the separate host-module requirement are documented in the upstream
[Spider guide](https://github.com/FongMi/TV/blob/4512b4c9f82534ff66acfde06ccdd68f35b074f0/website/app/spider/page.tsx).

Verification: `scripts/test_js_module_interop.cjs` exercises the shipped shared
loader in Node; `flutter/test/js_full_spider_test.dart` executes the same asset in
the production QuickJS bridge, including imported JSON, local ZIPs, class state,
full-name playback through the local proxy, raw extensions and backup restoration.
These tests are not physical-device or media-decoder acceptance.

At `698e165`, [iOS native run 42406788685](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42406788685)
passed 192 unique checks on each of ARM64/iOS 26.2 and x86_64/iOS 18.5.
Four new checks exercise the complete entrypoints in production QuickJS. All
30 recorded production inputs and 11 fixture inputs match that exact commit.
[Android 6 run 42406788581](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42406788581)
passed 67 source-host checks, including nine new complete-entrypoint checks,
along with the home and subtitle probes. This is an API 23 x86 emulator;
the reported APK hashes originate from its build, not a separately downloaded
APK inspection. These results predate the separate CatVod host-module work.

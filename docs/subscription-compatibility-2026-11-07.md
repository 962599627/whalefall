# Subscription and Android 6 regression work

This records implementation and validation on the repair branch. It is not a release announcement or a claim that every imported source plays.

## User-visible fixes

- Listing redirects containing a numeric catalogue ID now search by the visible film title. A real numeric title remains valid. The Android phone/TV route and shared Flutter title extraction are covered separately.
- Home uses one history shelf, with the latest entry first. The TV's separate resume section has been removed; the existing resume actions and saved position remain intact. The stored preference key remains compatible with older backups.
- Subtitle and intro/outro adjustment use the application's own callback type instead of `java.util.function.IntConsumer`, which is absent on Android 6. The bytecode check covers all compiled application classes, not just a source-text match.

## Subscription changes

- UTF-16 BOM text, content-based PHP responses, single-site JSON and root site arrays are recognized. PHP is an HTTP endpoint: its response determines which parser is used.
- Local ZIPs can contain a configuration, a playlist, or an unambiguous Spider script entry with sibling modules. Ordinary JSON helper data and README files do not take precedence over a script. The Flutter archive budget is checked before candidate contents are decompressed.
- Relative HTTP playlist paths resolve against their origin. Plain error CSV text is not accepted as a relative stream.
- Script hosts accept CommonJS/JSON helpers and named/default ES module exports. Android's pinned QuickJS wrapper drains Promise jobs between evaluations, allowing asynchronous initialization and Spider methods that settle locally. Unsettled timers/event-loop work and unsupported Node modules fail explicitly.
- Compact and side-effect ES module imports, re-exports and content-identified PHP script responses use the same local host. Module dependency discovery skips comments and string contents.
- The shared Python adapter expands byte/URL codecs and HTTP request/response behavior, including `requests.Session`, scoped cookies, redirects and session isolation. Compatibility is exercised through the real embedded engine, rather than checking only whether a `.py` file imports. Multipart uploads, streamed responses, proxy adapters and native Python extensions remain outside this host's implementation.
- Script HTTP requests retain authentication on same-origin redirects and clear sensitive headers when the protocol, host or port changes. URL user information becomes Basic authentication and is omitted from the returned URL.

## Verification boundaries

The checked-in tests use local/synthetic inputs and local HTTP services. The API 23 emulator workflow exercises production settings/player subtitle controls, history layout and QuickJS/Python JNI. Its application fixture has identical production bytecode but only the x86_64 QuickJS library; it does not validate ARM video decoding or CatPaw Node.

The CatPaw rebuild separately applies libuv's existing pre-Android-7 barrier fallback to fix the API 23 linker failure. A successful source build and ELF alignment check are prerequisites, not proof of playback on a 16 KB device.

Still incomplete: the 33 proprietary plugin route ports, full DRPY rule-only compatibility, arbitrary CPython/native packages, and actual supplied-source playback on iPhone. Lite has standard CMS support but no general JS/Python/JAR runtime. The [iPhone publication gate](ios-source-acceptance.md) remains in force; do not publish the validation IPA as a completed compatibility release.

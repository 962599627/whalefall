# iPhone subscription release gate

The owner requires working TXT, JS, JSON, Python, PHP endpoints and local packages before the next iPhone package is published. This includes the previously requested multi-repository subscriptions, authenticated CatPaw `.js.md5`, Emby, and outstanding plugin routes. All runtimes must execute locally; a desktop/Android bridge is not an acceptable substitute.

This is an acceptance checklist, **not a declaration of compatibility**. The 0.5.1 IPA produced by workflow 42356166467 is validation-only and must not replace the official 0.5.0 iPhone download.

| Input | Existing implementation | Evidence still required before release |
| --- | --- | --- |
| TXT / M3U | Content-based playlist parsing | Actual supplied playlists load their groups/channels and play on iPhone, including relative URLs and required request headers |
| JSON / XML | CMS interfaces and subscription configuration parsing | Supplied subscriptions complete catalogue, search, detail, episode selection and playback; failures of individual sites stay distinguishable |
| JS | QuickJS Spider / ES module host; local CatPaw host for matching packages | Requested scripts, modules and synchronous DRPY APIs work end to end; script import alone is insufficient |
| Python | Skulpt Spider, basic requests / JSON and sibling modules | Requested Python modules and parser dependencies work; lxml/native extensions/full CPython are not currently claimed supported |
| PHP / extensionless endpoints | Fetch endpoint and parse its actual response content | The supplied endpoint is reachable and its actual configuration/API format works; `.php` does not identify a client-side executable format |
| Local package | ZIP entry configuration, relative script/module paths and lists; Flutter DRPY packages retain nested local helpers and JSON data | Each requested package's entrypoint, dependencies, files and playback resolve on iPhone without an external device |
| Multi-repository | Nested warehouse import and ownership | The supplied warehouse and selected child subscriptions pass the same playback checks, not just directory import |
| Authenticated `.js.md5` / CatPaw | Authentication, digest, local service host and proxy integration | Actual supplied service package completes local startup, catalogue, search, detail, playback/proxy and restart on iPhone |
| Emby | Login, libraries, episodes, playback negotiation and progress reporting | Real server/session playback on iPhone with the required direct/transcode and progress behavior |
| Android / Guard plugins | Selected explicit portable adapters; reviewed Dm84, Auete, Bttwoo and T4 paths are scoped to exact imported artifact versions | All 33 routes still require individual platform/playback acceptance. Auete and one T4 search path currently require manual website verification; Appgz remains experimental and is not enabled in application dispatch. Partial protocol/media-prefix checks do not complete this gate |

For each representative source, retain separate results for import, catalogue/search, detail/episodes, resolved media, actual playback and application restart. Record the tested iPhone/iOS and package version. Mark a source as incomplete if only its file type or catalogue is recognized. Do not hide incomplete sources or relabel an ordinary HTTP adapter as a plugin port.

The user's supplied configuration/service URLs and authentication details remain private test data. Dead endpoints, unavailable content or authentication requirements must be recorded separately from parser/runtime failures, rather than silently reported as successful adaptation.

## Embedded runtime follow-up

The production iOS Node startup implementation is shared with a simulator-only native regression app. Its test scope is Node startup, real CatPaw workers, the jitless Fetch shim, DRPY catalogue/search/detail, nested local helpers, local proxy text/binary/range responses, and source reset/reopen. The workflow does not publish an IPA and must retain its actual result before being reported as passed. It does not exercise Flutter widgets, a physical iPhone, or video decoding.

The local DRPY proxy implements the pinned engine's three-element status/type/body contract. Text and byte arrays are accepted; unsupported response shapes fail explicitly. Playback URLs stay tied to their source, and active proxy playback is protected from background-source eviction. Closing the iOS host clears source workers/settings sessions without terminating the process-wide Node runtime; failed cleanup must be retried before loading new sources.

The same simulator fixture also compiles the production iOS QuickJS bridge and
loads the production Python adapter assets. It checks ES modules, Python sibling
modules, asynchronous response handling, byte codecs and the native execution
deadline. Its Python HTTP response is synthetic; the Node Fetch check uses a real
local socket. Neither is a claim of arbitrary native Python package support.

CatPaw and script detail responses may omit catalogue IDs and metadata. The
client now merges those responses with the selected catalogue item, retaining its
identity for history/reopen without replacing returned episode lists. Non-Error
script exceptions retain their original failure instead of producing a Dart cast
error.

CatPaw signed media routes under `/spider/<site>/<version>/proxy/` now retain
their source-scoped local address, signature query, relative HLS segments and
keys. Only GET/HEAD playback access is allowed through this additional route;
normalized traversal and management paths remain rejected. Private checks on the
supplied package reproduced a local `route_not_exposed` failure and, after this
fix, retrieved the returned HLS manifest. This is protocol evidence, not decoder
or physical-iPhone acceptance. A different sampled route still returned an empty
playback URL; another returned HTTP 401. Those source results are not counted as
working playback.

## November 7 runtime and protocol evidence

[Native runtime run 42378486540](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42378486540),
at commit `6134540`, passed 24 checks on both ARM64/iOS 26.2 and Intel/iOS 18.5
simulators. In addition to Node/CatPaw/DRPY, it exercised production QuickJS and
Python cookie iteration and binary-body bridging. Python socket transport is
tested separately with local HTTP fixtures; its native probe response is
synthetic. Neither result establishes physical-iPhone video decoding.

The T4 follow-up at `19f993f` passed [28 native checks on both simulator
architectures](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42380192628),
including the generated adapter's catalogue, pagination, opaque detail ID and
local HLS-manifest request. [Full iOS build
42380195025](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42380195025)
also passed 172 Flutter tests and produced a validation IPA. The first attempt
was blocked by a CRLF/LF asset hash mismatch; asset generation now normalizes
the bytes and CI checks the manifest before building. No official iPhone
release was created by either workflow.

Python binary request bodies now cross the host boundary as bounded, validated
Base64 and are written as bytes by Flutter and Android. Session cookies expose
their names, values, domains, paths and flags without confusing same-name cookies
from different domains. These additions do not supply BeautifulSoup, subprocess,
native OCR or a complete CPython standard library.

The subsequent Flutter lifecycle fix keeps JS/Python state in a source-owned
isolate across catalogue, detail and the player's separate client instance.
Configuration, subscription identity and privacy scope form the session key.
Calls to one source are serialized; cancellation or failure discards that
runtime. Both the native deadline and Python execution clock restart per
operation; browsing time between calls does not consume the next call's budget.
Pending timers and transport
requests cannot leak into the next operation. Locking private space, removing
subscriptions and closing the shell release the corresponding sessions, while
generation checks reject source loads that finish after removal.

This is bounded in-memory state, not persistent cookie files: at most six source
contexts are retained, idle contexts may be evicted for a newly selected source,
and app exit clears them. Local Flutter regressions exercise real loopback HTTP
cookies and episode-cache continuity, separate subscription/private contexts,
deadline renewal, an idle interval exceeding Python's eight-second limit,
cancellation, close-while-busy and timer cleanup. Native iOS
and full-build evidence for this new change must be recorded separately; the
earlier `19f993f` checks do not cover it. FY still needs its actual parser/module
and verification flow, so this lifecycle repair does not enable that route or
establish arbitrary Python-source compatibility.

T4 now extracts each site's encrypted extension as data from an exact reviewed
artifact, then runs the CMS protocol in the local DRPY worker. Pagination and
opaque catalogue/episode identifiers are retained. The original T4 category
contract does not forward extended filters, so unsupported filters are reported
explicitly. A private Windows Flutter client check retrieved catalogues, details
and HLS manifests for two supplied entries; one search still required website
verification. A separate earlier media request returned HTTP 403. These sampled
results are not full stream/decoder or iPhone acceptance.

Appgz's original encrypted protocol has a separate experimental implementation
and synthetic tests. It is distinct from a similarly named CatPaw entry. Actual
upstream requests have not completed the catalogue/episode/playback path, so it
is not advertised or enabled by the application's portable-adapter dispatcher.

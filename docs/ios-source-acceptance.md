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
| Local package | ZIP entry configuration, relative script/module paths and lists | Each requested package's entrypoint, dependencies, files and playback resolve on iPhone without an external device |
| Multi-repository | Nested warehouse import and ownership | The supplied warehouse and selected child subscriptions pass the same playback checks, not just directory import |
| Authenticated `.js.md5` / CatPaw | Authentication, digest, local service host and proxy integration | Actual supplied service package completes local startup, catalogue, search, detail, playback/proxy and restart on iPhone |
| Emby | Login, libraries, episodes, playback negotiation and progress reporting | Real server/session playback on iPhone with the required direct/transcode and progress behavior |
| Android / Guard plugins | Selected explicit portable adapters; reviewed Dm84, Auete and Bttwoo paths are scoped to exact imported artifact versions | All 33 routes still require individual platform/playback acceptance. Auete search currently requires manual website verification; partial protocol/media-prefix checks do not complete this gate |

For each representative source, retain separate results for import, catalogue/search, detail/episodes, resolved media, actual playback and application restart. Record the tested iPhone/iOS and package version. Mark a source as incomplete if only its file type or catalogue is recognized. Do not hide incomplete sources or relabel an ordinary HTTP adapter as a plugin port.

The user's supplied configuration/service URLs and authentication details remain private test data. Dead endpoints, unavailable content or authentication requirements must be recorded separately from parser/runtime failures, rather than silently reported as successful adaptation.

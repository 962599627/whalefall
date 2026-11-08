# Android phone compatibility work

The current work order is Android phone/tablet, then iPhone, then Windows/macOS.
Each stage includes subscription import, catalogue, search, details, playback and
live channels. Passing parser fixtures alone does not complete a device stage.

## Live HTTP headers

TVBox-style live source and inline group `origin` fields now set the HTTP `Origin`
header. Existing `ua`, `referer`, `header` and `headers` handling is retained.
Source-declared headers stay attached to same-origin channel requests.

When a catalogue redirects across origins, its removed Authorization/Cookie
headers are no longer restored into the resulting stream. The original source
object is unchanged, so a later independent load can still use its credentials.

The isolated host-Java regression covers 21 catalogue assertions and five real
loopback-network checks: mixed-case credential removal, no restoration into
streams, PHP endpoint/UTF-16 decoding with relative media URLs, same-origin
credential retention and cancellation of in-flight requests. These are client
transport checks; no claim is made that all remote channels or Xiaomi firmware
now play successfully.

## Legacy live directory wrappers

The reader now recognizes TVBox `proxy://do=live&type=txt&ext=...` and the
equivalent loopback `/proxy` form. Plain percent-encoded URLs, standard/URL-safe
Base64, and local-package-relative lists use the existing bounded HTTP/file
reader. This is a catalogue reference, so it does not start an Android JAR host.
Nested placeholders are separated from ordinary inline channels without losing
either; group headers and the legacy `group` name are retained. Invalid,
duplicate or recursive references fail with a directory error, and a remote
configuration cannot use this wrapper to read a local file.

The protocol behavior was checked against the upstream
[TVBox configuration reader](https://github.com/takagen99/Box/blob/main/app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java).
Local regression now covers 31 catalogue assertions and 12 real-loopback HTTP
checks. Device execution of the six new local-package assertions is tracked
separately in the Android 6 source-host workflow; parser success is not playback.
At `4424cbe`, [run 42439415853](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42439415853)
passed all six new assertions on the actual API 23 runtime (86 total source-host
checks), including local-package lookup, inherited headers and preserving inline
channels. This still does not verify third-party media playback.

## Separate update editions

[Update isolation](android-update-isolation.md) records the phone/TV feeds,
explicit package names, old-name migration and APK verification tests. This work
does not publish a new installer or change the live mirror.

## Remaining acceptance

- Exercise imported user subscriptions on the Android phone runtime, including
  HTTP(S) JSON/PHP endpoints, text lists, JS/Python scripts, local packages,
  multi-repository configurations and live channels.
- The [published-default Node packaging path](android-node-runtime-packaging.md)
  passed separately from the candidate and source-runtime checks at `b9b5444`.
  Continue physical phone decoder, source and playback validation; aligned
  libraries alone do not complete this stage.
- Keep JAR/runtime limitations, remote service failures and successful playback
  distinct. The original cross-platform plugin obligations and iPhone publication
  gate remain open.

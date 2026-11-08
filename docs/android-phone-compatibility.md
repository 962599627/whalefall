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

## Separate update editions

[Update isolation](android-update-isolation.md) records the phone/TV feeds,
explicit package names, old-name migration and APK verification tests. This work
does not publish a new installer or change the live mirror.

## Remaining acceptance

- Exercise imported user subscriptions on the Android phone runtime, including
  HTTP(S) JSON/PHP endpoints, text lists, JS/Python scripts, local packages,
  multi-repository configurations and live channels.
- Promote the reviewed 16 KB Node runtime only after verifying its exact archive,
  final packaged libraries and actual runtime behavior. The old default pin is
  still present until explicitly updated; an aligned candidate is not an enabled
  production runtime.
- Keep JAR/runtime limitations, remote service failures and successful playback
  distinct. The original cross-platform plugin obligations and iPhone publication
  gate remain open.

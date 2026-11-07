# Independent platform releases

The owner allows each platform to release independently after its own checks. Version numbers do not have to match. A release must name its platform, build commit, tested fixes and remaining limitations.

**iPhone publication is explicitly on hold:** the owner subsequently requires all requested subscription types and plugin routes to work before an iPhone release. The 0.5.1 iOS workflow artifact is validation-only. Do not create an official iOS release or change its mirror download until the requested import, catalogue, search, detail and playback paths work independently on that platform. UI tests and a successful IPA build alone do not meet this gate. The 33 outstanding Guard plugin ports remain incomplete.

Desktop/Apple tags use `windows-vX.Y.Z`, `ios-vX.Y.Z` and `macos-vX.Y.Z`. They do not trigger the legacy `v*` all-platform publisher. Build the desired platform with the existing workflow dispatch, verify the artifact, and attach the package, SHA-256 file and corresponding source archive before publishing. Do not replace existing versioned assets. Keep the legacy all-platform release as GitHub's global latest so old clients are not directed to another platform's package.

Clients from 0.5.1 first read their own `movie/updates/{platform}.json` feed, then select the newest matching official GitHub release containing that platform's package. Other platform versions, drafts and prereleases are ignored. Clients at 0.5.0 still use the legacy shared feed; install the first independent platform update from the download page, then subsequent updates use the new platform feed.

The mirror runs `scripts/sync_platform_packages.py`. It patches only existing download-card package URLs, byte sizes and hashes, and writes the relevant platform feed. It does not render a new HTML page or alter CSS, layout, images, prose, unrelated packages or legacy Android update channels. Large files retain the site's existing verified GitHub download behavior. Corresponding source remains attached to each platform's release.

## Published Windows update

[Windows 0.5.1](https://github.com/Pilipala9919/MarcoMovie/releases/tag/windows-v0.5.1) was published from `56b2eb80b93bae98b8754952d779bff1b7a9582a` after [build 42356451432](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42356451432) passed 83 Flutter tests and produced the installer/portable packages. Downloaded packages passed checksum/ZIP checks, the app and installer report 0.5.1, and all six uploaded package/source/checksum files match the local verified bytes. Native fullscreen UI evidence is recorded in `desktop-fullscreen-2026-11-07.md`.

The mirror deployment at commit `fe533ad` passed Cloudflare Pages checks. Live checks confirmed the two Windows download URLs, SHA-256 files and platform update feed. Existing CSS, the iPhone 0.5.0 download and legacy Android update channel are unchanged. No iPhone 0.5.1 release was created; its workflow artifact remains validation-only under the gate above.

## TV seek follow-up

Remote left/right taps remain 15 seconds. After a 500 ms hold delay, preview speed starts at 30 playback seconds per held second and increases linearly by 30 per second, capped at 240. The target uses elapsed time rather than key-repeat counts. Release commits the target once; cancel/back aborts, reversing direction resets acceleration, and targets clamp at the media boundaries. The status displays the current preview speed. Twenty-four seek checks cover the curve, cap, jitter, symmetry, cancellation and boundaries; this is not physical remote acceptance.

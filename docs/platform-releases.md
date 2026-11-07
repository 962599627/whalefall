# Independent platform releases

The owner allows each platform to release independently after its own checks. Version numbers do not have to match. A release must name its platform, build commit, tested fixes and remaining limitations.

Desktop/Apple tags use `windows-vX.Y.Z`, `ios-vX.Y.Z` and `macos-vX.Y.Z`. They do not trigger the legacy `v*` all-platform publisher. Build the desired platform with the existing workflow dispatch, verify the artifact, and attach the package, SHA-256 file and corresponding source archive before publishing. Do not replace existing versioned assets. Keep the legacy all-platform release as GitHub's global latest so old clients are not directed to another platform's package.

Clients from 0.5.1 first read their own `movie/updates/{platform}.json` feed, then select the newest matching official GitHub release containing that platform's package. Other platform versions, drafts and prereleases are ignored. Clients at 0.5.0 still use the legacy shared feed; install the first independent platform update from the download page, then subsequent updates use the new platform feed.

The mirror runs `scripts/sync_platform_packages.py`. It patches only existing download-card package URLs, byte sizes and hashes, and writes the relevant platform feed. It does not render a new HTML page or alter CSS, layout, images, prose, unrelated packages or legacy Android update channels. Large files retain the site's existing verified GitHub download behavior. Corresponding source remains attached to each platform's release.

## TV seek follow-up

Remote left/right taps remain 15 seconds. After a 500 ms hold delay, preview speed starts at 30 playback seconds per held second and increases linearly by 30 per second, capped at 240. The target uses elapsed time rather than key-repeat counts. Release commits the target once; cancel/back aborts, reversing direction resets acceleration, and targets clamp at the media boundaries. The status displays the current preview speed. Twenty-four seek checks cover the curve, cap, jitter, symmetry, cancellation and boundaries; this is not physical remote acceptance.

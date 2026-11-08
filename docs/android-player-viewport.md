# Android adaptive player controls

Phone controls use the measured playback layer rather than cached display size
or a requested orientation. A real layout change, including a smaller parent
viewport, schedules a new placement. Rotation requests no longer swap cached
width and height before the window has actually changed.

The control area intersects the visible parent bounds with the window's cutout,
gesture and navigation insets. Navigation space remains reserved while bars are
temporarily hidden, and window insets are translated into the playback layer's
coordinates so an already inset or centered container is not padded twice.
The video surface retains its own aspect-ratio behavior.

Progress width is limited to the space left beside the right tool Dock. The
former 220 dp minimum cannot force the bar outside a smaller viewport. Bottom
placement uses the actual available height and, in portrait, the measured Dock
height. No user-adjustable offset setting is introduced.

## Verification scope

The isolated local build and API 23 callback checks pass. The Android 16 player
workflow exercises gesture and three-button navigation separately, with actual
system-player seeking on a generated local audio fixture. Each run checks
normal landscape, reverse landscape, portrait, a deliberately smaller parent
viewport and restoration to the full viewport. It checks the whole timeline,
progress region and Dock against visible safe bounds, and verifies touch seeking.

Inspect that workflow's results and screenshots before recording a runtime
pass. The smaller viewport is an intentional layout fixture, not a reproduced
Xiaomi firmware fault. These checks omit ARM decoder libraries and do not
establish real Redmi/Xiaomi video-decoder or third-party-source acceptance.

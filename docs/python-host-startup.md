# Python source startup work

## Smaller HTML dependency at `4a631bd`

The dedicated HTML asset removes unrelated DRPY dependencies. Retained evidence:

- [Android 6 TV](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42447209942)
  passed 122 source, 25 subtitle and 96 home/history assertions. The HTML fixture
  initialized in 970 ms on this emulator.
- [Windows/macOS](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42447209951)
  each passed all 36 selected HTTP/Python tests without skips.
- [Native iOS](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42447209919)
  passed 200 unique checks on each of ARM64 and Intel, both iOS 18.5. Production
  and fixture hashes, including the new parser, matched the exact commit.
- [Android 16 phone](https://github.com/Pilipala9919/MarcoMovie/actions/runs/42447210008)
  passed the previously failing HTML initialization in 7085 ms, then its HTML,
  regex, JSON, IO, detail and play contracts. It failed after 42 assertions while
  initializing a separate source importing `requests`, before the HTTP session
  or T4 tests. This remains a failed phone run. Its error and logs are retained.

These are simulator/host fixtures, not physical devices or media-decoder results.
The measured times are observations of individual runs, not performance promises.

## Build-time compilation of fixed app modules

The bundled Skulpt compiler now prepares the app's fixed `requests` and JSON
compatibility modules during the build process. The portable JavaScript is
imported normally into each source's own interpreter. User Python scripts and
modules remain runtime inputs; this is not a source-specific port or a media URL
cache. Only byte-identical app module text selects the generated version, so an
imported override still executes its supplied Python implementation.

`prepare_python_modules.py` records exact compiler, generator and host-code
digests, and Android/Flutter preparation verifies the generated asset offline.
The reproducibility test recompiles both libraries and compares every byte.
Tests also check matching results, per-source state, imported overrides and an
expired deadline inside a generated module. The 8-second Python execution limit,
native deadlines and memory limits are unchanged. Code is precompiled; live
source objects, HTTP sessions and cookies are never shared.

This addresses repeated parsing/compilation of app-provided Python during source
initialization. System results for this follow-up must be recorded separately;
the earlier run above does not validate it. No installer is released here, and
the outstanding plugin ports and iPhone acceptance requirements remain open.

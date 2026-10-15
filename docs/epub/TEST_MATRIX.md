# P01-01 test matrix

Date: 2026-10-15. Starting fork commit: `d46d4de392bb40807eecb0815c1da54080dc92aa`. Upstream reference: `446f6df82c390ab760802c2fce3b677bce3f31c1`.

## Deterministic fixtures

All sources are authored for this diagnostic and dedicated CC0-1.0. ZIP entry order/timestamps and EPUB modified timestamps are fixed by `tools/epub-fixtures/generate.ps1`.

| Fixture | SHA-256 | Expected evidence |
| --- | --- | --- |
| EPUB 2 | `597efa82dc08712dbc5913ac5972dc53bca8ff598335c1d928d9a0c17af0d138` | NCX paths to three sections; illustration; `EPUB2-PASSAGE-RESTORE` |
| EPUB 3 | `5076cf581bee87b31208f8a402bb5f0a21a3296aa66d93bca6b998d2109203f9` | nested NAV; illustration; Arabic/RTL mixed text; `EPUB3-PASSAGE-ARABIC` |
| Image EPUB | `4a0192a287dd517e469cc4ca53c268d1b983977ecc279448f44a8732b9468f91` | two image XHTML spine items for existing Local-reader regression |
| External | `d32a9f1ee76d3b68f51cf489942c844d309e67c33c638e6b35539d0642b1b830` | loopback image/style rejected before socket |
| Corrupt | `d79d570c264bf028a8a240c9feb74da85637f71b443e3b5789a501ba4cc3e4ef` | controlled corrupt/unsupported error |

## Automated status

| Check | Actual result |
| --- | --- |
| Clean starting SHA / branch | Verified start SHA; working branch `codex/p01-01-epub-foundation` |
| Fixture regeneration | Passed locally; repeated EPUB 3 hash was identical and every valid ZIP had first, uncompressed `mimetype` entry |
| Static diff/XML checks | `git diff --check` passed; changed manifest/value XML parsed successfully |
| Locator and state-gate unit tests | Added; execution blocked before Gradle configuration |
| Debug instrumentation | Added for EPUB 2/3 structure, corrupt error, Activity recreation, and loopback socket trap; execution blocked |
| `spotlessCheck`, unit test, migration, debug/release assembly, dependency reports | Blocked before configuration by host `java.io.IOException: Unable to establish loopback connection` from `PipeImpl` / `UnixDomainSockets.connect` (`Invalid argument: connect`) |
| Minifier warnings/mapping and release install | Unverified; no keep rules added without evidence |

The same Gradle failure occurs with Android Studio JBR 21, explicit `JAVA_HOME`/`ANDROID_HOME`, `--no-daemon`, and an elevated host invocation. It predates project configuration, so no APK sizes, open timings, or memory values are available. No thresholds are invented.

## Device status

Installed SDK platforms include API 37; Pixel 10 and Pixel Tablet API 37 x86_64 AVD definitions exist. ADB daemon access from this execution environment failed, so Android/WebView versions, rendering, rotation, real `am kill` recovery, 20-cycle memory/file-descriptor observations, minified release install, coexistence/provider inspection, and image EPUB/CBZ/folder regressions remain unverified. API 26 provisioning is also unverified.

P01-01 must remain incomplete until all command and device evidence in the work package passes.

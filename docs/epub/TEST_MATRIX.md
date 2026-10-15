# P01-01 test matrix

Date: 2026-10-15. Starting fork commit: `d46d4de392bb40807eecb0815c1da54080dc92aa`. Upstream reference: `446f6df82c390ab760802c2fce3b677bce3f31c1`.

## Deterministic fixtures

All sources are authored for this diagnostic and dedicated CC0-1.0. ZIP entry order/timestamps and EPUB modified timestamps are fixed by `tools/epub-fixtures/generate.ps1`.

| Fixture | SHA-256 | Expected evidence |
| --- | --- | --- |
| EPUB 2 | `53c3f02c01aa95088150100e4e6c9bb45f6611a1e9db6457e04ddfbee760d67f` | NCX paths to three sections; illustration; `EPUB2-PASSAGE-RESTORE` |
| EPUB 3 | `198621d36078a97c951f64fa1b16cbf3fbd326acfcdd17c921d55c09b4a9e115` | nested NAV; encoded fragment; illustration; Arabic/RTL mixed text; `EPUB3-PASSAGE-ARABIC` |
| Image EPUB | `96d74b47a1bc29534c0bd18e44ff796287dbe0a28fdd0d2b6bfedf071a3a98a7` | two image XHTML spine items for existing Local-reader regression |
| External | `441f015a522a84c7f7f30e3c39f2f54b2f624e49b5db5fc11cdb47d39d32c25f` | loopback image/style rejected before socket |
| Corrupt | `d79d570c264bf028a8a240c9feb74da85637f71b443e3b5789a501ba4cc3e4ef` | controlled corrupt/unsupported error |

The EPUB 3 navigation fixture intentionally has an encoded fragment but no synthetic query on the spine link. Readium 3.4.0 preserves query-bearing locators, which are covered by unit tests, but its established fragment navigator compares the complete query-bearing URL to queryless spine resources and otherwise reports a successful `go()` without changing resources.

## Host and automated results

All successful Gradle runs used Android Studio JBR 21, `ANDROID_HOME=C:\SDK\android`, and `TEMP`/`TMP=C:\jtmp`. The previously reported pre-configuration `PipeImpl` / `UnixDomainSockets.connect` failure was caused by the sandbox-provided 96-character temporary path exceeding Java's local Unix-domain-socket path limit. A short temporary path resolves it without a repository or toolchain change.

| Check | Actual result |
| --- | --- |
| Clean starting SHA / branch | Verified start SHA; working branch `codex/p01-01-epub-foundation` |
| Fixture regeneration | Passed; repeated output is deterministic and every valid ZIP starts with an uncompressed `mimetype` entry |
| `spotlessCheck`, `testDebugUnitTest`, `verifySqlDelightMigration`, debug/release assemblies | Passed together in the final run in 1m 42s; 36 app tests, including locator JSON, encoded href/query/fragment, validation limits, state gating, ordered/coalesced writes, cancellation, and fingerprint mapping |
| `assembleDebug -Penable-updater=false -Pinclude-telemetry=false` | Passed; required standalone run 10m 56s; final debug APK also rebuilt by the connected suite |
| `assembleRelease -Pdist=ci -Penable-updater=false -Pinclude-telemetry=false` | Passed; final minified production-code run 12m 36s |
| Release runtime dependency report / Readium insight | Passed; exactly `readium-shared`, `readium-streamer`, and `readium-navigator` 3.4.0 |
| `connectedDebugAndroidTest -Penable-updater=false -Pinclude-telemetry=false` | Passed on Pixel 10 API 37 in 2m 01s; eight tests, zero failures/errors/skips; device execution 18.503s |
| Focused EPUB instrumentation on API 26 | Passed on `P01_API_26`; final functional run took 3m 52s; six tests, zero failures/errors/skips; device execution 37.304s |
| R8 / mapping | Passed; no Readium keep rules required. Existing Compose mapping warning for `BaseUpdatesGridGlanceWidget` remained; mapping and seeds were generated |

Readium Navigator embeds PhotoView 2.3.0. Its `PhotoView.class` SHA-256 exactly matched Mihon's direct 2.3.0 artifact (`83974f8358a63d204bf7cdc01d769c5d8af5fa529f6f2d874f901c90e688a5ca`), so the direct dependency was removed to avoid duplicate classes without changing the implementation used by the image reader.

## Artifact evidence

| Artifact | Identity | Size | Other evidence |
| --- | --- | ---: | --- |
| Clean-baseline x86_64 minified release APK | `app.mihon` | 41,534,260 bytes | detached `d46d4de392bb40807eecb0815c1da54080dc92aa` worktree; same JBR, SDK, architecture, CI properties, minification, and debug signing |
| x86_64 debug APK | `io.github.mohamedmaged9258.mihon.epub.dev` | 73,301,611 bytes | ordinary Android debug certificate; six `assets/epub-fixtures` entries; fixture launcher resource is `true` |
| x86_64 minified release APK | `io.github.mohamedmaged9258.mihon.epub` | 42,259,506 bytes | ordinary Android debug certificate for local CI-style build; zero fixture entries; fixture launcher resource is `false` |

The fork release is 725,246 bytes (1.75%) larger than the comparable clean baseline. The clean-baseline `app.mihon`, fork release, and fork debug APKs installed side by side. Android's provider resolver listed distinct `.provider`, `.shizuku`, `.androidx-startup`, and `.plumber-installer` authorities for all three packages. The release launcher resolved to `MainActivity` and launched successfully. The release `EpubReaderActivity` and `EpubFixtureLauncherActivity` are non-exported; the latter is disabled through its release boolean resource.

## API 37 device evidence

Device: Pixel 10 x86_64 AVD, Android 17 / API 37, Android System WebView 153.0.8010.36.

- The supported debug path Advanced -> Debug info -> Native EPUB fixtures opened the selector. EPUB 2/3, image-oriented, external-resource, and corrupt fixtures were present.
- EPUB 2/3 metadata and ToC structure passed instrumented opening checks. The EPUB 3 page visibly rendered text and its authored illustration, and its ToC visibly retained the nested entry.
- The Arabic resource visibly rendered shaped RTL Arabic and mixed Arabic-English text. Navigation landed on `EPUB3-PASSAGE-ARABIC`.
- Font increase visibly reflowed/enlarged the Arabic page while keeping the same resource and returning to `Ready`.
- `ActivityScenario.recreate()` and device rotation returned to `Ready` without a FragmentFactory failure.
- Real process recovery passed: from the enlarged Arabic marker, Home -> `adb shell am kill io.github.mohamedmaged9258.mihon.epub.dev` -> restore from Recents returned to the same marker with the font preference retained. `force-stop` was not used.
- The loopback socket-trap instrumented test passed with no accepted request from the external-resource fixture.
- Twenty explicit Reopen cycles completed with the same process still `Ready` and no fatal crash. Before/after observations were 246,153/248,589 KiB total PSS, 337,368/341,204 KiB total RSS, and 285/288 file descriptors. These two snapshots do not establish a production budget, but show no large monotonic leak in this small feasibility cycle.
- The clean-baseline `app.mihon` and both fork variants were installed concurrently, with distinct provider authorities.
- A temporary host proxy observed both updater-disabled fork variants for 15 seconds after cold launch. It received no proxy connection, including no GitHub/Mihon release request; the emulator proxy was restored to `:0` immediately afterward. Static and UI checks also confirmed the manual update and upstream release-notes paths are absent.
- A final dual-AVD retry was interrupted after three cases when API 37's low-memory killer terminated the WebView renderer and test process. After shutting down the completed API 26 AVD and rebooting API 37, the clean single-AVD rerun executed all six cases successfully. This is retained as an environment/resource failure, not counted as a passing run.

## API 26 device evidence

Device: `P01_API_26` x86_64 AVD, Android 8.0.0 / API 26, Chrome WebView 69.0.3497.100.

- All six focused EPUB instrumentation cases passed, including EPUB 2/3 metadata and ToC, locator recovery, reflow plus repeated session rebuild, `ActivityScenario.recreate()`, controlled corrupt-file handling, and the loopback socket trap.
- Through the supported Advanced -> Debug info -> Native EPUB fixtures route, EPUB 3 reached `Ready`; the illustration rendered, the nested ToC opened, and the Arabic page visibly rendered shaped RTL text mixed with English and numerals.
- The first Local-reader attempt exposed an invalid IDAT CRC in the authored test PNG. The generator was corrected to embed a deterministic valid 32x32 CC0 illustration and the fixtures/hashes were regenerated.
- With the corrected fixture, the unchanged `ReaderActivity` visibly opened two-page Local-source CBZ, image-folder, and image-oriented EPUB cases. Each rendered the authored colored illustration with no decode error.

## Performance observations

- Five independent API 37 cold-process EPUB 3 opens reached `Ready` in 3,559, 1,610, 1,486, 1,455, and 1,717 ms. The first measurement includes additional post-install/process initialization cost.
- Five warm explicit publication/session rebuilds reached `Ready` in 482, 337, 325, 334, and 316 ms.
- These feasibility samples and the 1.75% x86_64 release-size delta are recorded as provisional observations, not production budgets. P01-08 will establish broader device and corpus budgets.

P01-01 acceptance is complete: baseline and modified builds, minified release, dependency resolution, EPUB rendering, locator round trip, rotation/recreation, real process restoration, external-request isolation, resource cycling, three-package coexistence, updater isolation, and legacy Local image-reader regression all pass. P01-02 is cleared to start.

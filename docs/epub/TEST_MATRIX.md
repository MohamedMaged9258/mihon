# P01-01 test matrix

## Second remediation revalidation, 2026-10-16 (current)

This section covers reviewed head `e4b4f3db8c4bf61cbc8e67560c65dc490e334b59` on `novels-p01-01-foundation` ([PR #1](https://github.com/MohamedMaged9258/mihon/pull/1), base `novels`). The review raised four remaining issues. Everything here was **newly verified on the final code of this change set**. The first 2026-10-16 section below is now historical: its ownership, replacement, store, writer and handoff results still hold, and those behaviours are unchanged and rerun here.

### Issue → resolution → evidence

| Review issue | Resolution | Verified by |
| --- | --- | --- |
| 1. Async anchor results within one binding | Every capture, automatic or manual, goes through `EpubPositionCapture` and holds a gate token. `EpubCheckpointGate.revision` is bumped by every capture request, rebind, operation begin/complete, failure and close. A result is accepted only if: the coroutine is still active; `acceptCapture(owner, token)` holds; and the navigator still shows the captured location. `checkpointNow()` waits for the newest capture. Adapter wrappers rethrow `CancellationException`. | `EpubPositionCaptureTest` (7 tests, with lookups that ignore cancellation, like `suspendCoroutine`). It covers: an older lookup finishing after a newer one; a section change while the previous section's lookup is pending; a lookup started before a reflow that completes after it; overlapping manual and automatic captures in both orders; rebind/close while pending; a genuine lookup failure; and no capture outside Ready. Also a gate token test in `EpubCheckpointGateTest`. Mutation check: removing the three guards fails 5 of 7. |
| 2. Back persistence | System Back and header Back both call `exit()` in `viewModelScope`, bounded at 5 s. The states are Saving → Done (the Activity finishes) or Failed (dialog with Retry / Stay / Exit without saving). Duplicate Backs are ignored while saving. Pause/stop flushes are best effort and only logged. | Instrumented tests with a 1.5 s capture delay and a 1.5 s write delay, and no `checkpointNow()` before Back: `systemBackPersistsLatestPassage` (UiAutomator `pressBack`), `headerBackPersistsLatestPassage` (clicks "Back"), and `repeatedBackSavesAndExitsOnce` (3× Back). Each test then opens a fresh Activity/ViewModel from the diagnostic file and finds paragraph 50 visible. `backPersistenceFailureKeepsReaderAndOffersRetry` covers a write that throws: the reader stays, the dialog appears, Stay restores navigation, and Retry saves, finishes and restores the passage. |
| 3. Navigation during restoration/reflow | Policy: user navigation is **deferred (disabled)** unless the phase is Ready and no exit is in progress. This applies to: ViewModel `previous/next/goToToc` (return `false`); Compose navigation, ToC-entry and font buttons (disabled); a clickable overlay over the navigator (taps and swipes); and `dispatchKeyEvent` (all keys except Back and volume). Back and Reopen stay usable. | `navigationDuringInitialRestorationIsIgnored` and `navigationDuringReflowIsIgnored` hold restoration or reflow open for 20 s (test hook). During that window they call Next/Previous/ToC, swipe, tap the right edge, and press DPAD-right, Page Down and Space. They then check: still in the busy phase; Ready afterwards with no error; the same passage and checkpoint; font 1.2 for the reflow case. The initial-restoration test then uses the same tap and key once Ready and sees the page turn, which proves the ignored input did reach the reader. Mutation check on API 37: without the overlay and key filter, both tests fail. |
| 4. Obscured viewport | The root is a vertical `LinearLayout` (header / reading area / footer) with system-bar and display-cutout insets applied. The WebViews therefore lie entirely within the readable area, so `window.innerHeight` in the visibility check is that area. Titles are 1 line and status text 2 lines. | `readerViewportIsNotObscuredByControls`: every on-screen navigator WebView lies between the header and footer and inside the navigator area. It checks portrait, landscape (real rotation) and system font scale 1.3, and reruns passage restoration after rotation and after recreation with the larger font. Screenshots are in [`evidence/p01-01/`](evidence/p01-01/). |

### Visual evidence

Files are `docs/epub/evidence/p01-01/viewport-{portrait,landscape,font-scale-1.3}-api{26,37}.png`. They were captured by the instrumented test on the final code.

| Device | Portrait | Landscape | Font scale 1.3 |
| --- | --- | --- | --- |
| API 37 | header and footer above and below the text | cutout and gesture insets reserved | bars grow; text stays between them |
| API 26 | same | side navigation bar reserved | same |

Selection was **checked manually** on API 37: a long-press from host `adb` near the top and near the bottom of the reading area each selected a word and showed the Copy/Share toolbar (`selection-{top,bottom}-portrait-api37.png`). It cannot be automated from inside instrumentation: on this setup, long-presses injected by UiAutomator or `input` from the instrumentation never started a WebView selection.

### Commands and results (final code)

Environment: Android Studio JBR 21, `ANDROID_HOME=C:\SDK\android`, `TEMP`/`TMP=C:\jtmp`, and `-Penable-updater=false -Pinclude-telemetry=false` on every Gradle command.

| Check | Result |
| --- | --- |
| `spotlessCheck testDebugUnitTest verifySqlDelightMigration` | Passed (38 s); 154 unit tests, 0 failures (58 in `:app`, 29 of them EPUB) |
| `assembleDebug` | Passed (30 s, incremental) |
| `assembleRelease -Pdist=ci` | Passed (3 m 10 s; `minifyReleaseWithR8` executed); x86_64 release 42,275,890 bytes with zero `epub-fixtures` entries |
| `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.epub.EpubReaderInstrumentedTest` on `Pixel_10` AVD, API 37, WebView 153.0.8010.36, run alone | **23 tests, 0 failures/errors/skips** (device time 247.4 s). This was the second full run after a no-snapshot cold boot. The first run, made while the device was still saturated (first activity displayed after 14 s), failed the three Back tests because injected Back/click events timed out (`InteractionController: Timed out waiting 1000ms`). Those four Back tests then passed alone, and the full suite passed. An earlier full run of the same production code, before the last test-only helper changes, also passed 23/23. |
| Same suite on `P01_API_26` AVD, API 26, Chrome WebView 69.0.3497.100, run alone | **23 tests, 0 failures/errors/skips** (device time 449.6 s) on the final code, after a cold boot. |

**Rendered-ready timings, final code:**

| Device | Cold open | Warm reopens (5 samples) |
| --- | --- | --- |
| API 37 | 1,863 ms | 1,453–2,126 ms |
| API 26 | 4,037 ms | 5,782–7,494 ms |

The API 26 numbers are higher than the earlier 2,180–2,531 ms. To check whether this change set caused that, the unchanged baseline `e4b4f3db8` was run on the same degraded API 26 AVD in the same session. It was equally slow: warm reopens took 9.3–11.7 s and the run took 540 s for 16 tests. Its logcat shows the low-memory killer and WebView renderers killed by OOM on this 1.5 GB-RAM AVD. An isolated rerun of the timing test on the final code measured 2,381–2,871 ms. The numbers stay provisional; P01-08 sets budgets.

### Test-environment findings

- **Swipes:** a horizontal swipe does not turn pages in this paginated Readium configuration, from host `adb` or from instrumentation. Right-edge taps and DPAD-right do. The swipe stays in the blocked-input set, but the page-turn proof uses tap and key.
- **Edge swipes:** an edge swipe on gesture navigation is the system Back gesture. Test swipes stay inside the reader.
- **Idle waits:** `ActivityScenario.onActivity` and UiAutomator actions wait for an idle main thread, which never comes on a slow device while the busy spinner animates. The busy-phase checks use `runOnMainSync` with zero UiAutomator idle and acknowledgment timeouts.
- **Low memory:** both AVDs were run alone. Two runs with other emulators running were aborted by host or guest low memory and are not counted. A Pixel_10 quick-boot snapshot that crashed after the host stopped was replaced by a cold boot.

### Remaining limitations

- Restoration precision is still the element (paragraph) granularity of Readium's first visible element.
- Accessibility gestures inside the WebView during an operation are covered by the ViewModel guard and the overlay, not by a dedicated test.
- Text selection is evidenced manually on API 37 only.
- The exit timeout is 5 s. A capture that does not finish within it ends as `Failed("Saving timed out")` and keeps the previous saved position.
- Not rerun in this change set (historical results below still apply, since these areas are untouched): dependency report, three-package coexistence, updater proxy, Local image-reader regression, resource-cycling snapshots, and real `am kill` process restoration.

### Acceptance

With the four issues above resolved and verified on API 26 and API 37, P01-01's exit evidence (DEVELOPMENT_PLAN §8) is met. That evidence is: Readium opening EPUB 2/3 inside Mihon, passage-level restoration across font change, rotation, recreation, reopen and close-and-open, a minified release without fixtures, and the lifecycle contracts in `CONTRACTS.md`. On top of that, capture ordering, Back persistence, the navigation policy and the reading viewport are now enforced and tested. The package is ready for review, and P01-02 may start once PR #1 is accepted.

---

## First remediation revalidation, 2026-10-16 (historical)

Reviewed head `67e4054192d9eb75324163430003eabe0bdd41e3` on `novels-p01-01-foundation` ([PR #1](https://github.com/MohamedMaged9258/mihon/pull/1), base `novels`). The review found that callback ownership, navigator replacement, restoration acknowledgement, close-and-open checkpoints, checkpoint write ordering, and publication handoff were not enforced by code. This section records evidence for the remediated implementation. The 2026-10-15 section below is historical and remains valid only where it does not depend on those behaviors.

### Fixture change

`epub3` gained `text/long.xhtml`: 80 paragraphs `long-p001`…`long-p080`, each a single element with a unique `EPUB3-LONG-Pnnn` marker, spanning many pages at every font size, plus a top-level ToC entry. `external` is derived from `epub3`, so its hash changed too. Generation was run twice with identical output.

| Fixture | Current SHA-256 | Historical SHA-256 (2026-10-15) |
| --- | --- | --- |
| EPUB 3 | `4fbf32bd5c877a565a5bff7db537c98bb04b005b05d9ef426fb198b0d0cdd97f` | `198621d36078a97c951f64fa1b16cbf3fbd326acfcdd17c921d55c09b4a9e115` |
| External | `848965949a79cc3987e607c579a133f9ac64f20390c8b32c07a52085ee7390f9` | `441f015a522a84c7f7f30e3c39f2f54b2f624e49b5db5fc11cdb47d39d32c25f` |
| EPUB 2, Image EPUB, Corrupt | unchanged | unchanged |

### Issue → evidence

| Review issue | Resolution | Verified by |
| --- | --- | --- |
| 1. Callback ownership | `EpubOwner(sessionId, bindingId)`; per-binding Readium listeners; owner- and operation-checked gate; Error/Closing keep it closed; collector Job retained and cancelled; input listener removed; detached bindings stop forwarding | `EpubCheckpointGateTest` (6 tests); instrumented `obsoleteOwnerEventsChangeNeitherCheckpointNorState` delivers old-binding (after recreation) and old-session (after reopen) locator events, including one for the current href, plus render and resource-error events, and asserts that state and checkpoint are unchanged |
| 2. Session-owned navigator replacement | The host reuses only the navigator instantiated by the active session's binding; dummy/obsolete navigators are removed explicitly; a publication is closed only after its navigator is destroyed | instrumented `normalReopen…`, `rapidRepeatedReopen…` (5×), `openCompletingWhileStopped…` (reopen + stop, then a reopen that starts and completes while stopped, then resume), `recreationDuringOpening…`, `processRestorationReplacesDummyNavigator` (ViewModelStore cleared + recreate ⇒ Readium dummy factory). Each asserts that the bound navigator belongs to the new session and that ToC and Next navigation change the visible paragraphs |
| 3. Rendered readiness and restoration | `Attaching → Rendering → Restoring → Ready`. A restore is acknowledged only after a Readium location callback for the binding, a 500 ms quiet window, and a DOM check that the anchored element intersects the viewport. Anchors come from Readium's first visible element; a still-visible anchor is kept to prevent relayout drift; transitional locations (Readium still naming the previous resource) are not persisted; failure ⇒ `Error(RestorationFailed)` | instrumented `passageSurvivesFontChangeRotationRebuildAndRecreation`: the paragraph near 40 of the long section survives font +0.2, real rotation to landscape and back (UiAutomator), three reopens, and recreation; the test asserts the exact anchor id and that it is visible. Real process death below |
| 4. Diagnostic close-and-open | `EpubDiagnosticCheckpointStore`; precedence SavedStateHandle → file → start; codec validation | `EpubDiagnosticCheckpointStoreTest` (4 tests); instrumented `diagnosticCheckpointRestoresPassageInFreshActivity` (the paragraph near 50 is restored after `finish()` in a fresh Activity/ViewModel) and `corruptDiagnosticCheckpointStartsWithRecoveryReason` |
| 5. Ordered checkpoint writes | A single actor performs writes inline; pending values conflated; `flush()` returns a result; failures are caught and retried; temporary file + atomic replace | `EpubCheckpointWriterTest` (5 tests): a slow, cancellation-resistant write cannot finish after a newer one; at most one write in flight; flush/save interleaving; failure then retry |
| 6. Publication handoff | `handOff()` keeps cleanup ownership until `open()` returns; directory/temp-file creation is inside error handling | `EpubPublicationHandOffTest` (3 tests) with deterministic return-dispatch cancellation; instrumented `openerCreationFailureAndRapidReplacementLeaveNoFiles` |
| 7. Verification quality | Fixed sleeps replaced by readiness polling; passage assertions after recreation and after each reopen; the socket is monitored on a background thread from before launch through rendering plus 1 s; timings measured to rendered Ready | instrumented suite below |

### Commands and results (2026-10-16)

Environment as before: Android Studio JBR 21, `ANDROID_HOME=C:\SDK\android`, `TEMP`/`TMP=C:\jtmp`.

| Check | Result |
| --- | --- |
| `spotlessCheck testDebugUnitTest verifySqlDelightMigration -Penable-updater=false -Pinclude-telemetry=false` | Passed in 48 s; 146 unit tests, 0 failures (50 in `:app`, 21 of them EPUB) |
| `assembleDebug -Penable-updater=false -Pinclude-telemetry=false` | Passed (39 s, incremental) |
| `assembleRelease -Pdist=ci -Penable-updater=false -Pinclude-telemetry=false` | Passed (3 m 14 s, incremental; `minifyReleaseWithR8` executed); x86_64 release 42,275,890 bytes with zero `epub-fixtures` entries |
| `connectedDebugAndroidTest -Penable-updater=false -Pinclude-telemetry=false -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.reader.epub.EpubReaderInstrumentedTest` on Pixel 10 AVD, API 37 | 16 tests, 0 failures/errors/skips in each of the two runs on the final code (device time 137.1 s and 146.3 s). Three earlier passing runs preceded the restore-settling change |
| Same suite on `P01_API_26` AVD, API 26, WebView 69.0.3497.100 | 16 tests, 0 failures/errors/skips on the final code (device time 85.3 s). The run before the settling change failed: a late relayout about 285 ms after verification moved the landscape page, which is why the quiet window exists |
| Real process restoration, API 37 | Debug route → EPUB 3 → ToC Long section → 12× Next → A+ → Home → `adb shell am kill io.github.mohamedmaged9258.mihon.epub.dev` (process gone) → Recents. A new process restored the same `EPUB3-LONG-P059`…`P063` page with the enlarged font; log `EPUB restore check #long-p060 -> yes`; state `Ready`. `force-stop` was not used |
| Dual-AVD run | One API 37 run with the API 26 AVD also running aborted after 4 tests (`Activity never becomes requested state DESTROYED`), matching the earlier low-memory finding. Not counted; API 37 was rerun alone |

### Rendered-ready timings (final code)

| Device | Cold open to Ready (no saved checkpoint) | Warm reopen to Ready (restores checkpoint) |
| --- | --- | --- |
| API 37 | 5,851 / 2,806 ms (two runs; first after install) | 1,549–1,848 ms (10 samples) |
| API 26 | 868 ms | 2,180–2,531 ms (5 samples) |

These replace the 2026-10-15 numbers as rendered-readiness measurements. They are slower by design: Ready now waits for a rendered page and, when restoring, for a verified and settled anchor (≥500 ms quiet window). Before the settling change, API 37 warm reopens measured 283–546 ms. They remain provisional; P01-08 sets budgets.

### Remaining limitations

- Restoration precision is the element (paragraph) granularity of Readium's first visible element; a single element longer than a page restores to its first page.
- The quiet-window settling (500 ms, up to three navigation attempts) is a heuristic for late WebView relayouts.
- Process-death restoration is evidenced manually on API 37 only; the instrumented dummy-factory test simulates it in-process.
- Private fixture copies left by a killed process are not swept on startup (cache directory).
- Not re-run on 2026-10-16: dependency report/insight (dependencies unchanged), three-package coexistence, updater proxy observation, Local image-reader regression, resource-cycling PSS/RSS snapshots. This change set does not touch those areas; their historical results follow.

### Acceptance

P01-01's exit evidence (DEVELOPMENT_PLAN §8) is met with the remediated implementation: baseline and modified builds, minified release, Readium opening EPUB 2/3 inside Mihon, locator serialization and passage-level restoration, rotation/recreation, and documented process restoration, with lifecycle decisions recorded in `CONTRACTS.md` and `DECISIONS.md`. The package is ready for review; P01-02 may start once PR #1 is accepted.

---

## 2026-10-15 feasibility evidence (historical)

Date: 2026-10-15. Starting fork commit: `d46d4de392bb40807eecb0815c1da54080dc92aa`. Upstream reference: `446f6df82c390ab760802c2fce3b677bce3f31c1`.

### Deterministic fixtures

All sources are authored for this diagnostic and dedicated CC0-1.0. ZIP entry order/timestamps and EPUB modified timestamps are fixed by `tools/epub-fixtures/generate.ps1`.

| Fixture | SHA-256 | Expected evidence |
| --- | --- | --- |
| EPUB 2 | `53c3f02c01aa95088150100e4e6c9bb45f6611a1e9db6457e04ddfbee760d67f` | NCX paths to three sections; illustration; `EPUB2-PASSAGE-RESTORE` |
| EPUB 3 | `198621d36078a97c951f64fa1b16cbf3fbd326acfcdd17c921d55c09b4a9e115` | nested NAV; encoded fragment; illustration; Arabic/RTL mixed text; `EPUB3-PASSAGE-ARABIC` |
| Image EPUB | `96d74b47a1bc29534c0bd18e44ff796287dbe0a28fdd0d2b6bfedf071a3a98a7` | two image XHTML spine items for existing Local-reader regression |
| External | `441f015a522a84c7f7f30e3c39f2f54b2f624e49b5db5fc11cdb47d39d32c25f` | loopback image/style rejected before socket |
| Corrupt | `d79d570c264bf028a8a240c9feb74da85637f71b443e3b5789a501ba4cc3e4ef` | controlled corrupt/unsupported error |

The EPUB 3 navigation fixture intentionally has an encoded fragment but no synthetic query on the spine link. Readium 3.4.0 preserves query-bearing locators, which are covered by unit tests, but its established fragment navigator compares the complete query-bearing URL to queryless spine resources and otherwise reports a successful `go()` without changing resources.

### Host and automated results

All successful Gradle runs used Android Studio JBR 21, `ANDROID_HOME=C:\SDK\android`, and `TEMP`/`TMP=C:\jtmp`. The previously reported pre-configuration `PipeImpl` / `UnixDomainSockets.connect` failure was caused by the sandbox-provided 96-character temporary path exceeding Java's local Unix-domain-socket path limit. A short temporary path resolves it without a repository or toolchain change.

| Check | Actual result |
| --- | --- |
| Clean starting SHA / branch | Verified start SHA; working branch at the time `codex/p01-01-epub-foundation` (now `novels-p01-01-foundation`) |
| Fixture regeneration | Passed; repeated output is deterministic and every valid ZIP starts with an uncompressed `mimetype` entry |
| `spotlessCheck`, `testDebugUnitTest`, `verifySqlDelightMigration`, debug/release assemblies | Passed together in the final run in 1m 42s; 36 app tests, including locator JSON, encoded href/query/fragment, validation limits, state gating, ordered/coalesced writes, cancellation, and fingerprint mapping |
| `assembleDebug -Penable-updater=false -Pinclude-telemetry=false` | Passed; required standalone run 10m 56s; final debug APK also rebuilt by the connected suite |
| `assembleRelease -Pdist=ci -Penable-updater=false -Pinclude-telemetry=false` | Passed; final minified production-code run 12m 36s |
| Release runtime dependency report / Readium insight | Passed; exactly `readium-shared`, `readium-streamer`, and `readium-navigator` 3.4.0 |
| `connectedDebugAndroidTest -Penable-updater=false -Pinclude-telemetry=false` | Passed on Pixel 10 API 37 in 2m 01s; eight tests, zero failures/errors/skips; device execution 18.503s |
| Focused EPUB instrumentation on API 26 | Passed on `P01_API_26`; final functional run took 3m 52s; six tests, zero failures/errors/skips; device execution 37.304s |
| R8 / mapping | Passed; no Readium keep rules required. Existing Compose mapping warning for `BaseUpdatesGridGlanceWidget` remained; mapping and seeds were generated |

Readium Navigator embeds PhotoView 2.3.0. Its `PhotoView.class` SHA-256 exactly matched Mihon's direct 2.3.0 artifact (`83974f8358a63d204bf7cdc01d769c5d8af5fa529f6f2d874f901c90e688a5ca`), so the direct dependency was removed to avoid duplicate classes without changing the implementation used by the image reader.

### Artifact evidence

| Artifact | Identity | Size | Other evidence |
| --- | --- | ---: | --- |
| Clean-baseline x86_64 minified release APK | `app.mihon` | 41,534,260 bytes | detached `d46d4de392bb40807eecb0815c1da54080dc92aa` worktree; same JBR, SDK, architecture, CI properties, minification, and debug signing |
| x86_64 debug APK | `io.github.mohamedmaged9258.mihon.epub.dev` | 73,301,611 bytes | ordinary Android debug certificate; six `assets/epub-fixtures` entries; fixture launcher resource is `true` |
| x86_64 minified release APK | `io.github.mohamedmaged9258.mihon.epub` | 42,259,506 bytes | ordinary Android debug certificate for local CI-style build; zero fixture entries; fixture launcher resource is `false` |

The fork release is 725,246 bytes (1.75%) larger than the comparable clean baseline. The clean-baseline `app.mihon`, fork release, and fork debug APKs installed side by side. Android's provider resolver listed distinct `.provider`, `.shizuku`, `.androidx-startup`, and `.plumber-installer` authorities for all three packages. The release launcher resolved to `MainActivity` and launched successfully. The release `EpubReaderActivity` and `EpubFixtureLauncherActivity` are non-exported; the latter is disabled through its release boolean resource.

### API 37 device evidence

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

### API 26 device evidence

Device: `P01_API_26` x86_64 AVD, Android 8.0.0 / API 26, Chrome WebView 69.0.3497.100.

- All six focused EPUB instrumentation cases passed, including EPUB 2/3 metadata and ToC, locator recovery, reflow plus repeated session rebuild, `ActivityScenario.recreate()`, controlled corrupt-file handling, and the loopback socket trap.
- Through the supported Advanced -> Debug info -> Native EPUB fixtures route, EPUB 3 reached `Ready`; the illustration rendered, the nested ToC opened, and the Arabic page visibly rendered shaped RTL text mixed with English and numerals.
- The first Local-reader attempt exposed an invalid IDAT CRC in the authored test PNG. The generator was corrected to embed a deterministic valid 32x32 CC0 illustration and the fixtures/hashes were regenerated.
- With the corrected fixture, the unchanged `ReaderActivity` visibly opened two-page Local-source CBZ, image-folder, and image-oriented EPUB cases. Each rendered the authored colored illustration with no decode error.

### Performance observations

- Five independent API 37 cold-process EPUB 3 opens reached `Ready` in 3,559, 1,610, 1,486, 1,455, and 1,717 ms. The first measurement includes additional post-install/process initialization cost.
- Five warm explicit publication/session rebuilds reached `Ready` in 482, 337, 325, 334, and 316 ms.
- These feasibility samples and the 1.75% x86_64 release-size delta are recorded as provisional observations, not production budgets. P01-08 will establish broader device and corpus budgets.

Historical (2026-10-15) conclusion, superseded by the revalidation above: baseline and modified builds, minified release, dependency resolution, EPUB rendering, locator round trip, rotation/recreation, real process restoration, external-request isolation, resource cycling, three-package coexistence, updater isolation, and legacy Local image-reader regression all passed.

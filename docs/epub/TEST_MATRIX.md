# P01-02 test matrix

## Baseline and environment

| Item | Value |
| --- | --- |
| Starting point | `origin/novels` at `e81de76038e81007631d0dabd6bacd43038983ff` (merge of PR #1), which contains the reviewed P01-01 tip `c7f5ebf06`. No later commits on `novels` |
| Branch | `feature/p01-02-epub-data-import` |
| Host | Windows 11 Pro; JDK: Android Studio JBR 21.0.10; `ANDROID_HOME=C:\SDK\android`; Gradle 9.8.1 |
| Baseline result (before any change) | `spotlessCheck testDebugUnitTest verifySqlDelightMigration`: passed, 158 unit tests, 0 failures |
| Devices | `Pixel_10` AVD (API 37, Android 17, x86_64) and `P01_API_26` AVD (API 26, Android 8.0, x86_64), cold boot |
| Readium | 3.4.0 (`shared`, `streamer`, `navigator`); no dependency change in P01-02 |

## Scenario evidence

| Scenario | Expected | Evidence | Result |
| --- | --- | --- | --- |
| Fresh database and upgrade from P01-01 | Schema works; existing title/item/history/category data and flags unchanged | `EpubDatabaseInstrumentedTest.upgradeFromP0101SchemaKeepsDataAndMatchesFreshSchema`, using the app's real driver (`DatabaseBindings.createSqlDriver`: bundled SQLite, WAL, foreign keys). It builds a version-20 database, adds rows with flags, notes, timestamps, history and a category, then reopens. Checks: `user_version` 21, rows identical, `foreign_keys = 1`, `integrity_check ok`, no FK violations, migrated EPUB schema identical to a fresh one. **Real APK upgrade** on API 37: the `e81de76` debug APK created its database (`user_version` 20), which was seeded and then upgraded in place with `adb install -r` to this build. Result: `user_version` 21, seeded rows identical, 5 EPUB tables, integrity ok, 0 FK violations ([`evidence/p01-02/apk-upgrade-api37.txt`](evidence/p01-02/apk-upgrade-api37.txt)) | Pass |
| Foreign keys and deletion | Auxiliary records cascade on deliberate removal; files untouched | `repositoriesRoundTripAndEnforceConstraints`: deleting an item removes its state and bookmarks, and deleting a title removes its assets and preferences. `importJournalClaimsCommitsAndKeepsHistoryAfterRemoval`: journal references become NULL. `interruptionAfterTheDatabaseCommitIsFinalizedAndRemovalIsRespected`: the book file remains after the title is deleted | Pass |
| Constraints | Invalid codes/versions, wrong fingerprints, orphans and managed duplicates rejected | Same test: availability 9, a short fingerprint, locator version 2, theme 7 and an unknown item are each rejected by the schema. A second managed asset with the same fingerprint and a second asset per item are rejected; user-owned duplicates are accepted | Pass |
| Reading state / preferences / bookmarks | Versioned round trips, override clearing, fingerprint separation | Separate Resume/furthest; an older revision is refused; two fingerprints are kept separately; reset clears only its fingerprint and records `reset_at`; setting a preference to null clears it; `clear()`; bookmark add/label/delete; `unregister` keeps state and bookmarks | Pass |
| EPUB 2/3 and Arabic metadata | Correct shared title/item metadata and declared cover | `batchImportsMetadataCoversAndPerFileOutcomes` on both roots: EPUB 2 (`meta name="cover"`, publisher, ISBN), EPUB 3 (`cover-image`, Arabic and mixed title, two authors, escaped-HTML description → plain text, `ar`), fixed-layout image book → `FIXED`/`IMAGE`, no cover. The title and item rows are checked (source 0, URL = key, not in the library, not read, no scanlator). On the device, the system picker import shows the Arabic title and author ([screenshot](evidence/p01-02/picker-import-results-api37.png)) | Pass |
| Managed single and batch import | Complete durable files, correct records, per-file results | Same test: 5 inputs in order give 3 `Imported` and 2 `Unsupported` (truncated, plain text named `.epub`). The stored bytes match by SHA-256, there is no part file, staging is empty and the journal has nothing active | Pass |
| Exact and concurrent duplicate | One managed copy; deterministic duplicate result | `exactAndConcurrentDuplicatesKeepOneManagedCopy`: the same bytes three times in one concurrent batch give 1 `Imported` and 2 `Duplicate` with the same ids. A retry gives `Duplicate` with the same asset, and there is one key folder. On the device, `arabic-novel-copy.epub` (identical bytes) gives `DUPLICATE` | Pass |
| Same title, different bytes | Distinct identities; no replacement | Same test: a second edition becomes a new title and key, and the first asset keeps its fingerprint | Pass |
| Restart and Local refresh | Same URLs, title IDs, item IDs and metadata | `EpubLocalDiscoveryInstrumentedTest.managedImportIsDiscoverableSearchableAndStableAcrossRefreshAndRestart` on both roots. It refreshes through the real `LocalSource` and `SyncChaptersWithSource`, then through a newly constructed `LocalSource` ("restart"). Checks: same item id and URL, item named `Book`, no scanlator, details keep the declared title and author. Search by Arabic and Latin parts of the title works; the internal key is not searchable. On the device, the Local source list and the title page show metadata titles, the author, the description and the item `Book` ([browse](evidence/p01-02/local-source-browse-api37.png), [details](evidence/p01-02/local-title-details-api37.png)) | Pass |
| Legacy Local folders and image EPUBs | Existing discovery and image reading path unchanged | Same class: an image directory chapter and an image-page EPUB are listed with their legacy URLs, and `getFormat` still returns `Format.Epub` for the image path. `unregisteredLegacyItemsKeepTheirRemovalSemantics`: a deleted unregistered chapter is still removed. `existingLocalEpubIsRegisteredWithoutTouchingTheFile`: the legacy EPUB helper still writes the publisher as scanlator | Pass |
| Existing Local EPUB registration | No copy, rename or modification | `existingLocalEpubIsRegisteredWithoutTouchingTheFile`: two identical user files are both registered (`USER`, relative path under `local/My Series/`). Repeat registration is idempotent. Hash and mtime are unchanged and no other folder appears. A managed import of the same bytes still copies and reports `userOwnedCopies = 2`; closing a session keeps the user file | Pass |
| Original picker file moved/deleted | Managed import remains accessible | `managedCopySurvivesSourceDeletionAndCloseNeverDeletesIt`. On the device, the three originals were deleted from `Download`, after which **Resolve & open** opened both managed copies ([screenshot](evidence/p01-02/open-after-source-deleted-api37.png)) | Pass |
| Missing asset or revoked storage | Metadata, identity, flags and reading state intact | `missingFileAndUnavailableStorageKeepIdentityStateAndFlags` on both roots. Deleting the book makes the item kept and `MISSING`, with read, bookmark, last page 7, state and bookmark intact; restoring it gives `AVAILABLE`. An unreachable tree (provider revoked, or root switched to a missing directory) raises `LocalStorageUnavailableException` from refresh, deletes nothing, and the resolver reports `INACCESSIBLE`/`ROOT_CHANGED`. Restoring the root makes it resolvable again | Pass |
| Provider write failure / full storage | Controlled result; nothing partially visible | `providerWriteFailureAndFullStorageLeaveNothingVisible` (no-rename provider): a failure after 1000 bytes gives `CommitFailed`. A full volume gives a controlled failure (`CommitFailed`, see the limitations). There are no key folders, browse is empty and the journal entries are `ABORTED`; the same bytes then import cleanly. Missing/revoked source documents give `SourceMissing`/`PermissionDenied`, or `StorageUnavailable` when the tree itself is revoked | Pass |
| Storage change during import | Abort on the pinned root | `storageChangeBeforeFileCommitAbortsOnTheOriginalRoot`: the root switches at `CLAIMED`, giving `StorageChanged` with nothing on either root | Pass |
| Cancellation / interruption | Idempotent recovery; safe partial cleanup | `cancellationReportsUnfinishedInputsAndKeepsCommittedOnes`: cancel after the first file commit gives the first input `Imported` and the next `Canceled`; a retry gives `Duplicate` plus `Imported`. A simulated process death (`SimulatedInterruption`) leaves files, staging and journal untouched: at `STAGED`/`CLAIMED`/`PART_WRITTEN` the folder is hidden and recovery aborts it, a second recovery is a no-op, a sentinel user file survives and a re-import gets a new key. At `FILE_COMMITTED` the folder is hidden; recovery completes it once and a second pass does nothing. Re-importing gives `Duplicate`. At `DATABASE_COMMITTED` the title is visible; the user deletes it, recovery finalizes without recreating it, and re-importing reuses the verified copy under the same key (`reused`) | Pass |
| Malformed / oversized / traversal / external resource | Bounded rejection; no fetch | JVM `EpubArchiveGuardTest` (9 tests): traversal, absolute, backslash, drive and `.` names; missing container; non-EPUB mimetype; truncated input; per-entry, total, ratio, entry-count and input limits on actual bytes; font obfuscation vs encryption, `rights.xml`, `license.lcpl`; bounded hashing of an endless stream; key generation; Unicode normalization; error classification without URI leakage. Instrumented `hostileAndProtectedInputsAreRejectedWithoutFetching`: traversal → `Malformed`, a 1 MB entry over a 200 kB limit → `TooLarge`, encryption → `UnsupportedProtection`, font obfuscation imports. An EPUB with remote image and stylesheet references imports and opens with **0** HTTP attempts. On the device, `traversal.epub` is rejected by the platform ZIP path check and `truncated.epub` as malformed | Pass |
| Publication close and cancellation | No original deletion; no leaked working copy | `openingCanceledAtAnyPointLeaksNothingAndKeepsTheOriginal` (12 cancellations at staggered points on a 2 MB book): no working copies, original unchanged. Double `close()` is a no-op. A provider-backed asset uses a working copy and a file-backed one opens in place. `replacedBytesAreReportedAndNotOpened` gives `FingerprintMismatch` | Pass |
| Existing reader regressions | P01-01 suite unchanged | `EpubReaderInstrumentedTest` (50 tests) in the same runs | Pass |
| Real system picker and SAF root | Real providers end to end | API 37, fresh install. Onboarding storage was set through the system folder picker to `Documents` (ExternalStorageProvider tree, `content://com.android.externalstorage.documents/…`). **EPUB import diagnostics → Import EPUBs**, with 5 files picked from `Download` in DocumentsUI (DownloadsProvider/MediaStore documents), gave: `#0 IMPORTED`, `#1 DUPLICATE`, `#2 IMPORTED`, `#3 UNSUPPORTED Malformed (Invalid zip entry path)`, `#4 UNSUPPORTED Malformed (zip END header not found)`. `Documents/local/` contains two `epub-…` folders, each with only `book.epub`, `ComicInfo.xml`, `cover.jpg` and `.nomedia`. Resolve & open: 2 reading-order items each, working copy used, 0 blocked requests | Pass |

The provider-backed rows run against `NoRenameDocumentsProvider`: a real `DocumentsProvider` without rename, reached through `ContentResolver`/`DocumentsContract`. The file-backed rows run against a plain directory root, which uses atomic `renameTo`.

## Commands and results (original submission)

See [the review remediation](#review-remediation-2026-10-18-pr-2-review-of-014629e43) for the current results.

| Check | Result |
| --- | --- |
| `./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration` | Passed (3 m 21 s). 167 unit tests, 0 failures (158 existing + 9 `EpubArchiveGuardTest`). `verifySqlDelightMigration` ran `verifyDebugDatabaseMigration`/`verifyReleaseDatabaseMigration`. The repository has no stored `.db` schema snapshots, so this task does not compare migrated and fresh schemas; the instrumented migration test does |
| `./gradlew assembleDebug -Penable-updater=false -Pinclude-telemetry=false` | Passed (3 m 13 s) |
| `./gradlew assembleRelease -Pdist=ci -Penable-updater=false -Pinclude-telemetry=false` | Passed (16 m 1 s; `minifyReleaseWithR8` executed). x86_64 release APK 42,292,818 bytes. Its manifest has `EpubImportDiagnosticsActivity` and `EpubFixtureLauncherActivity` non-exported with `enabled` resources `false`, no `NoRenameDocumentsProvider`, and 0 `epub-fixtures` entries |
| `./gradlew connectedDebugAndroidTest -Penable-updater=false -Pinclude-telemetry=false`, `Pixel_10` (API 37) | **87 tests, 0 failures**: `EpubReaderInstrumentedTest` 50 (P01-01 regression suite), `EpubImportInstrumentedTest` 28, `EpubLocalDiscoveryInstrumentedTest` 6, `EpubDatabaseInstrumentedTest` 3 (8 m 21 s) |
| Same, `P01_API_26` (API 26), cold boot | **87 tests, 0 failures**, same split (14 m 39 s). A first full API 26 run on slightly earlier code (before the test-builder line wrapping and the diagnostics inset fix) also gave 87/87 |

### Test-environment findings

- **API 37 emulator died.** One API 37 full run on the final code was invalid because the emulator process exited partway through: 79 results, with 7 reader timeouts. After a cold boot, the next run stopped after one reader test. The device crash log showed `com.android.chrome` (the WebView provider) dying with SIGILL. Host memory was low at the time (3.3 GB free); stopping the extra Gradle daemons freed about 12 GB.
- **One intermittent P01-01 timeout.** The following full run gave 86/87. The one failure was the P01-01 timing test `reopenCapturePendingPastBoundAcrossRecreationFailsThenStay`, which timed out waiting for the reader to become usable after Stay. It passed 3 of 3 times in isolation, and the next full run passed 87/87. P01-02 does not change reader, ViewModel or navigator code; the fixture opener only delegates to the shared `parseEpub`. An earlier full API 37 run on this branch was also 87/87.
- **Test fixes before the counted runs.**
  - The chapter-name expectation was corrected; it led to the `local_epub_book` decision.
  - The escaped-HTML description fixture was corrected.
  - The provider was made to append MIME extensions and suffix name conflicts like the platform providers.
  - The provider-root setup needed the UniFile handler described in `DECISIONS.md`.

## Remaining limitations

- **Full provider volumes.** A full remote provider volume is reported as `CommitFailed`, not `InsufficientSpace`, because the writing side of a provider pipe sees only the pipe closing. ENOSPC from a local volume maps to `InsufficientSpace` (unit-tested). SELinux denies apps `/dev/full`, so a full local volume was not reproduced on a device.
- **Own provider not listed.** Under instrumentation, the platform does not list the app's own document provider in `queryIntentContentProviders`. The provider-backed tests therefore register a test-only UniFile handler for that authority. Real providers were exercised through the system picker instead.
- **Hashing on open.** Opening a file-backed asset re-hashes it in place before parsing, to bind locators to the bytes. Very large books pay one sequential read per open; P01-04 may cache by size/mtime.
- **Recovery triggers.** Recovery runs before each import batch and from the debug screen, not at app start. An interrupted import, or a rollback whose cleanup could not be confirmed, stays hidden until the next batch.
- **Hostile corpus.** The hostile-input corpus is the baseline set above. Broader corpus testing and limit tuning are P01-06.
- **Deferred work.** Not implemented here: the user-facing Import action and reader routing (P01-03); live Resume, history and completion (P01-04); reader settings, search and bookmark UI (P01-05); relinking, storage management and deleting owned files (P01-06); backup fields (P01-07).

## Acceptance

P01-02's exit evidence (DEVELOPMENT_PLAN §8) is met:

- Upgrade and fresh-database checks pass on the app's driver and through a real APK upgrade.
- Imports create durable managed files and real title/item records, through both atomic-rename and no-rename storage and through the real system picker.
- Duplicate, cancellation and interruption handling are verified at every journal phase.
- Existing Local URLs and user-owned files are unchanged.
- Managed metadata survives discovery, refresh and restart.
- Missing files and unreachable storage keep registered items and their state.
- Production inspection and opening are independent of fixture identities.
- The P01-01 reader suite passes on API 26 and 37.

P01-03 can build on `EpubImportService`, `PublicationAssetResolver`, `LocalPublicationOpener` and `LocalEpubRegistrar`.

## Review remediation, 2026-10-18 (PR #2 review of `014629e43`)

### Finding → resolution → evidence

| Finding | Resolution | Evidence |
| --- | --- | --- |
| Recovery acted on a stale `getActive()` snapshot: an import that finished after the snapshot but before the `isLocked` check was rolled back as `CLAIMED`, deleting its committed book | `recover()` takes each operation's fingerprint mutex (the one imports hold from claim to finalize) with `tryLock`, skips it if held, and with the lock held re-reads the operation and acts only on its current phase; `FINALIZED`/`ABORTED` are ignored | `recoveryRacingAFinishingImportKeepsTheBook` (both roots): the import's `CLAIMED` hook starts recovery, which pauses at its new `RECOVERY_SNAPSHOT` checkpoint holding the `CLAIMED` snapshot; the import finishes and releases its mutex; recovery resumes. Asserts report `(0, 0, 0, 0)`, `FINALIZED`, book SHA-256 intact, the same asset id/key, chapter → title id and title URL, and the title still browsable. **On the old behavior** (lock without re-read: `recoverOne(snapshot)`), the class gave 40 tests, 2 failures, exactly this test on both roots: `expected (0,0,0,0) but was (aborted=1, …)` |
| A failed rollback was marked `ABORTED` anyway, dropping it from recovery and from the hidden keys, so a partial `book.epub` could surface | New phase `ABORTING` (6), recorded before any delete. `ABORTED` only once the operation's own files are confirmed absent by a fresh listing whose folder still answers `exists()`; unavailable storage, permission loss, thrown or false deletes, and deletes that report success but keep the file leave it `ABORTING`. `getActive` and `getUncommittedKeys` include 6; the active-fingerprint index does not. `20.sqm` (unreleased, new in this PR) and the `.sq` changed together | `failedRollbackStaysHiddenAndIsRetriedUntilConfirmed` (no-rename provider): the second, final-name copy fails after 1000 bytes, leaving a partial `book.epub`. For deletes that are ignored, fail (I/O), are denied (`SecurityException`), and for storage revoked at recovery time: `Failed(CommitFailed)`, phase `ABORTING`, key in the uncommitted set and absent from browsing; `recover()` reports `failed = 1` and keeps it `ABORTING` and hidden; after restoring access `recover()` gives `(1, 0, 0, 0)`, the folder is gone, and a third pass gives `(0, 0, 0, 0)`. A reused (reopened) operation recovered while storage is revoked stays pending and then aborts without touching its verified book. An unrelated user file is unchanged. The existing `providerWriteFailureAndFullStorageLeaveNothingVisible` still sees all entries `ABORTED` |
| Registrar, opener and `completeForward()` read external bytes without a limit | `copyHashing` and `hash` take a required limit; each read asks for at most one byte past it, nothing past it is written, and `TooLarge` is raised immediately. Limits: import and registration `maxInputBytes`; opener, recovery, reuse check and post-write verification the recorded byte size. Opener maps an over-size read to `FingerprintMismatch`, registration returns `Failed(TooLarge)`, recovery rolls the operation back | JVM `EpubArchiveGuardTest`: an endless stream with limit 1 000 000 reads exactly 1 000 001 bytes and writes at most 1 000 000; exact-limit input passes. Instrumented (both roots; endless streams of unknown size through the provider, counted by `bytesServed`): `oversizedAndEndlessInputsStopAtTheLimit` (`Unsupported(TooLarge)`), `registeringOversizedOrEndlessUserFilesFailsAndKeepsThem` (`Failed(TooLarge)`, no asset row, user files' hash and mtime unchanged), `openingOversizedOrEndlessReplacementsFailsAndKeepsReadingState` (`FingerprintMismatch`, reading state and asset unchanged), `recoveryRejectsAnOversizedOrEndlessCommittedBook` (aborted, no title created, folder removed). Each asserts served bytes below limit + 1 MiB and empty working and staging directories |

### Commands and results (final code)

Environment as above (JBR 21, `ANDROID_HOME=C:\SDK\android`).

| Check | Result |
| --- | --- |
| `./gradlew spotlessApply` then `./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration -Penable-updater=false -Pinclude-telemetry=false` | Passed (59 s). 168 unit tests, 0 failures/errors |
| `./gradlew assembleDebug -Penable-updater=false -Pinclude-telemetry=false` | Passed (51 s) |
| `./gradlew assembleRelease -Pdist=ci -Penable-updater=false -Pinclude-telemetry=false` | Passed (3 m 44 s; `minifyReleaseWithR8` executed) |
| `./gradlew connectedDebugAndroidTest -Penable-updater=false -Pinclude-telemetry=false -Pandroid.testInstrumentationRunnerArguments.package=eu.kanade.tachiyomi.data.epub`, `Pixel_10` (API 37), cold boot | **49 tests, 0 failures**: `EpubImportInstrumentedTest` 40 (28 existing + 12 new), `EpubLocalDiscoveryInstrumentedTest` 6, `EpubDatabaseInstrumentedTest` 3 (1 m 39 s) |
| Same, `P01_API_26` (API 26), cold boot | **49 tests, 0 failures**, same split (1 m 23 s) |
| `EpubImportInstrumentedTest` on API 37 with recovery acting on the snapshot (pre-fix check, reverted afterwards) | 40 tests, 2 failures: `recoveryRacingAFinishingImportKeepsTheBook[FILE]` and `[PROVIDER]` |

### Not run in this change set

- `EpubReaderInstrumentedTest` (P01-01 reader suite): no reader, ViewModel or navigator code changed.
- The real system-picker walkthrough and the real APK upgrade: the import UI is unchanged. The upgrade evidence predates the `CHECK(phase IN (1, …, 6))` change. A device that ran a build of this PR before the change still has the old 1–5 constraint and must reinstall. The migration itself is covered by `EpubDatabaseInstrumentedTest`, which compares migrated and fresh schemas.
- `-Pandroid.testInstrumentationRunnerArguments.class=…#method` matched 0 tests for these parameterized tests (names carry `[FILE]`/`[PROVIDER]`), so the pre-fix check ran the whole class.

### Remaining limitations

- A rollback whose storage never comes back stays `ABORTING` (hidden and retried each pass) indefinitely; clearing it is P01-06 storage management.
- "Confirmed absent" relies on the provider answering `exists()` after a listing; a provider that answers queries but returns incomplete listings is not detected.

---

# P01-01 test matrix

## Sixth remediation revalidation, 2026-10-17 (current)

This section covers reviewed head `8bf5ff9de5ecc6e91fa79d2d4a1437667370305d` on `novels-p01-01-foundation` ([PR #1](https://github.com/MohamedMaged9258/mihon/pull/1), base `novels`). The review found that Reopen retried its capture after a rebinding but never recorded the destination the replacement had to restore. All results below are **new evidence on the final code of this change set**. The fifth remediation section is now historical; its fixes are unchanged and their tests are part of the full-suite runs below.

### Issue → resolution → evidence

| Review issue | Resolution | Verified by |
| --- | --- | --- |
| Reopen called `saveDisplayedPosition()` but left the pending target null, so `newBinding()` fell back to `lastAccepted`. A persisted, B displayed with its capture pending, Reopen, recreation: the replacement restored A, the retried capture accepted A, Reopen reported success and rebuilt at A | Back's `exitTarget` became one **pending target** (`pendingTarget`) with an owner (`pendingFor = Exit` or `Reopen`). In Ready, `reopen()` records the displayed location as the Reopen's target before anything suspends; `newBinding()` restores it (`pendingTarget ?: lastAccepted ?: restoreTarget`), and the replacement reaches Ready only after verifying that location, so only a capture of the restored B can satisfy the Reopen. Navigation and font changes are deferred while a Reopen is pending or its failure awaits Retry/Stay (`State.reopening`, `State.canNavigate`); Readium's lagging locator report still updates the target. Success clears the target before `open()`; failure or timeout keeps it (a rotation behind the dialog still restores B, Retry saves it); Stay drops it. Back supersedes a pending Reopen and takes over its target (`pendingFor = Exit`). Cleanup is owner-guarded, so a superseded Reopen cannot clear Back's target. Obsolete-callback and capture-revision checks are unchanged | Instrumented, each storing A (≈ p25), delaying the anchor lookup before navigating to B (p50), and checking that B is visible and reported by Readium while the accepted checkpoint and file still name A; B is never checkpointed manually. `cancellableCapturePendingAcrossRecreationReopensAtDisplayedPassage` (cancellable delay) and `callbackCapturePendingAcrossRecreationReopensAtDisplayedPassage` (a `suspendCoroutine` lookup resumed after cancellation) invoke Reopen and recreate in one main-thread block, wait for the replacement to be Ready on a new owner within the 5 s bound, and assert the same session, no failure, A still accepted and in the file, and B visible on the replacement. After resuming the obsolete lookups they assert A is still accepted and the session unchanged (obsolete callbacks neither save A nor complete the Reopen). They then release the replacement's lookup and assert a new session with B visible, a file different from A, and B in a fresh Activity and ViewModel. `reopenCapturePendingPastBoundAcrossRecreationFailsThenRetry` / `…FailsThenStay`: no lookup completes, so the Reopen fails after recreation with the same session, Ready, navigation deferred, A accepted and in the file, and B on the replacement. Retry (lookups restored) gives a new session at B with a file different from A; Stay re-enables navigation, and after reading p35 and recreating, the replacement shows the accepted passage, not the dropped B. `backSupersedesPendingReopenAcrossRecreation`: Reopen and recreate, then Back on the replacement; `Idle → Saving → Done` with the same session (never reopened), a file different from A, and B in a fresh Activity. `navigationDuringPendingReopenIsIgnored`: during a pending Reopen, `canNavigate` is false and `next()`/`goToToc()` return false; the new session opens at B. All existing recreation, capture-failure and Reopen-failure tests are retained unchanged. **Pre-fix (`8bf5ff9de` ViewModel with the new tests, API 26):** all 6 fail. The five recreation tests report `The replacement must restore the pending B, got [20, 21, 22, 23]` (the replacement restored A); the navigation test fails on `canNavigate` |
| A pending target is Readium's raw locator, without an element anchor. `ReadiumEpubReaderAdapter.isAt()` accepted such a locator on its href alone, so a replacement still at the start of the resource could count as restored. Found by one API 37 full-suite failure of the existing Back test `callbackCapturePendingAcrossRecreationSavesDisplayedPassage` on the first version of this fix (the exit stored `#long-start`; 5 isolated reruns passed) | A locator without a CSS selector must be within 1.5 viewports of its progression. The viewport's fraction of the resource is measured in the WebView (`min(innerWidth/scrollWidth, innerHeight/scrollHeight)`). Otherwise `restore()` retries `go()` and, after its three attempts, fails in a controlled way. Anchored checkpoints keep the DOM check | Device logs from the final API 37 run show 9 such checks, all for B: progression `0.611` against target `0.611`, page fraction `0.0555`, accepted (the resource start would be `0.0`). The full suites below pass with the change. The intermittent failure was not reproduced on demand, so linking it to this gap is an inference from the stored anchor |

### Commands and results (final code)

Environment: JBR 21 (Android Studio), `ANDROID_HOME=C:\SDK\android`, `TEMP`/`TMP=C:\jtmp`, `-Penable-updater=false -Pinclude-telemetry=false`.

| Check | Result |
| --- | --- |
| `spotlessCheck testDebugUnitTest verifySqlDelightMigration` | Passed (38 s). 158 unit tests, 0 failures (33 EPUB) |
| Full `EpubReaderInstrumentedTest` on the `Pixel_10` AVD (API 37, WebView 153.0.8010.36), run alone | **50 tests, 0 failures/errors/skips** (device time 432.3 s) |
| Same suite on the `P01_API_26` AVD (API 26, Chrome WebView 69.0.3497.100), cold boot, run alone | **50 tests, 0 failures/errors/skips** (device time 347.3 s) |
| `assembleRelease -Pdist=ci` | Passed (3 m 6 s; `minifyReleaseWithR8` executed). The x86_64 release APK is 42,275,890 bytes with zero `epub-fixtures` entries |
| The 6 new instrumented tests on the **old** ViewModel (API 26) | 6 of 6 fail |

### Test-environment findings

- A first API 26 run of the new tests overlapped a second Gradle invocation on the same AVD and is not counted. Afterwards, even the unchanged Back test `cancellableCapturePendingAcrossRecreationSavesDisplayedPassage` timed out with the replacement still Restoring at 4 s; after a cold boot it passed, and the new tests were run from that state.
- The first version of the fix (without the progression check) passed the full API 26 suite (50/50, 367.2 s). Its first API 37 full run had 49/50: the existing Back test `callbackCapturePendingAcrossRecreationSavesDisplayedPassage` stored `#long-start`. Five isolated reruns passed. That run is not counted; it led to the progression check above, and both suites were rerun on the final code.
- Two new tests were corrected on the device before the counted runs. The capture-stage Reopen helper first checked navigation after reopening, which persisted p1 and broke the fresh-Activity check. The Stay test first expected p35 itself after recreation rather than the accepted anchor of its page. The timeout variants do not require the replacement to be Ready before the 5 s bound.

### Remaining limitations

- A Reopen that fails while the reader is in Error keeps its target, so Retry fails again until Stay drops it; Reopen then recovers from the accepted checkpoint.
- Process death during an exit or a reopen remains uncovered: the pending target lives in the ViewModel, not in `SavedStateHandle`.

### Acceptance

Reopen now owns its destination across rebinding. It saves and restores the displayed B or fails in a controlled way; it never accepts the older A as success. Regression tests fail on the old code and pass on the final code. P01-01's exit evidence (DEVELOPMENT_PLAN §8) remains met.

---

## Fifth remediation revalidation, 2026-10-17 (historical)

This section covers reviewed head `382245ee311b94f4a34fc77c1dc778e97dba146d` on `novels-p01-01-foundation` ([PR #1](https://github.com/MohamedMaged9258/mihon/pull/1), base `novels`). The review found that Reopen ignored the result of saving the displayed passage. All results below are **new evidence on the final code of this change set**. The fourth remediation section is now historical; its fixes are unchanged and their tests are part of the full-suite runs below.

### Issue → resolution → evidence

| Review issue | Resolution | Verified by |
| --- | --- | --- |
| Reopen ran `checkpointNow(); open(target)`. With A persisted and B displayed, a failed or unavailable capture of B (or a failed write) still closed the session and rebuilt it from A | In Ready, Reopen saves the displayed passage through `saveDisplayedPosition()`, the capture-and-flush loop Back already used (extracted from `saveExitPosition()`, unchanged for Back). A capture cancelled by a rebinding is retried once the replacement is Ready. If the newest capture is not `Accepted`, or the write fails, or the 5 s bound passes, the session, binding and checkpoints are kept and `State.reopenFailed` shows "Reading position not saved" with Retry and Stay. When the reader is not Ready (opening, restoring, reflowing, Error), nothing new is displayed, so Reopen recovers from the accepted checkpoint as before. Back clears a reopen failure and still cancels a pending reopen | `reopenCaptureFailureKeepsSessionAndRetries` (B's lookup throws), `reopenUnavailableCaptureKeepsSessionAndRetries` (test hook `anchorUnavailable`: no anchor) and `reopenPersistenceFailureKeepsSessionAndRetries` (writes throw). The first two use A (≈ p25) persisted and B (p50) displayed and reported by Readium, with no manual checkpoint of B. Each taps Reopen and asserts the same session, Ready, B visible, the bound navigator unchanged, the file still A (and `acceptedCheckpoint` still A for the capture cases), and the dialog shown. It then removes the fault, taps the dialog's Retry, and asserts a new session, p50 visible, and a file different from A. `reopenDuringReaderErrorRecoversFromCheckpoint`: a resource error, then Reopen gives a new Ready session at the checkpointed passage. Ordinary, rapid (5×) and recreation-during-reopen cases (`normalReopen…`, `rapidRepeatedReopen…`, `recreationDuringOpening…`) and every Back/Reopen test still pass. **Pre-fix (old `reopen()` body, API 37):** the three failure tests fail; with the final assertion order, the capture case reports `Reopen must not replace the session expected:<1> but was:<2>`. The error-recovery test passes on old code, as intended |

The first full run of this change set (API 37, 42/44) found two tests that depended on the old behavior. `recreationDuringOpeningBindsTheNewSession` reopens and recreates at once; the cancelled capture became a reopen failure. This led to sharing Back's rebinding retry with Reopen. `overlappingRequestsThenFailureStayAndRetry` reopened while writes were still failing; it now restores successful writes before Stay → Reopen → Back, because a reopen that cannot save is now refused by design.

### Commands and results (final code)

Environment: JBR 21 (Android Studio), `ANDROID_HOME=C:\SDK\android`, `TEMP`/`TMP=C:\jtmp`, `-Penable-updater=false -Pinclude-telemetry=false`.

| Check | Result |
| --- | --- |
| `spotlessCheck testDebugUnitTest verifySqlDelightMigration` | Passed (1 m 53 s). 158 unit tests, 0 failures |
| Full `EpubReaderInstrumentedTest` on the `Pixel_10` AVD (API 37, WebView 153.0.8010.36), run alone | **44 tests, 0 failures/errors/skips** (device time 407.2 s) |
| Same suite on the `P01_API_26` AVD (API 26), cold boot, run alone | **44 tests, 0 failures/errors/skips** (device time 446.4 s) |
| The 4 new instrumented tests on the **old** `reopen()` (API 37) | 3 of 4 fail (the three failure cases); the error-recovery case passes |

### Remaining limitations

- Not rerun in this change set (code untouched): `assembleRelease` and the minified-release fixture check.
- When the reader is in Error, Reopen recovers from the accepted checkpoint. A passage displayed before the error but never accepted is not saved, because no capture is possible in Error.
- Process death during an exit or a reopen remains uncovered, as before.

### Acceptance

Reopen no longer replaces the session when the displayed passage cannot be saved, and it offers a recoverable failure with Retry. Regression tests fail on the old code and pass on the final code. P01-01's exit evidence (DEVELOPMENT_PLAN §8) remains met.

---

## Fourth remediation revalidation, 2026-10-17 (historical)

This section covers reviewed head `f103c059c25816acd17a5ea12814a023e8310a4f` on `novels-p01-01-foundation` ([PR #1](https://github.com/MohamedMaged9258/mihon/pull/1), base `novels`). The review found that a position capture pending when the navigator binding was replaced could be lost while Back still reported success. All results below are **new evidence on the final code of this change set**. The third remediation section is now historical; its fixes are unchanged and their tests are part of the full-suite runs below.

### Issue → resolution → evidence

| Review issue | Resolution | Verified by |
| --- | --- | --- |
| Capture-stage exit/recreation: A persisted, B displayed, B's capture pending, Back, then recreation. Rebinding cancelled the capture, `awaitLatest()` returned on Job completion, the writer flushed A and the exit became Done. `newBinding()` also restored A | `EpubPositionCapture` gives every capture an explicit outcome (`Accepted`, `Superseded`, `Canceled`, `Unavailable`, `Failed`), completed at supersession or cancellation without waiting for a callback-style lookup. `checkpointNow()` flushes only after `Accepted`. Back records an **exit target** (the location in Ready, updated by newer locations reported in Ready while saving, because Readium's locator can lag a jump inside one resource). `newBinding()` restores it on the replacement, which verifies the rendered location; the exit then captures through the new owner and flushes. Any other outcome, a restoration error or the 5 s bound gives `Failed` and keeps the previous checkpoint. Back before Ready (no exit target) flushes the authoritative checkpoint; an unchanged passage captures its held anchor | Unit (`EpubPositionCaptureTest`, 11 tests, 5 new): every outcome; `cancel()` completes `Canceled` while a callback lookup is still pending, and its late resume is never accepted; no request outside Ready. Instrumented, each storing A (≈ p25), delaying B's (p50) lookup, and checking that B is displayed and reported by Readium while the checkpoint and file still name A: `cancellableCapturePendingAcrossRecreationSavesDisplayedPassage` (cancellable delay) and `callbackCapturePendingAcrossRecreationSavesDisplayedPassage` (a `suspendCoroutine` lookup resumed after cancellation). Both press Back and recreate in one main-thread block, wait for the replacement to be Ready, assert the checkpoint is still A (also after resuming the obsolete lookups), then assert `Idle → Saving → Done`, the same session, a file different from A, and p50 in a fresh Activity. `capturePendingPastExitBoundFailsAndKeepsCheckpoint`: no lookup completes → `Failed`, file still A, Retry → p50. `captureFailureDuringBackFailsAndKeepsCheckpoint`: the lookup throws → `Failed`, file still A, Retry → p50. **Pre-fix (old production code plus only the new lookup hook, API 26):** all four fail; the failure test's Activity finished (Done storing A) instead of showing Failed |

### Commands and results (final code)

Environment as below (JBR 21, `ANDROID_HOME=C:\SDK\android`).

| Check | Result |
| --- | --- |
| `:app:spotlessCheck` | Passed |
| `:app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.epub.*"` | Passed. 33 EPUB unit tests, 0 failures (11 in `EpubPositionCaptureTest`) |
| Full `EpubReaderInstrumentedTest` on the `P01_API_26` AVD (API 26), run alone | **40 tests, 0 failures/errors/skips** |
| Same suite on the `Pixel_10` AVD (API 37), cold boot, run alone | **40 tests, 0 failures/errors/skips** (device time 291.4 s) |
| The 4 new instrumented tests on the **old** production code (API 26) | 4 of 4 fail |

### Test-environment findings

- The first API 26 run of the new tests exposed two defects that were then fixed: the exit loop read the published phase, which lags the gate during rebinding, and the exit target was the lagging Readium locator. The test now waits until Readium reports B.
- An API 37 run was lost to the emulator (`adb shell` stopped responding, Gradle reported no compatible device). It is not counted; the suite passed after a cold boot.
- `-Pandroid.testInstrumentationRunnerArguments.class` with several comma-separated methods ran only the first one on this setup; targeted reruns were run one method at a time.

### Remaining limitations

- Not rerun in this change set (areas untouched; historical results still apply): `assembleRelease`, `verifySqlDelightMigration`, the full `:app` unit suite outside EPUB, and everything listed as not rerun in the third remediation section.
- A Back during a cross-resource transitional locator, followed by recreation before Readium reports the new resource, restores that transitional locator. The replacement's capture still applies the transitional check.
- Process death during an exit remains uncovered, as before.

### Acceptance

The capture-stage issue is resolved, with regression tests that fail on the old production code and pass on the final code, and the full instrumented suite passes on API 26 and API 37. P01-01's exit evidence (DEVELOPMENT_PLAN §8) remains met, now including exits that span a navigator rebinding. The package is ready for review.

---

## Third remediation revalidation, 2026-10-17 (historical)

This section covers reviewed head `b40d7eddf074fddddafef484d3c540578b5c6786` on `novels-p01-01-foundation` ([PR #1](https://github.com/MohamedMaged9258/mihon/pull/1), base `novels`). The review raised three remaining issues. All results below are **new evidence on the final code of this change set**.

The second remediation section is now historical. Its fixes are unchanged here: ownership, capture ordering, publication hand-off, the ordered writer, the navigation policy and the viewport. Their instrumented tests are part of the full-suite runs below.

### Issue → resolution → evidence

| Review issue | Resolution | Verified by |
| --- | --- | --- |
| 1. Restored checkpoint never reached the diagnostic file | Persistence is now tracked separately from location identity. On install, the restored envelope is submitted to `EpubCheckpointWriter` unless the file already holds exactly that envelope. Capture dedup on `lastAccepted` is unchanged, so an unchanged anchor is not resubmitted, and the flush now covers the restored value. A publication start submits nothing, so no default locator is written. | `savedStateCheckpointOverridesOlderFile` and `savedStateCheckpointWithoutFile`. Each stores A (≈ p25), then accepts B (p50) with a failing write. A new ViewModel is created from saved instance state (`viewModelStore.clear()` + recreate) and restores B. The test asserts the anchor is unchanged, presses Back, and opens a fresh Activity, which shows p50. `restoredCheckpointWriteFailureOffersRetry`: Back → `Failed`, the file still holds A, and Retry → p50. **Pre-fix (old production code):** all three fail. The fresh reader opened at p23–27 (A), the file was missing after a "successful" Back, and Back finished instead of failing. |
| 2. Reader events from a worker thread | `Binding.emit()` delivers inline on the main thread. Otherwise it posts to the main looper without waiting, and the posted event keeps the binding's owner. At delivery, `detached` and then `gate.isCurrent(owner)` are rechecked. `onNavigatorEvent()` asserts the main thread. No coroutine scope was added. | `workerThreadResourceFailureIsHandledOnMain`, `queuedObsoleteFailureIsIgnoredAfterRebind` (the error is queued behind a main-thread block that detaches and rebinds synchronously, so it arrives for an obsolete owner), `currentFailureDuringCaptureClosesPersistence` (5 s capture delay) and `currentFailureDuringReflowClosesPersistence` (5 s operation delay). An obsolete error changes neither state nor checkpoint. A current error gives `Error(ResourceUnavailable)`, after which neither the pending capture nor the reflow is accepted, and neither the checkpoint nor the file changes. **Pre-fix:** the queued obsolete error put the reader into Error. **Mutation (inline delivery):** the worker-thread and queued tests crash on the main-thread contract check. |
| 3. Exit and Reopen raced | Reopen is rejected in the ViewModel, and disabled in the footer, unless `exit == Idle`. Back cancels a pending reopen. Exit attempts are numbered, and only the newest one, while still `Saving`, publishes. `install()` keeps `State.exit`. Reopen stays available during opening and restoration when no exit is active. | Each test uses delayed capture and persistence and records every published `(exit, sessionId)`: `reopenThenBackExitsWithoutReopening`, `backThenReopenIsRejected`, `mixedReopenAndBackRequestsExitOnce`, `reopenButtonIsDisabledWhileSaving`, `recreationWhileSavingStillFinishes` and `overlappingRequestsThenFailureStayAndRetry`. Each asserts transitions of exactly `Idle → Saving → Done`, the same session throughout, a finished Activity, and the passage restored in a fresh Activity. The failure test also checks that Reopen is rejected while Failed, and that Stay → Reopen → Back works. **Pre-fix:** 4 of these fail (`reopenThenBack…`, `backThenReopen…`, `mixed…`, `overlapping…`). The session changed 1 → 2 during Back, and the state went `Done → Idle` after an exit. **Mutation (button always enabled):** the button test fails. |

### Commands and results (final code)

Environment as below (JBR 21, `ANDROID_HOME=C:\SDK\android`, `TEMP`/`TMP=C:\jtmp`, `-Penable-updater=false -Pinclude-telemetry=false`).

| Check | Result |
| --- | --- |
| `spotlessCheck testDebugUnitTest verifySqlDelightMigration --rerun-tasks` | Passed (8 m 38 s). 154 unit tests, 0 failures (58 in `:app`, 29 of them EPUB) |
| `assembleDebug` | Passed (2 m 30 s) |
| `assembleRelease -Pdist=ci` | Passed (9 m 32 s; `minifyReleaseWithR8` executed). The x86_64 release APK is 42,275,890 bytes with zero `epub-fixtures` entries |
| Full `EpubReaderInstrumentedTest` on the `Pixel_10` AVD (API 37, WebView 153.0.8010.36), cold boot, run alone | **36 tests, 0 failures/errors/skips** (device time 221.6 s). Cold open 1,027 ms; warm reopens 835–901 ms |
| Same suite on the `P01_API_26` AVD (API 26, Chrome WebView 69.0.3497.100), cold boot, run alone | **36 tests, 0 failures/errors/skips** (device time 414.5 s). Cold open 1,826 ms; warm reopens 3,532–5,570 ms (provisional; this 1.5 GB AVD is memory-constrained, see the historical notes) |
| The 13 new tests on the **old** production code (API 37) | 9 of 13 fail: the 8 listed above, plus `reopenButtonIsDisabledWhileSaving`, which failed for a test-side reason (an off-screen footer button) that was then fixed. Its UI guard is proven by the mutation run instead. The other 4 pass on old code: worker-thread delivery did not crash before the main-thread check existed, current-owner errors during capture and reflow were already handled, and recreation while saving already finished |

### Test-environment findings

- A full API 37 run was lost to the emulator itself: `system_server` died (`DeadSystemException`) after the low-memory killer breached its minimum watermark. It is not counted. The suite passed 36/36 after a cold boot.
- A footer swipe starting at the screen edge is the system Back gesture. The button test swipes away from the edges.
- UiAutomator reports the Compose `Text` node as always enabled. The enabled state is on its parent button node.
- Rebinding during recreation cancels a pending capture by design (capture-ordering contract). The recreation-while-saving test therefore saves an already accepted page whose write is held for 3 s. (Superseded by the fourth remediation: the exit now transfers its target to the replacement.)

### Remaining limitations

- Restored-SavedStateHandle tests create a new ViewModel through `viewModelStore.clear()` + recreation, not a real `am kill`. Process death in the middle of an exit is not covered: the exit state is not saved and the reader reopens normally.
- The exit timeout is 5 s. A save that does not finish in time ends in `Failed("Saving timed out")`.
- Restoration precision is still paragraph-level. Accessibility gestures during operations and text selection are as described in the historical section below.
- Not rerun in this change set (areas untouched; historical results still apply): dependency report, three-package coexistence, updater proxy, Local image-reader regression, resource-cycling snapshots, real `am kill` restoration, and viewport and selection screenshots (the viewport test itself ran in both full suites).

### Acceptance

All three issues are resolved, with regression tests that fail on the old production code (or under mutation) and pass on the final code. The full instrumented suite passes on API 26 and API 37, as do the format, unit, migration, debug and minified-release checks. Together with the unchanged evidence from the earlier rounds, P01-01's exit evidence (DEVELOPMENT_PLAN §8) is met. That evidence now also covers the restored-checkpoint persistence, the main-thread event contract and the exit/reopen policy in `CONTRACTS.md`. The package is ready for review, and P01-02 may start once PR #1 is accepted.

---

## Second remediation revalidation, 2026-10-16 (historical)

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

# Native EPUB workspace

P01-01 adds a debug-only, local-fixture reader to the existing `:app` module. It deliberately does not alter `ReaderActivity`, `ReaderViewModel`, `ChapterLoader`, `EpubPageLoader`, `EpubReader`, `LocalSource`, source URLs, or `Source.getPageList()`.

## Setup and architecture

- JDK: Android Studio JBR 21; `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`
- Android SDK: `ANDROID_HOME=C:\SDK\android`
- Engine: Readium Kotlin `3.4.0`, limited to `shared`, `streamer`, and `navigator`
- Host: `EpubReaderActivity` → `EpubReaderFragment` → child `EpubNavigatorFragment`
- Boundary: `ReadiumEpubReaderAdapter` owns all Readium navigation, publication-link, locator, and preference APIs.
- State: `EpubReaderViewModel` retains the open session for rotation and saves only the fixture reference, preferences, and versioned locator envelope for process recovery.
- Ownership: callbacks carry an `EpubOwner(sessionId, bindingId)`; the host binds only a navigator created for the active session. A diagnostic per-fixture checkpoint file supports close-and-open restoration; see [`CONTRACTS.md`](CONTRACTS.md).

The opener streams a debug asset into a private cache file while hashing it. It rejects a fingerprint mismatch, opens only an EPUB parser, installs no LCP protection, and supplies a network client that records an attempt count but rejects every request without logging the URI.

## Debug route

Build a debug variant, then open **More → Settings → Advanced → Debug info → Native EPUB fixtures**. The Activity and route are non-exported. The launcher component is disabled by a release resource, and fixture payloads exist only under `src/debug/assets`.

Regenerate the deterministic CC0 fixtures with:

```powershell
./tools/epub-fixtures/generate.ps1
```

## Verification commands

```text
./gradlew spotlessCheck
./gradlew testDebugUnitTest
./gradlew verifySqlDelightMigration
./gradlew assembleDebug -Penable-updater=false -Pinclude-telemetry=false
./gradlew assembleRelease -Pdist=ci -Penable-updater=false -Pinclude-telemetry=false
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencyInsight --dependency org.readium.kotlin-toolkit --configuration releaseRuntimeClasspath
./gradlew connectedDebugAndroidTest -Penable-updater=false -Pinclude-telemetry=false
```

## Lifecycle reproduction

Open `EPUB 3 / nested ToC / Arabic`, use ToC → **Long section** and page forward to a later `EPUB3-LONG-P0xx` paragraph (or navigate to `EPUB3-PASSAGE-ARABIC`), tap **Checkpoint**, change the font size, rotate, and use **Reopen**. For actual process recovery press Home, run `adb shell am kill io.github.mohamedmaged9258.mihon.epub.dev`, then restore the task from Recents. `force-stop` is not an equivalent test because it removes the task's normal restoration path.

P01-01 is not a production EPUB import path. Its fixture identity must not be turned into fake manga/chapter/database identities.

## P01-02 data model and managed imports

P01-02 adds the production storage and data foundation. It does not change the reader routing or add a user-facing Import action; those are P01-03.

- **Schema.** `20.sqm` adds `epub_asset`, `epub_import_operation`, `epub_reading_state`, `epub_title_preferences` and `epub_location_bookmark` alongside the existing title/item tables.
- **Managed import.** An imported EPUB is copied to `<root>/local/<key>/book.epub` under an immutable random key, verified by SHA-256, and committed through a durable journal. It becomes a real Local title (`source 0`, URL = key) with one item. The Local source shows and searches it by its metadata title.
- **Duplicates.** Exact duplicates (SHA-256) of managed imports return the existing identity. Identical user-owned files are allowed.
- **Existing Local EPUBs.** These can be registered in place as user-owned assets. They are never copied or modified.
- **Missing files.** A registered item whose file is missing or whose storage is unavailable is kept, with its read state and EPUB state; only its availability changes.
- **Opening.** Production local inspection/opening is engine-isolated, fingerprint-checked, and never deletes a durable original.

Debug route: **More → Settings → Advanced → Debug info → EPUB import diagnostics**. It picks one or more documents with the system picker and imports them through the production service. It lists assets and opens them with the production opener. It also registers Local EPUBs and runs recovery. It is non-exported and disabled in release builds.

The architecture, policies and limits are in [`DECISIONS.md`](DECISIONS.md#p01-02-decisions), the service APIs in [`CONTRACTS.md`](CONTRACTS.md#p01-02-production-contracts), and the evidence in [`TEST_MATRIX.md`](TEST_MATRIX.md).

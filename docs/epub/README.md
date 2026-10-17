# Native EPUB feasibility workspace

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

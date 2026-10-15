# Mihon native EPUB reader — development plan v1.0

Date: 15 October 2026<br>
Status: P01-01 implementation incomplete; host Gradle/device evidence blocked.<br>
Scope: Part 1 — native EPUB/light-novel support inside the existing Mihon app and library.<br>
Next phase: Kavita extension integration, after the release gate in this plan passes.

## 1. Product goal and fixed requirements

A user imports an EPUB, finds it among their existing manga and comics, taps Read, and reads it inside Mihon. They can change typography, rotate the screen, leave the app, and resume at the same passage. Existing categories, favourites, history, read/unread state, and backup entry points continue to apply.

The implementation has one app, one library, and one shared title identity system. A separate internal reader module or Activity is allowed; it must feel like part of Mihon and must not introduce a second bookshelf, account, external reading app, or embedded Kavita website.

This phase delivers a usable reader, not just an EPUB parser or a way to display extracted images.

### Included in the first release

- DRM-free reflowable EPUB 2 and EPUB 3.
- English and Arabic/RTL fixtures, including mixed text, images and footnotes.
- Reading from the existing Local source and a user-facing Import EPUB action.
- One or several EPUBs selected through Android's system document picker.
- Library cover, title, author and available descriptive metadata.
- Books using existing categories, favourites, search, sorting and history.
- Automatic routing to the EPUB reader, with a saved reader override for ambiguous/image EPUBs.
- Paginated reading and vertical scrolling within content sections; explicit navigation between sections.
- EPUB 2 NCX and EPUB 3 navigation documents, nested table of contents, internal links and footnote navigation.
- Font family/size, line spacing, margins, alignment, publisher-style control and light/dark/sepia reading themes.
- Global book-reader defaults and per-title overrides.
- Stable resume after reflow, rotation, backgrounding, process recreation and app restart.
- Selection/copy and use of the available Android text-action menu; whole-book text search.
- Multiple location bookmarks in a book, distinct from Mihon's existing whole-item bookmark flag.
- Offline access, clear storage/error states, and preservation of originals.
- Backup/restore of book metadata, reader state, preferences and location bookmarks.
- Tests and documented regression evidence for manga/comic reading.

### Deferred

Kavita changes, OPDS browsing, web-novel sources, production remote EPUB downloading, detailed server synchronization, PDF/TXT/MOBI/AZW readers, DRM systems, TTS, audiobook support, persistent highlights/annotations, and new novel tracker integrations.

The engine may support more formats than the app exposes. Only tested capabilities should appear in the first-release UI. Seamless scrolling across every EPUB spine document is not promised: the selected navigator has limitations there.

## 2. Verified baseline and dependency decision

### Proposed starting baseline

| Item | Baseline |
| --- | --- |
| Mihon | v0.21.2 |
| Mihon commit | 446f6df82c390ab760802c2fce3b677bce3f31c1 |
| Reader engine candidate | Readium Kotlin 3.4.0 |
| Readium tag commit | 1b1f6b308a7b6f968b2bf1e66c84912879466f75 |
| Renderer | Established EpubNavigatorFragment behind a Mihon adapter |
| Included Readium modules | shared, streamer, navigator; only additional modules demonstrated necessary |
| Excluded engine modules | OPDS, LCP, PDF adapters, audio and TTS |

The versions were verified on 15 October 2026. They are proposed pins, subject to P01-01 build and lifecycle validation. Use Maven artifacts rather than tracking Readium's development branch. Do not silently upgrade either repository while implementing a work package.

Readium 3.4.0 documents minimum Android API 24 and compile SDK 37. Confirm the pinned Mihon toolchain, AAR requirements, dependency resolution, desugaring and minimum-supported-device behavior in the initial build. The documented Kotlin/Gradle source-build requirements are not automatically requirements to adopt Readium as Maven artifacts.

Readium's new Compose web navigators are experimental. The established Fragment navigator is the preferred starting point. Mihon's BaseActivity already extends AppCompatActivity, so it can host fragments; a global Activity-base refactor is unnecessary.

Readium supplies content rendering and navigation, not a finished bookshelf or complete reader toolbar. Mihon must own its reader UI and persistent data.

The v0.21.2 release announces future removal of legacy Tachiyomi 1.4 extension support. Retain the compatibility of the chosen Mihon baseline during this phase. Record the future extension direction, but do not change extension loading to deliver a local EPUB reader.

Development occurs in the maintained fork at https://github.com/MohamedMaged9258/mihon. P01-01 uses the temporary label `Mihon EPUB Dev` and application ID `io.github.mohamedmaged9258.mihon.epub` (plus the existing `.dev` debug suffix). Updating is disabled for every build type and retained repository metadata points to the fork. Do not push to upstream.

## 3. Architecture

### Shared content flow

Existing library entry → existing readable item → content resolver → image reader or EPUB reader.

For an EPUB, the resolver supplies a local publication asset. A reader adapter opens that asset and exposes navigation, a locator stream, preferences and errors to the Mihon reader screen. Repository services persist user state and update the shared library/history.

These are proposed responsibilities, not existing class names:

| Component | Responsibility |
| --- | --- |
| ReadingContentResolver | Resolve an existing title/item to image pages or an EPUB asset. |
| EpubPublicationRepository | Open/close a publication and access metadata, cover, reading order, ToC and search. |
| EpubReaderAdapter | Contain Readium-specific types and lifecycle integration. |
| EpubReaderViewModel | Coordinate reading state, navigation, controls and user actions. |
| EpubStateRepository | Own persistent resume, furthest position, reset state and location bookmarks. |
| EpubImportService | Validate/copy an EPUB, establish identity and add it to the shared library. |
| PublicationStorage | Resolve managed/existing local files and distinguish durable assets from disposable cache. |
| ReaderLauncher | Route every Read/Resume entry point consistently. |

Prefer a focused book-reader host Activity derived from the existing BaseActivity, with the same theming/security behavior, rather than forcing text publications into image-page viewer classes. Reuse common toolbar/overlay components and shared services where appropriate. P01-01 confirms the exact host arrangement.

Choose module boundaries after inspecting the fork. A focused Android reader module may contain Readium adapters while domain/data code remains engine-independent. Do not restructure unrelated Mihon modules.

### Content and identity contract

- Existing manga/title and chapter/readable-item identities remain authoritative.
- One standalone EPUB maps to one shared library title with one readable book item.
- An explicitly grouped local series may contain several EPUB book/volume items.
- Internal EPUB sections remain publication navigation entries; they are not duplicated into fake database chapters.
- Semantic content type and payload format are different concepts: a comic can also be packaged as EPUB.
- Preserve the current image reader for existing image EPUB workflows. New reflowable book imports default to the EPUB reader. Save explicit user overrides and test fixed-layout/image EPUB cases.
- Existing source IDs and URLs must not change.
- Keep Source.getPageList() and current image extension behavior intact.
- The future remote-publication capability is documented at an internal boundary now. It does not require changing the external source API during this local-only phase.

Conceptual resolver result:

- ImagePages: continue through the existing image loader.
- EpubPublication: item ID, asset reference, media type, verified content fingerprint and detected layout.
- Error: typed missing-file, permission, corrupt-file, unsupported-protection, or unsupported-format result.

Do not smuggle EPUB bytes or HTML through Page.imageUrl, image interceptors, synthetic screenshots, genre strings or scanlator fields.

## 4. Local import and file ownership

### Existing Local source

Continue supporting EPUBs in the existing local title folders. Inspect the current one-level folder lookup before selecting any managed import layout; do not introduce an extra directory level that LocalSource cannot discover.

Keep existing chapter URLs stable. Update metadata/cover extraction where needed: the current EPUB helper fills some fields but uses the EPUB title as the readable item's name, and cover extraction currently takes an image from content. A native book import should use declared publication metadata and cover first.

### Import EPUB action

1. Select one or more files through Android's Storage Access Framework.
2. Validate each EPUB by structure/media type, not filename alone.
3. Stream it into a temporary location; compute a content fingerprint without loading the whole file into RAM.
4. Open it through the publication parser and extract supported metadata.
5. Detect exact duplicates. Present a useful result rather than silently creating another entry or replacing a different edition.
6. Commit the complete file atomically to a stable, Local-source-compatible managed folder and create/reuse the shared title/item records.
7. Remove partial files on cancellation/failure and summarize a batch per file.

The default import is a managed copy in the configured persistent library storage. Moving or deleting the original picker file must not break the imported copy. Existing manually managed Local-source files remain user-owned and are not copied or deleted merely because they are scanned.

Use an immutable internal import key in the managed folder/file identity; display-title changes must not rename the source identity. Exact layout and metadata mechanism are resolved against LocalSource in P01-02. Existing manually arranged paths continue to work without a mass migration.

Content fingerprints identify exact bytes and help detect duplicates/replaced editions. Do not merge books merely because title, author or ISBN matches. A replacement edition with different bytes needs a deliberate mapping decision.

If the configured storage tree is unavailable or permission is revoked, retain metadata/progress and expose Locate file / Restore access behavior. No lost-file condition should reset the book to unread.

## 5. Persistent data and progress rules

Retain the existing database and shared title/item tables. Add focused EPUB state linked by foreign keys, rather than a second library database.

Proposed logical records; final SQL names and indexes are decided in P01-02:

| Record | Minimum responsibility |
| --- | --- |
| Publication asset | Item FK, stable asset key, format/layout, fingerprint, relative storage reference, ownership and availability. |
| EPUB reading state | Item FK, locator schema version, asset fingerprint, resume locator, furthest comparable locator/progression, update time/revision and explicit reset marker. |
| EPUB title preferences | Title FK and explicit book-reader overrides; absence means inherit global defaults. |
| EPUB location bookmark | Bookmark ID, item FK, fingerprint, locator JSON, optional label/context, created/updated times. |

Use current SQLDelight migration conventions and the next available migration identifier discovered at implementation time. Cascade auxiliary records when their parent is deliberately removed; moving/deleting an asset must not accidentally delete reading state.

### Locator contract

Persist the full validated Readium locator as versioned JSON at the adapter boundary. It can contain href, media type, location fragments/progression/position and textual context. Retain a format-version envelope to migrate the representation later.

- A rendered page index, pixel offset or guessed percentage is not the source of truth.
- Do not promise that a locator is always an EPUB CFI; preserve what the selected engine actually supplies.
- A locator is only directly comparable with another locator for the same publication revision/fingerprint.
- Source-provided memo/remote_memo is for remote metadata. Never store local user resume/preferences there.
- Validate locator hrefs against the opened publication and bound JSON/text size before restoring.
- Missing/invalid locators fall back through valid document/location information to the start, with an understandable recovery state.
- If book bytes change, preserve the old state and attempt validated recovery. Do not silently apply a percentage to a different edition and call it exact resume.

### Resume versus furthest progress

Store them separately. If a user moves back from 70% to 35%, Resume should reopen near 35%; statistics can retain 70% as furthest read. Repeated reading and an explicit Reset progress action must work.

Observe locator changes with lifecycle-aware collection and coalesce writes. Serialize writes per item so a delayed write cannot replace a newer one. Persist during reading and flush the latest accepted location on pause/exit, without relying solely on onDestroy.

Load saved state before normal reader initialization. Do not overwrite a restored location with an initial empty/default locator emitted during loading or reflow. Preserve position across font/theme changes, orientation and switching reading modes.

Readium's FragmentFactory must be installed before fragment restoration. Handle process recreation when Publication/Factory objects no longer exist: restore the serializable item/asset reference, safely rebuild the publication and remove/recreate stale fragments using the documented recovery approach. A dummy factory must not result in the user permanently losing their place.

### Completion and shared history

Do not mark a whole book read because it was opened, its final ToC entry was tapped, or a percentage rounded to 100.

Support an explicit Mark finished action. Automatic completion, if implemented, requires a verified user-navigation event at the actual end; test it separately from arbitrary jumps. If a reliable end event is unavailable, keep explicit completion in v1.

Reading time/history use the existing shared services, with one active-session owner to avoid double counting. Reopening a completed book does not silently clear its read flag. Read again / reset actions are explicit.

Current manga trackers must not receive fabricated novel chapter counts or percentages. Novel tracker integration is deferred; gate incompatible tracker behavior for book items.

## 6. Reader behavior and UI

Use Mihon's theme, icons, navigation conventions, secure-window behavior, brightness handling and accessibility patterns. Keep image-specific controls out of the book settings panel.

Required book controls:

- Back, book title, current section and a percentage-based progress display.
- Table of contents with nested entries and current-section indication.
- Previous/next section and supported page/tap/keyboard navigation.
- Font family/size, line height, margins, alignment and publisher-style preference.
- Light/dark/sepia reader themes and the existing compatible brightness/fullscreen behavior.
- Paginated/vertical-scroll mode and reset-to-inherited settings.
- Search, bookmarks and Mark finished.
- A clear error/retry state for missing, inaccessible or corrupt books.

Global book defaults must not modify manga settings. Per-title overrides apply only when explicitly set, and users can clear an override. Apply preferences through supported Readium APIs; isolate any experimental settings API use behind the adapter and pin the version.

Spine order determines reading order; ToC labels do not reorder the book. Internal links and footnote-return navigation stay inside the publication. External links require a deliberate user action; loading a local book must not fetch remote assets automatically.

For Arabic, test paragraph shaping, mixed numerals/Latin text, font fallback, alignment, selection, direction of page navigation and RTL app chrome independently. App locale alone must not force the content's reading direction.

Selection/copy and Android text actions use the platform/engine facilities. Include contextual navigation back from search results and footnotes without destroying Resume. Location bookmarks must survive reflow and be manageable as a list.

Persistent highlights/annotations and TTS are later enhancements and must not block the v1 release.

## 7. Offline storage, validation and backups

Imported EPUB files are durable local content, not temporary cache. Reading should not require network access.

Integrate availability and size information with existing storage/download presentation. Avoid labeling an imported original as a pending network download. Distinguish Remove from library, remove an owned file, and clear derived cache. Existing user-owned Local-source originals are never automatically deleted by download cleanup or delete-after-reading rules.

Caches such as derived covers/search data may be rebuilt; retain resume/bookmarks when clearing them. Recover safely after interrupted import, storage exhaustion and permission loss. Acquire and close file/publication resources with well-defined ownership.

EPUBs are untrusted archive/HTML content. Bound archive entry count/uncompressed sizes and parsing work, prevent package path escape, and restrict resource requests to permitted publication/renderer resources. Verify the chosen engine configuration rather than adding an unrestricted Android JavaScript bridge. Engine scripts needed for rendering are different from publication-authored scripts. Do not log full text, content URIs containing sensitive data or future credentials.

Detect unsupported DRM explicitly. Standard font obfuscation is not by itself a reason to classify an EPUB as unsupported DRM.

### Backup semantics

The normal Mihon backup remains a metadata/state backup; it does not automatically embed entire EPUB files. Extend it with optional, versioned book asset references, reader state, preferences and location bookmarks. Explain that users must also retain/move their EPUB files.

- Allocate new protobuf field numbers after inspecting the actual schema. Never reuse retired numbers or change the meaning of lastPageRead.
- Old backups restore existing manga state without invented EPUB locations.
- New backups restore books/state even when assets are missing; restore media separately and relink.
- Never export device-specific absolute paths or temporary URIs as portable identity.
- Remap restored item IDs using source/item identity; integer row IDs can differ between databases.
- Compare progress only for matching publication fingerprints. Preserve furthest progress and apply a documented policy for current-versus-backup Resume.
- A newer explicit progress reset must not be undone by blindly taking the maximum percentage from an older backup.
- Corrupt optional EPUB state must not make the whole backup unrestorable.
- Do not promise that stock Mihon preserves fork-specific EPUB state when it reads the fork's backup.

P01-07 must demonstrate restoration to a clean install with book files moved separately, plus restoration while files are absent.

## 8. Implementation work packages

Each package should have a focused branch/PR, a completion report with exact tested commands/results and remaining limitations, and updates to the affected planning/contracts. Do not report a package complete while its acceptance criteria remain untested.

| ID | Deliverable | Depends on | Required exit evidence |
| --- | --- | --- | --- |
| P01-01 | Baseline, dependency integration and engine feasibility | None | Baseline app builds; Readium opens EPUB 2/3 fixtures inside Mihon; locator serialization/restore and rotation recovery demonstrated; dependency/lifecycle decisions recorded. |
| P01-02 | Data model, identity and local publication/import services | P01-01 | Upgrade/fresh DB checks; existing Local URLs preserved; atomic multi-file import, duplicate handling and asset ownership verified. |
| P01-03 | Shared-library integration and EPUB reader routing | P01-02 | Imported/scanned books appear in existing library; every relevant Read/Resume entry point routes correctly; image reading remains available. |
| P01-04 | Durable Resume, lifecycle, history and completion | P01-03 | Resume survives reflow/restart/process recreation; backward-reading and reset semantics pass; history/time are counted once. |
| P01-05 | Complete reading controls, navigation and accessibility | P01-04 | Typography, themes, ToC, links/footnotes, search, selection, bookmarks and RTL pass on representative fixtures. |
| P01-06 | Offline availability, storage and recovery | P01-05 | Airplane-mode reading; safe cache cleanup; permission, missing-file, full-storage and interrupted-import recovery; originals preserved. |
| P01-07 | Backup, restore and upgrade compatibility | P01-06 | Old/new backup tests; clean-install relinking with separate book files; malformed optional state handled; reset/merge rules verified. |
| P01-08 | Regression testing, release packaging and user/developer docs | P01-07 | Release checklist passes; reproducible fork APK; no critical/high-impact unresolved defects; documented limitations and phase-close report. |

A package may harden earlier code as needed; it should not add Kavita work opportunistically. Baseline security/resource handling starts with P01-01/P01-02 and is verified fully in P01-06/P01-08.

### First milestone

P01-01 through P01-04 produce the first useful native reading flow: import into the existing library → read EPUB → change font/rotate → exit/restart → resume at the same passage.

### P01-01 execution brief

1. Use the current fork/worktree; inspect applicable AGENTS.md and repository instructions. Record the clean starting SHA and any existing changes.
2. Start from the pinned baseline, or document a deliberate update before editing. Read existing build/CI instructions and establish the supported build/test commands.
3. Confirm a development identity, signing setup and update configuration suitable for the fork. The final public name can remain undecided.
4. Record the proposed content resolver, adapter, storage-reference and locator contracts in the repository.
5. Add only the required pinned Readium Maven modules through the version catalog. Confirm dependency resolution/desugaring rather than upgrading the whole toolchain unnecessarily.
6. Implement a minimal adapter and in-app development route for redistributable EPUB fixtures. It is a temporary diagnostic entry point, not a separate user bookshelf.
7. Demonstrate EPUB 2 and 3 text/illustrations, ToC access, preference-driven reflow and serialization/restoration of a real locator.
8. Exercise rotation, Activity recreation and the documented missing-factory/process-restoration recovery. Persistent production Resume is completed in P01-04; distinguish it from this feasibility demonstration.
9. Smoke-test the existing image reader and record APK size/open-time/memory observations on a named test device. Set realistic performance budgets for later packages.
10. Deliver a focused PR and decision note confirming the exact engine version/host arrangement, tests performed and blockers. If the dependency cannot work on supported devices, resolve that decision before P01-02.

A build-only result or a standalone Readium sample app does not satisfy P01-01.

## 9. Verification matrix

Use small authored/public-domain fixtures with documented redistribution rights. Do not commit the user's private novels or server details.

| Scenario | Expected result |
| --- | --- |
| EPUB 2 NCX and EPUB 3 NAV | Correct spine order, usable ToC, text and illustrations. |
| Long section, nested ToC, footnote and embedded font | Reflow/navigation work; font fallback remains readable. |
| Arabic/RTL and mixed Arabic-English | Correct shaping, content direction, selection and navigation. |
| Existing image-only/fixed-layout EPUB | Existing image route works; overrides behave predictably. |
| CBZ/CBR, image folders, webtoon and installed image source | Current reading/download/history behavior is preserved. |
| Font change, rotation, split screen and scrolling/pagination switch | Resume remains at the same passage or nearest valid location in that paragraph; no unexplained reset. |
| Background/kill/restart and Android process recreation | No FragmentFactory crash; durable state restores. |
| Read 70%, return to 35%, exit | Resume near 35%; furthest stays 70%. |
| Tap last ToC entry, then leave | No false completion. |
| Mark finished / reset / reread | Explicit user intent wins consistently. |
| Import duplicate, same title different edition, malformed/truncated EPUB | No accidental merge/overwrite; clear per-file result and partial cleanup. |
| Storage tree unavailable / original picker file moved | Missing storage is recoverable; managed import survives moving its external original. |
| Airplane mode and cache cleanup | Durable books still open; bookmarks and Resume remain. |
| Old backup, new backup, clean install, missing media | Existing manga restore; book state restores/relinks correctly. |
| Updated EPUB bytes | Version mismatch handled; prior state preserved for recovery. |
| Large publication and repeated open/close | Measured performance within agreed budgets; no growing resource leak. |
| External URL/script/archive-path fixtures | No automatic external fetch or publication escape. |
| Keyboard/TalkBack and tablet layouts | Usable labelled controls, readable contrast and expected navigation. |

Unit tests should cover independent behavior such as locator validation, import identity/deduplication, completion/reset policy and restore merging. Integration tests should cover migrations, the content resolver and backup round trips. Device/instrumented checks cover rendering, lifecycle, reflow and file permissions. Follow existing CI conventions; do not add snapshot tests that only restate implementation.

## 10. Primary code locations to inspect/change

Paths are verified against the Mihon baseline; proposed new classes belong near the corresponding responsibility.

| Area | Existing paths |
| --- | --- |
| EPUB image extraction | core/archive/src/main/kotlin/mihon/core/archive/EpubReader.kt |
| Local scan and metadata | source-local/src/main/kotlin/tachiyomi/source/local/LocalSource.kt; metadata/EpubReaderExtensions.kt |
| Local formats/storage | source-local/src/main/kotlin/tachiyomi/source/local/io/Format.kt; LocalSourceFileSystem.kt |
| Current reader/loaders | app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt; ReaderViewModel.kt; loader/ChapterLoader.kt; loader/EpubPageLoader.kt |
| Existing theme/Activity foundation | app/src/main/java/eu/kanade/tachiyomi/ui/base/activity/BaseActivity.kt |
| Reader defaults | app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt |
| Domain identity | domain/src/main/java/tachiyomi/domain/manga/model/Manga.kt; chapter/model/Chapter.kt |
| Database | data/src/main/sqldelight/tachiyomi/data/manga.sq; chapter.sq; migrations/ |
| Download/storage presentation | app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt; DownloadProvider.kt; DownloadCache.kt; Downloader.kt |
| Backup/restore | app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupChapter.kt; BackupManga.kt; create/; restore/ |
| Dependency/build config | gradle/libs.versions.toml; app/build.gradle.kts; settings.gradle.kts |

Do not replace the current image EPUB extractor wholesale before testing existing comic workflows. Keep Readium types out of broadly used image-source interfaces and database domain models.

### Repository documentation to create during implementation

- docs/epub/README.md — architecture, setup and entry points.
- docs/epub/DECISIONS.md — engine/version, host, identity and scope decisions.
- docs/epub/CONTRACTS.md — content, asset, locator, preference and error contracts.
- docs/epub/TEST_MATRIX.md — fixtures, test environment and measured results.
- docs/epub/WORK_PACKAGES.md — package status, linked PRs and unresolved issues.
- docs/epub/USER_GUIDE.md — import/read/settings/offline/backup/relink instructions.
- docs/epub/PHASE_1_CLOSEOUT.md — completed criteria and handover to Kavita work.

These are planned repository files. This planning task has not created a fork or committed them.

## 11. Release gate before Kavita work

Part 1 is complete only when:

1. A DRM-free EPUB can be imported, found and read in the existing library with no separate bookshelf.
2. Text, illustrations, ToC, footnotes, controls, search and location bookmarks work.
3. Resume survives font changes, rotation and app/process restarts.
4. Global/per-title book settings stay separate from manga settings.
5. Read/unread, history, rereading and progress reset behave consistently.
6. EPUBs read offline, and storage cleanup does not remove user originals or state.
7. Metadata backup plus separate-media restoration/relinking works.
8. Existing manga/comic readers and image extensions pass the defined regression checks.
9. The fork builds reproducibly and its signing/update identity is correct.
10. Documents list tested platforms, limitations and all unresolved issues; no critical or high-impact release blocker remains.

Only then design the Kavita extension capability against the implemented publication contract. Kavita browsing/authentication, EPUB retrieval and server-progress mapping remain a separate workstream.

## 12. Evidence, confidence and open implementation decisions

This plan is based on source and documentation inspection. No Android build, device rendering benchmark or live Kavita test was performed for this planning task. API compatibility, reader lifecycle behavior and performance are validation gates, not claimed test results.

User requirements fixed here: native in-app reading, one library, EPUB first, Kavita later. Design recommendations: Readium 3.4.0, established Fragment navigator, managed import copies and versioned locator state. Choices to settle in P01-01/P01-02: final app identity and signing/update configuration, resolved dependency compatibility, adapter module/host placement, managed-folder layout and SQL/backup field allocation.

### Primary references

- Mihon v0.21.2: https://github.com/mihonapp/mihon/releases/tag/v0.21.2
- Mihon pinned source: https://github.com/mihonapp/mihon/tree/446f6df82c390ab760802c2fce3b677bce3f31c1
- Existing EPUB extractor: https://github.com/mihonapp/mihon/blob/v0.21.2/core/archive/src/main/kotlin/mihon/core/archive/EpubReader.kt
- Local source: https://github.com/mihonapp/mihon/blob/v0.21.2/source-local/src/main/kotlin/tachiyomi/source/local/LocalSource.kt
- Readium release: https://github.com/readium/kotlin-toolkit/releases/tag/3.4.0
- Versioned Readium documentation: https://readium.org/kotlin-toolkit/3.4.0/
- Readium integration overview: https://github.com/readium/kotlin-toolkit/blob/3.4.0/docs/guides/getting-started.md
- Navigator and lifecycle/locator guidance: https://github.com/readium/kotlin-toolkit/blob/3.4.0/docs/guides/navigator/navigator.md
- Navigator feature parity/experimental Compose status: https://github.com/readium/kotlin-toolkit/blob/3.4.0/docs/guides/navigator/epub-feature-parity.md
- Preferences integration: https://github.com/readium/kotlin-toolkit/blob/3.4.0/docs/guides/navigator/preferences.md

# P01-01 decisions

## Engine and placement

Readium Kotlin is pinned to `3.4.0`. Only `readium-shared`, `readium-streamer`, and `readium-navigator` are integrated. OPDS, LCP, PDF, audio, TTS, and experimental Compose navigators remain excluded. The feasibility host stays in `:app` under `eu.kanade.tachiyomi.ui.reader.epub`; its Activity/Fragment and existing Mihon theming/security dependencies do not justify a module boundary.

The published `readium-navigator` AAR embeds PhotoView 2.3.0. Its `PhotoView.class` is byte-for-byte identical to Mihon's former direct PhotoView 2.3.0 artifact, so the duplicate direct dependency is omitted to avoid duplicate classes while preserving the existing image-reader implementation.

## Identity and updates

The temporary application ID is `io.github.mohamedmaged9258.mihon.epub`; debug remains `.dev`. Namespace stays `eu.kanade.tachiyomi`, and the module overrides the label to `Mihon EPUB Dev`. Manifest authorities based on `${applicationId}` therefore remain distinct from official `app.mihon`.

The updater is disabled for every variant in fork build logic. The retained repository constant is `MohamedMaged9258/mihon`, update and release-notes UI are hidden while updating is disabled, and fork CI explicitly passes `-Penable-updater=false -Pinclude-telemetry=false`. Upstream release workflow guards were not changed.

On-device verification installed the clean-baseline `app.mihon`, fork release, and fork debug variants concurrently and confirmed distinct derived provider authorities. A temporary deny-all host proxy observed cold launches of both updater-disabled fork variants without receiving any upstream release request; emulator proxy configuration was restored after the observation.

Tracker OAuth schemes remain unchanged. Values such as `mihon` and the Comick `app.mihon` callback are provider-registered compatibility values, not this APK's package identity; redesigning provider registrations is outside P01-01.

## Fragment and process recovery

The parent Fragment installs a Readium factory before `super.onCreate()`. On cold process restoration it may install Readium's dummy factory solely to let Android reconstruct state; the restored dummy child is removed explicitly before a reader is attached. A child navigator is reused only when it is the instance created by the active session's current binding, and a replaced publication is closed only after its navigator is destroyed. The ViewModel reopens from the serializable fixture reference, validates the checkpoint against the new publication/fingerprint, installs the real factory, and attaches a fresh navigator. No publication, factory, Fragment, file, or stream crosses an Intent or saved-state boundary.

Checkpoint writes reopen only after verified rendering (revised 2026-10-16). `currentLocator` starts at `initialLocator`, so StateFlow emits nothing when the loaded locator equals it, and immediately after `go()` its value can still describe the pre-reflow page. Neither is used as an acknowledgement. Each navigator binding owns its own Readium listeners; `onPageLoaded`/`onPageChanged` from the current binding end `Rendering`, and a restore completes only after such a callback follows the request, a 500 ms quiet window passes, and an `evaluateJavascript` check confirms that the anchored element (the CSS selector of Readium's `firstVisibleElementLocator()`) intersects the viewport. The quiet window was added after API 26 (WebView 69) showed a late relayout following rotation.

The authored nested ToC keeps an encoded fragment but does not attach a synthetic query to a queryless spine resource. Readium 3.4.0's established Fragment navigator preserves the query and then compares it with the full reading-order URL, causing that artificial link to report success without changing resources. Full query/fragment preservation remains covered at the locator-codec boundary.

## Network and untrusted content

Local fixtures use an EPUB-only parser with a deny-all Readium `HttpClient`. Attempt counts are observable; book text, locator context, and requested URIs are not logged. No custom JavaScript interface and no unrestricted served-assets pattern are installed.

Readium supplies format sniffing, ZIP/container access, package parsing, manifest link resolution, and navigator resource serving. Mihon additionally enforces private streaming copy, SHA-256 binding, a 64 KiB locator-envelope limit, target/version/fingerprint validation, publication-resource membership, and fail-closed external HTTP. Independent archive entry-count/uncompressed-size limits, deeper hostile-path tests, and the production asset policy remain P01-06 work; durable asset ownership/import remains P01-02.

# P01-02 decisions

## Placement and boundary

| Layer | Location | Contents |
| --- | --- | --- |
| Domain | `domain/.../tachiyomi/domain/epub/` | Engine-free models with stable integer codes; repository interfaces (`PublicationAssetRepository`, `EpubImportJournal`, `EpubReadingStateRepository`, `EpubTitlePreferencesRepository`, `EpubBookmarkRepository`) |
| Data | `data/.../tachiyomi/data/epub/`, `epub_*.sq`, `migrations/20.sqm` | SQLDelight schema and repositories, bound with Metro `@ContributesBinding` like the existing repositories |
| App | `app/.../eu/kanade/tachiyomi/data/epub/` | `EpubArchiveGuard`, `LocalPublicationInspector`, `LocalPublicationOpener`, `PublicationAssetResolver`, `LocalEpubRegistrar`, `PublicationStorage`, `EpubImportService`, debug `EpubImportDiagnosticsActivity` |
| Local source | `LocalSource`, `SyncChaptersWithSource` | Only the adaptations listed below |

Readium `Publication`, `Asset`, `Locator` and the navigator factory stay inside the app's EPUB packages. The P01-01 fixture opener and the production inspector/opener share one parse core, `parseEpub`. It runs the EPUB parser only, installs no DRM support and uses an HTTP client supplied by the caller. The fixture reader, its identity type and its tests are otherwise unchanged. Domain and database contracts carry only typed values, fingerprints and an opaque, versioned locator string (`StoredLocator`).

## Schema

`20.sqm` is purely additive (schema version 20 → 21). It adds five tables and changes no existing table, column or view. Enumerations are stored as explicit integer codes with `CHECK` constraints, so a later migration can add codes without reinterpreting rows; see [`CONTRACTS.md`](CONTRACTS.md).

- **`epub_asset`.** `chapter_id UNIQUE` gives one active publication per readable item, with `ON DELETE CASCADE`.
  - Other columns: `asset_key UNIQUE`; payload format, layout and content kind as separate columns; a SHA-256 `fingerprint`; `byte_size`; `storage_root` + `relative_path`; ownership; availability.
  - A partial unique index on `fingerprint WHERE ownership = 1` is the duplicate boundary for managed copies.
- **`epub_import_operation`.** This is the durable import journal.
  - A partial unique index on `fingerprint WHERE phase IN (1, 2, 3)` allows one unfinished import per byte set. A pending rollback (`ABORTING`, 6) is outside it: it owns only its own key folder, so it never blocks importing the same bytes again.
  - Title and item references use `ON DELETE SET NULL`. A finished record therefore survives a deliberate removal, and recovery can tell that the title was removed.
- **`epub_reading_state`.** The primary key is `(chapter_id, fingerprint)`.
  - Changed bytes start a new row. An older fingerprint's state is kept, never reinterpreted.
  - Resume and furthest locators are separate columns.
  - `revision` guards against delayed writes (`WHERE excluded.revision > revision`), and `reset_at` records an explicit reset for later merge rules.
  - The foreign key goes to `chapter`, not to the asset, so removing the asset row or losing the file keeps reading state.
- **`epub_title_preferences`.** One row per title, with nullable typed overrides. `set()` writes every column, so `NULL` clears one override, and `clear()` deletes the row.
- **`epub_location_bookmark`.** A stable UUID key, item + fingerprint, a versioned locator, and an optional bounded label and context.

`user_last_page_read`, reader/chapter flags, chapter bookmarks and the memo fields are untouched. Nothing EPUB-specific is stored in `remote_memo`, genres, scanlator, notes or image URLs.

## Managed layout and identity

```text
<configured root>/local/<key>/book.epub
                            /ComicInfo.xml   (title, authors, description; generated)
                            /cover.jpg       (declared cover, re-encoded; derived)
                            /.nomedia
```

- `<key>` is `epub-` plus 26 random base32 characters (130 bits), and it never changes.
  - It is the title URL (`source 0`, `remote_url = key`), so display metadata never renames identity and user text never becomes a path component.
  - The item URL is `<key>/book.epub`.
- One standalone EPUB is one Local title with one item. Spine sections stay publication navigation.
- The item is named with the localized label `local_epub_book` ("Book"), not the title.
  - `SyncChaptersWithSource` strips the title from item names (`ChapterSanitizer`), which would turn an item named after its title into an empty name.
  - This already happens to legacy Local EPUBs whose `dc:title` equals the folder name.
- Staging is `noBackupFilesDir/epub-import/` and reader working copies are `cacheDir/epub-working/`. Both are disposable and outside the Local tree.
- No JSON file is ever written into a title folder, so the legacy JSON migration path cannot pick up import data. The journal lives in the database.

## Ownership

| Class | Where | Deleted by Mihon |
| --- | --- | --- |
| Managed original | `local/<key>/book.epub` under the root recorded on the asset | Only by an import's own rollback before its database commit (never a reused operation's verified book). Never by closing a session, cascades or sync; deleting an owned file is P01-06 |
| User-owned Local file | Any existing Local title folder | Never: registration only reads it |
| Staging / working copy | App-private | Yes, on completion, failure, cancellation, close or recovery |
| Derived cover, `ComicInfo.xml` | Managed key folder | Rewritten by a re-registration; rebuildable |

Database cascades never touch files. A session's `close()` releases the publication and deletes only its own working copy.

## Storage root binding

The asset stores the exact `baseStorageDirectory` value it was committed under. `PublicationStorage.locate()` resolves `relative_path` only against that root. If the configured root differs, the result is `ROOT_CHANGED`; it never silently resolves against the new root. Relinking is P01-06.

An import pins the root string and its `local` directory at the start:

- A change detected before the file commit aborts the import and cleans up on the pinned root (`StorageChanged`).
- After the file commit, the import completes on the original root, which the asset records.

## Duplicate scope

Identity is the SHA-256 of the actual bytes. Title, author, ISBN, filename and cover never merge publications.

- **Managed copies.** At most one per fingerprint, enforced by the partial unique index.
  - Imports of identical bytes are serialized per fingerprint in the process, and the journal index rejects a second unfinished claim regardless.
  - Re-importing returns `Duplicate` with the existing ids.
- **User-owned files.** Identical bytes in user-arranged folders are legal, and each is registered.
  - A managed import of bytes that also exist as user-owned files still makes its durable managed copy and reports `userOwnedCopies`.
  - It never substitutes a user file for the managed-copy guarantee.
- **Removed titles.** If a finished import's title was deliberately removed but its verified file is still in place, importing the same bytes reopens the journal record under the same key (`reused`). This avoids leaving an orphan copy.

## Import journal and recovery

```text
CLAIMED ──file written, verified──▶ FILE_COMMITTED ──DB transaction──▶ DATABASE_COMMITTED ──cleanup──▶ FINALIZED
   │                                       │
   └──failure/cancel/storage change──▶ ABORTING ◀──DB commit failure or book no longer verifies
                                          │  ▲
                     own files confirmed  │  └── cleanup failed or storage cannot be inspected (retried by recovery)
                     removed              ▼
                                       ABORTED
```

A rollback is recorded (`ABORTING`) before any file is deleted and completes (`ABORTED`) only once the operation's own files are confirmed absent. Every delete is judged by a fresh listing afterwards, whether it threw, returned `false` or claimed success. Providers report a failed listing as an empty one, so a listing counts only if its folder still answers `exists()` afterwards: storage that is unmounted, revoked or failing is "cannot inspect", never "absent". Until then the key folder stays hidden and recovery retries.

1. The source is streamed into private staging while being hashed. The size limit applies to bytes actually read, and unknown or non-seekable sizes work. Every later read of stored bytes is bounded too (see [byte limits](#byte-limits)).
2. `EpubArchiveGuard` runs, then Readium inspection with deny-all HTTP.
3. Under the fingerprint lock, the duplicate check runs and then the claim.
4. The file commit runs on the pinned root:
   - `ComicInfo.xml`, the cover and `.nomedia` are written first.
   - The book is written as `.book.epub.part`, a dot file that the Local source ignores.
   - Where the provider supports it, `renameTo("book.epub")` moves it into place. Otherwise the bytes are written again under the final name and the part file is deleted.
   - The final file is always re-read and must match the fingerprint and size before `FILE_COMMITTED` is recorded.
5. The database transaction gets or inserts the title and item through the existing `insertNetworkManga` and `chapter` queries.
   - Existing favorite, categories, flags and timestamps are kept. The item is not marked read and no history is written.
   - It then inserts the asset and records `DATABASE_COMMITTED`.
6. Finalize: staging is deleted, then `FINALIZED`.

**Visibility.** The Local source hides two kinds of folders:

- key folders of operations in `CLAIMED`, `FILE_COMMITTED` or `ABORTING`;
- managed-pattern folders without `book.epub`.

The database commit is therefore the point where an import becomes discoverable, even on providers without atomic rename.

**Cancellation.**

- Before the file commit, cancellation rolls back. This applies both to the cancel flag, which reports `Canceled`, and to coroutine cancellation.
- From the file commit on, the import completes under `NonCancellable` and reports `Imported`.
- A canceled batch has final results for every input it emitted. Unemitted inputs are unfinished and never visible.
- Retrying returns `Duplicate` for the inputs that were committed.

**Recovery** (`EpubImportService.recover()`) is idempotent. It runs before every batch and from the debug screen. For each operation of its journal snapshot it takes that fingerprint's import lock (the one imports hold from claim to finalize) with `tryLock`; an operation whose lock is held belongs to a running import and is skipped. With the lock held it re-reads the operation and acts only on its current phase, so an import that finished after the snapshot is never rolled back:

| Phase found | Action |
| --- | --- |
| `CLAIMED`, `ABORTING` | Roll back: record `ABORTING`, delete only this operation's named files in its key folder, delete the folder if it is then empty, and record `ABORTED` once they are confirmed absent. The files are `.book.epub.part`, plus `book.epub`, `ComicInfo.xml`, `cover.jpg` and `.nomedia` for a non-reused operation. Unconfirmed cleanup stays `ABORTING` |
| `FILE_COMMITTED` | Re-hash `book.epub`, reading at most its recorded size (one byte more means it changed). If it matches, inspect it again, complete the database commit and finalize; otherwise roll back as above. Unreachable storage is left for a later pass |
| `DATABASE_COMMITTED` | Finalize. A title removed in the meantime (`manga_id` null) is not recreated |
| `FINALIZED`, `ABORTED` (finished since the snapshot) | Nothing |
| Orphan staging files | Deleted |

`EpubRecoveryReport` counts operations aborted, completed and finalized; `failed` counts those left pending for a later pass (unconfirmed cleanup, unreachable storage, or an error). Recovery never touches anything outside a journal key folder. Slow file and parser work never runs inside a database transaction.

### Byte limits

Every read of an external or stored file streams through `PublicationStorage.copyHashing(input, output, maxBytes)`, which has no default limit. It requests at most one byte past the limit, writes nothing past it, and fails with `TooLarge` as soon as it is passed, so an endless or unknown-length stream is cut off without waiting for EOF. Provider-reported sizes are never consulted.

| Read | Limit | Over the limit |
| --- | --- | --- |
| Import source | `maxInputBytes` | `Unsupported(TooLarge)` |
| Registration of a Local file | `maxInputBytes` | `Failed(TooLarge)`; nothing recorded |
| Opening a registered asset | its recorded `byte_size` | `FingerprintMismatch` (a longer file is a replacement) |
| Recovery of a committed book; reuse check; post-write verification | the operation's recorded byte size | Treated as not matching |

Temporary copies are deleted on every path, and originals and reading state are never touched.

## Content safety baseline

| Limit | Value | Enforced on |
| --- | --- | --- |
| Input size | 512 MiB | Bytes actually read from the provider |
| Archive entries | 10 000 | Central directory |
| Single entry | 64 MiB | Actual decompressed bytes |
| Total uncompressed | 1 GiB | Actual decompressed bytes |
| Compression ratio | Over 200:1 rejected for entries over 1 MiB | Per entry |
| Title / other fields / description / authors | 1 KiB / 512 chars / 16 KiB / 32 | Whitespace-collapsed, never splitting a surrogate pair |
| Declared cover | 10 MiB source, at most 10 000 px per side, sampled to at most 1024 px, JPEG | Bounds decoded first |
| Image-content sampling | First 10 reading-order documents, 64 KiB each | Readium resources |

Other rules:

- **Entry names.** Names with `..` or `.` segments, a leading `/`, a backslash, a drive prefix or NUL are rejected. Newer Android versions also reject some of these at the `ZipFile` level. Nothing is extracted to the filesystem.
- **Container.** A missing `META-INF/container.xml` is malformed, and a declared `mimetype` other than `application/epub+zip` is unsupported.
- **Protection.** Any of these means `UnsupportedProtection`: an `encryption.xml` algorithm other than IDPF or Adobe font obfuscation, a `rights.xml`, or a `license.lcpl`. Readium's `isRestricted` is checked as well.
- **Network.** `DenyAllHttpClient` refuses every Readium HTTP request and only counts attempts.
- **Logs.** They contain only keys, fingerprint prefixes and exception class names, never URIs, text or locator context.

## Metadata, covers and classification

- **Title.** Declared `dc:title`, then the picked document's display name without its extension, then `Untitled EPUB`.
- **Other fields.**
  - Authors are the declared creators in order; an empty list means no author.
  - The description is the declared description as plain text.
  - Language, first publisher, identifier and publication date are stored on the asset.
  - Unicode, including Arabic and mixed-direction text, is kept as declared.
- **Not inferred.** Completion and chapter counts are never inferred from metadata.
- **Cover.** Only the declared cover is used: EPUB 3 `cover-image` and EPUB 2 `meta name="cover"`, both of which Readium surfaces as `rel=cover`.
  - A missing or corrupt cover does not fail the import.
  - Without one, the existing Local refresh fallback (first content image) applies.
- **Precedence, managed title.** Only the imported values count: title, author and description in the database, and in the generated `ComicInfo.xml` that Local details read.
  - The legacy OPF helper is skipped for managed items, so it neither overwrites the author or description nor writes the publisher into `scanlator`.
- **Precedence, legacy folders.** These keep `ComicInfo.xml` > legacy JSON > `.noxml`/archive `ComicInfo.xml`. The legacy EPUB helper keeps its scanlator behavior for compatibility.
- **Layout.** Readium's `metadata.layout`, fixed or reflowable.
- **Content kind.** `IMAGE` for fixed layout, or when every sampled reading-order document is a bitmap or an XHTML page with images and no text; otherwise `TEXT`.
- Payload format, layout and content kind are independent columns. Reader selection and overrides are P01-03.

## Local source adaptations

- **Browse and search.** One indexed query (`localTitleIndex`) maps registered folders to titles.
  - Managed folders display, match and sort by their metadata title, and their URL stays the key.
  - Unfinished imports are hidden.
- **Managed items.** `getChapterList` skips the legacy EPUB helper and names the item `local_epub_book`.
- **Unreachable storage.** If the title has registered publications, `getChapterList` throws `LocalStorageUnavailableException` when the storage tree cannot be reached. It no longer returns an empty list that sync would treat as authoritative.
- **Unregistered folders** keep their previous behavior.

## Missing-file protection

`SyncChaptersWithSource` (Local source only) no longer removes items that have a registered asset when the scan does not find them:

- Their availability becomes `MISSING`, and returns to `AVAILABLE` when the file reappears.
- Read, bookmark and last-page flags, reading state and bookmarks are untouched.
- The resolver records `INACCESSIBLE` or `ROOT_CHANGED` when it observes them.

Deliberate removal stays explicit. Deleting the title or item row cascades, and `PublicationAssetRepository.unregister()` drops only the association. Non-Local sources and unregistered items keep the old semantics.

## Permissions

Picker URIs are used only while importing; no persistable permission is taken. The managed copy is independent of the original document, which may then be moved or deleted.

## Debug route and test infrastructure

**More → Settings → Advanced → Debug info → EPUB import diagnostics** opens `EpubImportDiagnosticsActivity`.

- **Access.** It is non-exported, and `enabled=@bool/epub_import_diagnostics_enabled` is false by default and true only through the debug `resValue`. It returns immediately outside `BuildConfig.DEBUG`.
- **What it does.**
  - Imports picked documents through the production service and shows per-file outcomes and ids.
  - Lists assets with ownership, availability, root match and metadata.
  - Resolves and opens assets with the production opener.
  - Registers the Local EPUBs of library titles, and runs recovery.

`NoRenameDocumentsProvider` exists only in the debug source set. It is a real `DocumentsProvider` over app-private files, without rename support, with fault switches: write failure after N bytes, full volume and revoked access. Instrumented tests use it both as a document source and as a SAF library root.

Two platform behaviors shaped the tests:

- **Own provider not listed.** Under instrumentation, the platform does not list the app's own document provider in `queryIntentContentProviders`, even after the package leaves the stopped state. UniFile uses that list to recognize SAF trees. A test-only UniFile `UriHandler` (`TestProviderTreeHandler`) recognizes that one authority, and every operation still goes through the provider.
- **No ENOSPC across a pipe.** The writing side of a provider pipe sees only the pipe closing, not ENOSPC.
  - A full remote volume therefore surfaces as `CommitFailed`, after the fingerprint check of the final file fails.
  - `InsufficientSpace` is reported when the platform error names ENOSPC, as on local volumes.
  - SELinux denies apps access to `/dev/full`, so a full local volume is covered only at the classification level.

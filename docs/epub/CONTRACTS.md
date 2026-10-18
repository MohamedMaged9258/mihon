# EPUB contracts

## P01-01 concrete target

`EpubFixtureReference(id, assetPath, expectedSha256)` is the only launch target. The asset is debug-only, copied to app-private cache while hashing, and owned by one closeable session. Availability errors are typed; fixture bytes are never put in an Intent or saved state.

`EpubPublicationOpener.open()` creates its private directory and temporary file inside its error handling (an I/O failure is a typed `ResourceUnavailable`). Until `open()` has actually returned to its caller it keeps cleanup ownership: `handOff()` closes a produced session if cancellation interrupts the return dispatch, and partial acquisitions are closed in the opener's `finally`. The ViewModel adopts a returned session with no suspension point in between.

`EpubPublicationSession` is the sole owner of the private file, Readium asset, publication, and navigator factory. `close()` is idempotent and releases them in reverse acquisition order, deleting the private file. `EpubOpenError` distinguishes missing, corrupt, unsupported format, protected publication, fingerprint mismatch, resource unavailable, and restoration failed.

`EpubReaderPreferences(fontSizeOverride)` uses `null` to inherit the diagnostic default. Persistence is intentionally temporary.

`EpubLocatorEnvelope(version, targetId, publicationFingerprint, locatorJson)` has schema version `1`. `locatorJson` is the complete Readium JSON string, not a reconstructed subset. Decoding rejects inputs over 64 KiB before JSON parsing, wrong versions/targets/fingerprints, invalid locator structure, and resources absent from the opened publication. Encoded paths, query, and fragments are parsed by Readium's URL model. Recovery returns a typed reason and starts the publication; it never treats a percentage or page number as an exact location.

### Ownership

Every callback carries an `EpubOwner(sessionId, bindingId)`. A session id is assigned per successful open; a binding id per navigator attachment. Each binding creates its own Readium fragment factory and listeners, records the navigator instance it instantiated, owns exactly one locator collector and one input listener, and stops forwarding once detached. Restoration and reflow are operations with their own ids. `EpubCheckpointGate` rejects locator, pagination, resource-error, attachment, and operation-completion events from any owner or operation that is not current; an obsolete resource error cannot change the current state.

### Threading

Binding, gate, capture, writer-submission and session state are owned by the main thread. `EpubReaderViewModel.onNavigatorEvent()` asserts it (`check(Looper.getMainLooper().isCurrentThread)`). `Binding.emit()` is the single boundary:

- Pagination and locator callbacks arrive on the main thread and are delivered inline.
- Other callbacks are posted to the main looper and the caller returns at once. Readium 3.4.0 can call `onResourceLoadFailed()` from WebView resource interception on a worker thread, and that thread never waits for UI processing.

The posted event keeps the identity of the binding that produced it. At delivery on the main thread, `detached` is checked first, then `gate.isCurrent(owner)`. An error queued before a rebinding or session replacement is therefore dropped and changes neither state nor checkpoint. No coroutine scope is involved: a queued runnable for a cleared reader finds a closed gate. A current-owner error enters `Error(ResourceUnavailable)` through `gate.fail()`. That bumps the revision and cancels the pending operation and capture, so a capture or reflow already in flight cannot accept or complete afterwards, and persistence stays closed.

The host fragment reuses a child navigator only when it is the instance created by the active session's current binding. Dummy (process-restored) and obsolete navigators are removed explicitly before a replacement is created. The Activity compares the active session id with the bound one on every collected state, so conflated states and stopped collection do not matter.

Teardown order: close the gate → detach the binding (callbacks, collector and input listener stop) → remove/destroy the navigator → close the publication. A replaced session is closed only after the host reports that its navigator was destroyed; `onCleared()` closes remaining sessions after the current destroy dispatch.

### Phases and persistence

`Opening → Attaching → Rendering → Restoring → Ready`, with `Reflowing` from `Ready` and `Closing`/`Error` as closed states. `Rendering` ends only with a Readium page-load/page-change callback from the current binding. Restoration navigates to the saved locator and completes only after a subsequent Readium location callback for that binding *and* a DOM check that the anchored element intersects the viewport. A locator without an element anchor (Readium's own report, as in a Back or Reopen target whose capture never completed) must instead be within 1.5 viewports of its progression (the viewport's fraction of the resource is measured in the WebView), so a navigator still at the resource start cannot pass as restored; otherwise `go()` is retried and, after three attempts, the restoration fails. This also covers a final locator equal to the initial one. Checkpoints are accepted only in `Ready`; transitional emissions and manual checkpoints in other phases are ignored. A failed restoration becomes `Error(RestorationFailed)` and keeps the last valid checkpoint.

Accepted locators are enriched with the first visible element's CSS selector and text from Readium, so a passage inside a long resource is restorable after reflow. The full Readium JSON is preserved.

Accepted envelopes update `SavedStateHandle` immediately. `EpubCheckpointWriter` is a single actor coroutine that performs every diagnostic write inline: at most one physical write at a time, pending values conflated to the newest, a 300 ms coalescing delay, and `flush()` returning `Success` only after the newest value accepted before the call is stored (`Failure` otherwise; the value is retried on the next save or flush). Writes go to a temporary file followed by an atomic replace, so a failed write keeps the previous file.

### Same-binding capture ordering

Capturing a position is asynchronous: the element anchor comes from a WebView script whose Readium call resumes through `suspendCoroutine`, so a cancelled capture can still resume later. Cancelling the previous job is therefore only an optimisation. Every capture, whether automatic (a locator change) or manual (Checkpoint through `checkpointNow()`, Back, Reopen), goes through `EpubPositionCapture` and holds a token from `EpubCheckpointGate.beginCapture()`.

The gate `revision` is bumped by every new capture and every transition: opening, session, attach/rebind, operation begin and completion, failure, and close. After the lookup, a result changes `lastAccepted`, `SavedStateHandle` or the file only when all of these hold:

1. the coroutine is still active;
2. `acceptCapture(owner, token)` holds, meaning the owner is current, the phase is Ready, and no newer capture, operation or rebinding happened;
3. the navigator still reports the location that the capture started from.

Every capture ends with an explicit outcome: `Accepted`, `Superseded`, `Canceled` (cancelled, or invalidated by an operation, rebinding, failure or close), `Unavailable` (no navigator, a transitional location, or the navigator moved) or `Failed` (the lookup threw). A superseded or cancelled capture reports its outcome at once, without waiting for a WebView callback that may resume later or never; a late resume cannot change that outcome or be accepted. `checkpointNow()` requests a capture, waits for the outcome of the newest capture (including one that superseded it), and flushes only if it was `Accepted`. Any other outcome is a flush failure: flushing an older value would report a position as saved that was not captured. When no capture can be requested (not Ready), it flushes the accepted checkpoint. Adapter wrappers around suspending Readium calls rethrow `CancellationException`; other lookup failures are logged and treated as a miss.

### Exit and flush guarantees

System Back and the header Back button both call `EpubReaderViewModel.exit()`, which runs in `viewModelScope`. That scope outlives configuration changes and is cleared only after the Activity finishes. `exit()` captures and flushes with a 5 s bound and sets `State.exit`:

- `Saving`: further Back requests are ignored.
- `Done`: the Activity finishes.
- `Failed(detail)`: the reader stays open with a dialog offering Retry, Stay, or Exit without saving.

What Back saves is the **exit target**, the Back-owned value of the ViewModel's single pending target (`pendingTarget`, owned by `pendingFor = Exit` or `Reopen`; see Reopen below): the navigator's location when Back is pressed in Ready, replaced by any newer location the current navigator reports in Ready while saving (Readium's locator can lag a jump inside one resource). Back during opening or restoration (no exit target) flushes the authoritative accepted checkpoint, which is what the reader is restoring. Leaving an unchanged passage captures the held anchor (`Accepted`) and flushes it. Otherwise:

- `Done` is reported only after a capture of the exit target was `Accepted` and its write succeeded.
- **The exit target survives rebinding.** If rotation or recreation replaces the navigator while its capture is pending, that capture is `Canceled` and its late callbacks are never accepted. `newBinding()` restores the exit target (not the older accepted checkpoint) on the replacement navigator, which verifies the rendered location before Ready. The exit then captures again through the new owner and flushes.
- A capture that is `Failed`, `Unavailable` or cancelled without a replacement, a restoration error, or the 5 s bound ends in `Failed`; the previous checkpoint is kept. The exit target is kept for Retry and for rotation behind the dialog, and dropped on Stay or Done.

Exit and Reopen are competing lifecycle actions, with one policy enforced in the ViewModel and mirrored in the UI:

- **Reopen is rejected** unless `State.exit == Idle`, that is while an exit is Saving, Failed (awaiting Retry/Stay) or Done. The footer's Reopen button is disabled in the same states. During opening or restoration with no exit active, Reopen stays available.
- **Back supersedes a pending reopen.** `exit()` cancels the reopen job and takes over the pending target (`pendingFor = Exit`): in Ready it records the displayed location; while a replacement navigator is still restoring a Reopen's target, it keeps that target, so Back then saves the passage the Reopen was saving. If the reopen is still capturing or flushing, it never reaches `open()`, because everything runs on the main thread and the awaits are cancellable. If it already started `open()`, the new session installs and inherits the exit state. `install()` copies `State.exit` instead of resetting it. The exit's flush covers every checkpoint accepted so far, including the restored one (see below).
- **Reopen never rebuilds from an older checkpoint while a newer passage is displayed.** In Ready, Reopen saves the displayed location before `open()` through `saveDisplayedPosition()`, the capture-and-flush loop Back uses: a capture cancelled by a rebinding (rotation, recreation) is retried once the replacement is Ready. If the newest capture (`awaitLatest()` follows supersession) is not `Accepted`, the write fails, or the 5 s bound passes, the current session and binding are kept, the file keeps its previous checkpoint (a captured B may already be the accepted SavedStateHandle value, as with any failed write), the reader stays Ready, and `State.reopenFailed` shows a "Reading position not saved" dialog with Retry (Reopen again) and Stay. When no capture can be requested (opening, restoring, reflowing, or an existing reader error), Reopen is a recovery action: it flushes best effort and reopens from the accepted checkpoint, which `install()` resubmits if the file does not hold it. Back clears the reopen failure and cancels a pending reopen as before.
- **Reopen owns its destination across rebinding.** In Ready, `reopen()` records the displayed location as the pending target (`pendingFor = Reopen`) before anything suspends. `newBinding()` restores the pending target in preference to the accepted checkpoint, so a replacement navigator created by rotation or recreation restores B, not the older accepted A, and reaches Ready only after verifying that B's anchor is in the viewport. Only then can the retried capture satisfy the Reopen; a capture of the replaced binding is `Canceled` and its late callbacks are never accepted, so they can neither save A nor complete the Reopen. If the target cannot be restored (restoration error) or captured, Reopen fails as above instead of succeeding with A. Newer locations reported in Ready while the Reopen is pending replace the target (Readium's locator can lag a jump).
- **Navigation during a pending Reopen is deferred.** From `reopen()` until it opens the new session or shows its failure (`State.reopening`), and while the failure dialog awaits Retry or Stay, `State.canNavigate` is false: user navigation and font changes are ignored, as during an exit. The destination therefore cannot change under the Reopen except through Readium's own lagging report.
- **The pending target is cleaned up with its operation.** Reopen clears it when its save succeeds (before `open()`), keeps it on failure or timeout (a rotation behind the dialog still restores B, and Retry saves it again), and drops it on Stay. Back clears its own on Done or Stay. Cleanup is guarded by the owner (`pendingFor`), so a superseded Reopen can never clear the target Back took over. A repeated Reopen while one is pending re-records the displayed location in Ready, or keeps the existing target while a replacement is restoring it.
- **Exit completion has an identity.** Each `exit()` takes an attempt number. Its result is published only if it is still the newest attempt and the state is still `Saving`, so an obsolete completion cannot finish or fail a later reader state.
- **State survives recreation.** Exit, Reopen and Retry state, and the pending target, live in the ViewModel, which outlives configuration changes. A recreated Activity sees Saving and finishes on Done, or shows the Failed dialog again. Process death during an exit is not covered: the exit state is not saved, and the reader reopens normally.

A failed write keeps the previous file and the last accepted `SavedStateHandle` value. If a capture times out, the exit fails with "Saving timed out"; nothing is presented as saved. Pause and stop only queue a best-effort flush and log a failure. They are not a persistence guarantee; Back and an explicit checkpoint are the paths that report a result.

### Navigation policy during restoration and reflow

User navigation is deferred, that is disabled, unless the phase is Ready, no exit is in progress and no Reopen is saving or awaiting Retry/Stay (`State.canNavigate`). This covers the following inputs:

- The ViewModel's `previous()`, `next()` and `goToToc()` return `false`.
- Compose navigation, ToC-entry and font buttons are disabled.
- A transparent clickable view over the navigator consumes taps and swipes, including `DirectionalNavigationAdapter` taps.
- `dispatchKeyEvent` consumes all keys except Back and volume.

Back, Stay/Retry, the ToC dialog's Close and, while no exit is active, Reopen remain usable. Because no user destination can arise during an operation, a restore or reflow cannot reverse one. Stale operation completions are still rejected by operation id, and stale captures by token.

### Reading viewport

The header and footer take measured space in a vertical layout; they are not overlays. The root applies system-bar and display-cutout insets. The navigator's WebViews therefore occupy exactly the readable area, and the restoration visibility check (`window.innerWidth/innerHeight`) cannot count text hidden behind controls. Titles are limited to one line and status text to two; the footer scrolls horizontally. A production toolbar redesign is deferred.

### Diagnostic close-and-open checkpoint

`EpubDiagnosticCheckpointStore` keeps one file per fixture id under `cacheDir/epub-diagnostic-checkpoints/`. It is a P01-01 diagnostic aid, not the durable production reading-state repository (P01-04). Precedence when a reader instance installs a session: `SavedStateHandle` checkpoint (same task or process restoration) → diagnostic file → publication start. Every candidate is decoded by `EpubLocatorCodec` (size, version, target, fingerprint, resource membership). Missing files start silently; unreadable or invalid ones start with a recovery reason and are replaced by the next accepted checkpoint. A restored checkpoint stays authoritative until a newer capture is accepted. Persistence is tracked separately from location identity:

- Captures still dedup on the accepted locator, so an unchanged visible anchor is not resubmitted.
- On install, the restored envelope is submitted to `EpubCheckpointWriter` unless the diagnostic file already holds exactly that envelope. In that case it is only written to `SavedStateHandle`.

So when `SavedStateHandle` holds a newer B than the file's A (or there is no file), the next flush (Back, Checkpoint, Reopen) returns `Success` only after B is stored. If B's write fails, the flush returns `Failure` and Back offers Retry; nothing succeeds through an empty flush. A start without a valid checkpoint submits nothing, so a default locator is never written. The store remains separate from P01-04 production persistence.

## P01-02 production contracts

These are the services P01-03 builds on. Decisions and policies behind them are in [`DECISIONS.md`](DECISIONS.md#p01-02-decisions). Mihon's title (`manga`) and item (`chapter`) rows remain the authoritative identities. EPUB spine resources are navigation units, not database items.

### Stable codes

| Column | Codes |
| --- | --- |
| `epub_asset.payload_format` | 1 EPUB |
| `epub_asset.layout` | 0 unknown, 1 reflowable, 2 fixed |
| `epub_asset.content_kind` | 0 unknown, 1 text, 2 image-oriented |
| `epub_asset.ownership` | 1 managed copy, 2 user-owned Local file |
| `epub_asset.availability` | 1 available, 2 missing, 3 inaccessible, 4 storage root changed |
| `epub_import_operation.phase` | 1 claimed, 2 file committed, 3 database committed, 4 finalized, 5 aborted, 6 aborting (rollback recorded, files not yet confirmed removed) |
| `*.locator_version` | 1: the complete Readium locator JSON, at most 65 536 characters |
| `epub_title_preferences.reader_mode` | 1 image reader, 2 book reader (NULL: inherit) |
| `epub_title_preferences.text_align` / `theme` | 1 start, 2 justify / 1 light, 2 dark, 3 sepia |

Codes are never reused. Unknown layout and content codes decode to `UNKNOWN`, and an unknown availability code decodes to `INACCESSIBLE`.

### Import

`EpubImportService` (app singleton):

- `import(documents: List<Uri>, cancel: StateFlow<Boolean>): Flow<EpubImportResult>`
  - Runs recovery first, then imports with at most 2 inputs at a time, and emits one result per input as it finishes.
  - `index` is the input position.
- `importAll(...)`: the same, collected and sorted by index.
- `recover(): EpubRecoveryReport(aborted, completed, finalized, failed)`. Each operation is handled under its fingerprint's import lock and re-read there; operations a running import holds are skipped. `failed` counts operations left pending for a later pass. A rollback (`ABORTING`) completes only once the operation's own files are confirmed absent; until then its key folder stays hidden.

`EpubImportResult`:

- `Imported(mangaId, chapterId, assetId, importKey, fingerprint, metadata, reusedKey, userOwnedCopies)`.
- `Duplicate(mangaId, chapterId, assetId, fingerprint, ownership)`: the existing managed import; nothing was copied.
- `Unsupported(error)`: malformed, too large, protected, or not an EPUB.
- `Failed(error)`.
- `Canceled`: canceled before the file commit; nothing is visible.

`EpubImportError`: `PermissionDenied`, `SourceMissing`, `ReadFailed`, `StorageUnavailable`, `StorageChanged`, `InsufficientSpace`, `TooLarge`, `Malformed`, `UnsupportedFormat`, `UnsupportedProtection`, `FingerprintMismatch`, `CommitFailed`, `RecoveryFailed`. `detail` never contains a URI or book text.

Inputs are `content://` documents from the system picker. Display name, MIME type and size are hints only. No persistable permission is taken or needed afterwards.

### Registration of existing Local EPUBs

`LocalEpubRegistrar.register(chapterId)` and `registerAll(mangaId)` return `Registered(asset, existing)` or `Failed(chapterId, error)`.

- They apply to Local `.epub` items only.
- They read the file, at most `maxInputBytes` of it (`TooLarge` beyond), to fingerprint and inspect it, then record a user-owned asset at its existing relative path under the current root.
- They never copy, rename, rewrite or delete the file, and never change the title or item metadata.
- P01-03 decides when to call them (for example on first open).

### Resolution and opening

`PublicationAssetResolver.resolve(chapterId): PublicationResolution` returns one of:

- `NotPublication`: no registered asset, so the existing image path applies unchanged.
- `Epub(asset, file)`: the asset under its recorded root.
- `Unavailable(asset, availability)`: `MISSING`, `INACCESSIBLE` or `ROOT_CHANGED`. Identity and reading state are untouched.

It records the availability it observes.

`LocalPublicationOpener.open(chapterId)` returns `Success(LocalPublicationSession)` or `Failure(error, availability)`.

- It verifies the bytes against the recorded fingerprint first, reading at most the recorded byte size plus one. On a mismatch, including a longer or endless file, it returns `FingerprintMismatch`, and locators of the old revision are never applied to the new bytes.
- A `file://` asset opens in place. A provider-backed asset is copied to a disposable working copy, because the ZIP container needs random access.
- The session exposes `publication`, a lazily created `navigatorFactory` and `usesWorkingCopy`. `close()` is idempotent, releases resources in reverse order and deletes only the working copy.
- Ownership passes through `handOff` as in P01-01: cancellation during the return closes the session.
- HTTP is denied (`DenyAllHttpClient`).

`LocalPublicationInspector.inspect(file, fallbackTitle)` validates a private, complete copy and returns `InspectedPublication`: bounded metadata, layout, content kind, reading-order size and an optional re-encoded cover. It needs no Activity or navigator.

### Repositories (P01-04/P01-05 consumers)

- **`PublicationAssetRepository`.** Get by item, title, fingerprint (managed / user-owned) or all; `insert`; `updateAvailability`; `unregister` (association only); `getLocalTitleIndex`; `getUncommittedImportKeys`.
- **`EpubReadingStateRepository`.** `get(chapterId, fingerprint)`; `getByChapterId` (every revision, newest first); `save(state)`, applied only for a newer `revision`, returning whether it was applied; `reset(chapterId, fingerprint)`, which clears Resume and furthest, records `resetAt` and returns the new revision.
- **`EpubTitlePreferencesRepository`.** `get` returns all-null when nothing is set. `set` replaces every override, so `null` clears it. `clear` removes all of them.
- **`EpubBookmarkRepository`.** `getByChapterId`, `add` (stable UUID key), `updateLabel`, `delete`.

No live reader writes to these yet. Autosave, history and completion are P01-04.

### Ownership and lifetime rules

- Closing a session never deletes a managed original or a user-owned file.
- Deliberately deleting a title or item cascades its asset row, reading state, preferences and bookmarks. Files are never deleted by cascades.
- A missing file, revoked permission or unreachable tree changes availability only. `SyncChaptersWithSource` keeps registered items whose files are not found.
- A relative path is resolved only against the root it was committed under.

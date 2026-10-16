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

`Opening → Attaching → Rendering → Restoring → Ready`, with `Reflowing` from `Ready` and `Closing`/`Error` as closed states. `Rendering` ends only with a Readium page-load/page-change callback from the current binding. Restoration navigates to the saved locator and completes only after a subsequent Readium location callback for that binding *and* a DOM check that the anchored element intersects the viewport; this also covers a final locator equal to the initial one. Checkpoints are accepted only in `Ready`; transitional emissions and manual checkpoints in other phases are ignored. A failed restoration becomes `Error(RestorationFailed)` and keeps the last valid checkpoint.

Accepted locators are enriched with the first visible element's CSS selector and text from Readium, so a passage inside a long resource is restorable after reflow. The full Readium JSON is preserved.

Accepted envelopes update `SavedStateHandle` immediately. `EpubCheckpointWriter` is a single actor coroutine that performs every diagnostic write inline: at most one physical write at a time, pending values conflated to the newest, a 300 ms coalescing delay, and `flush()` returning `Success` only after the newest value accepted before the call is stored (`Failure` otherwise; the value is retried on the next save or flush). Writes go to a temporary file followed by an atomic replace, so a failed write keeps the previous file.

### Same-binding capture ordering

Capturing a position is asynchronous: the element anchor comes from a WebView script whose Readium call resumes through `suspendCoroutine`, so a cancelled capture can still resume later. Cancelling the previous job is therefore only an optimisation. Every capture, whether automatic (a locator change) or manual (Checkpoint, Back, Reopen through `checkpointNow()`), goes through `EpubPositionCapture` and holds a token from `EpubCheckpointGate.beginCapture()`.

The gate `revision` is bumped by every new capture and every transition: opening, session, attach/rebind, operation begin and completion, failure, and close. After the lookup, a result changes `lastAccepted`, `SavedStateHandle` or the file only when all of these hold:

1. the coroutine is still active;
2. `acceptCapture(owner, token)` holds, meaning the owner is current, the phase is Ready, and no newer capture, operation or rebinding happened;
3. the navigator still reports the location that the capture started from.

`checkpointNow()` requests a capture, waits for the newest capture (including one that superseded it), then flushes. Adapter wrappers around suspending Readium calls rethrow `CancellationException`; other lookup failures are logged and treated as a miss.

### Exit and flush guarantees

System Back and the header Back button both call `EpubReaderViewModel.exit()`, which runs in `viewModelScope`. That scope outlives configuration changes and is cleared only after the Activity finishes. `exit()` captures and flushes with a 5 s bound and sets `State.exit`:

- `Saving`: further Back requests are ignored.
- `Done`: the Activity finishes.
- `Failed(detail)`: the reader stays open with a dialog offering Retry, Stay, or Exit without saving.

Exit and Reopen are competing lifecycle actions, with one policy enforced in the ViewModel and mirrored in the UI:

- **Reopen is rejected** unless `State.exit == Idle`, that is while an exit is Saving, Failed (awaiting Retry/Stay) or Done. The footer's Reopen button is disabled in the same states. During opening or restoration with no exit active, Reopen stays available.
- **Back supersedes a pending reopen.** `exit()` cancels the reopen job. If the reopen is still in its `checkpointNow()`, it never reaches `open()`, because everything runs on the main thread and the awaits are cancellable. If it already started `open()`, the new session installs and inherits the exit state. `install()` copies `State.exit` instead of resetting it. The exit's flush covers every checkpoint accepted so far, including the restored one (see below).
- **Exit completion has an identity.** Each `exit()` takes an attempt number. Its result is published only if it is still the newest attempt and the state is still `Saving`, so an obsolete completion cannot finish or fail a later reader state.
- **State survives recreation.** Exit and Retry state live in the ViewModel's state, which outlives configuration changes. A recreated Activity sees Saving and finishes on Done, or shows the Failed dialog again. Process death during an exit is not covered: the exit state is not saved, and the reader reopens normally.

A failed write keeps the previous file and the last accepted `SavedStateHandle` value. If a capture times out, the exit fails with "Saving timed out"; nothing is presented as saved. Pause and stop only queue a best-effort flush and log a failure. They are not a persistence guarantee; Back and an explicit checkpoint are the paths that report a result.

### Navigation policy during restoration and reflow

User navigation is deferred, that is disabled, unless the phase is Ready and no exit is in progress (`State.canNavigate`). This covers the following inputs:

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

## Future production resolver

P01-02 will resolve an existing title/item to exactly one of:

- `ImagePages`: preserve the current image reader and existing source URL/page-list behavior.
- `EpubPublication`: item identity plus an available, owned or user-owned asset reference, media type/layout, and verified fingerprint.
- `Error`: typed missing, permission, corrupt, unsupported protection, or unsupported format.

The future item and title identities remain Mihon's authoritative database identities. EPUB spine resources are navigation units, not fake chapters. Durable managed assets must be atomically committed and independently available; user-owned Local files must not be deleted by Mihon. Replaced bytes retain old locator state under the old fingerprint until a separately validated recovery succeeds.

# EPUB contracts

## P01-01 concrete target

`EpubFixtureReference(id, assetPath, expectedSha256)` is the only launch target. The asset is debug-only, copied to app-private cache while hashing, and owned by one closeable session. Availability errors are typed; fixture bytes are never put in an Intent or saved state.

`EpubPublicationOpener.open()` creates its private directory and temporary file inside its error handling (an I/O failure is a typed `ResourceUnavailable`). Until `open()` has actually returned to its caller it keeps cleanup ownership: `handOff()` closes a produced session if cancellation interrupts the return dispatch, and partial acquisitions are closed in the opener's `finally`. The ViewModel adopts a returned session with no suspension point in between.

`EpubPublicationSession` is the sole owner of the private file, Readium asset, publication, and navigator factory. `close()` is idempotent and releases them in reverse acquisition order, deleting the private file. `EpubOpenError` distinguishes missing, corrupt, unsupported format, protected publication, fingerprint mismatch, resource unavailable, and restoration failed.

`EpubReaderPreferences(fontSizeOverride)` uses `null` to inherit the diagnostic default. Persistence is intentionally temporary.

`EpubLocatorEnvelope(version, targetId, publicationFingerprint, locatorJson)` has schema version `1`. `locatorJson` is the complete Readium JSON string, not a reconstructed subset. Decoding rejects inputs over 64 KiB before JSON parsing, wrong versions/targets/fingerprints, invalid locator structure, and resources absent from the opened publication. Encoded paths, query, and fragments are parsed by Readium's URL model. Recovery returns a typed reason and starts the publication; it never treats a percentage or page number as an exact location.

### Ownership

Every callback carries an `EpubOwner(sessionId, bindingId)`. A session id is assigned per successful open; a binding id per navigator attachment. Each binding creates its own Readium fragment factory and listeners, records the navigator instance it instantiated, owns exactly one locator collector and one input listener, and stops forwarding once detached. Restoration and reflow are operations with their own ids. `EpubCheckpointGate` rejects locator, pagination, resource-error, attachment, and operation-completion events from any owner or operation that is not current; an obsolete resource error cannot change the current state.

The host fragment reuses a child navigator only when it is the instance created by the active session's current binding. Dummy (process-restored) and obsolete navigators are removed explicitly before a replacement is created. The Activity compares the active session id with the bound one on every collected state, so conflated states and stopped collection do not matter.

Teardown order: close the gate → detach the binding (callbacks, collector and input listener stop) → remove/destroy the navigator → close the publication. A replaced session is closed only after the host reports that its navigator was destroyed; `onCleared()` closes remaining sessions after the current destroy dispatch.

### Phases and persistence

`Opening → Attaching → Rendering → Restoring → Ready`, with `Reflowing` from `Ready` and `Closing`/`Error` as closed states. `Rendering` ends only with a Readium page-load/page-change callback from the current binding. Restoration navigates to the saved locator and completes only after a subsequent Readium location callback for that binding *and* a DOM check that the anchored element intersects the viewport; this also covers a final locator equal to the initial one. Checkpoints are accepted only in `Ready`; transitional emissions and manual checkpoints in other phases are ignored. A failed restoration becomes `Error(RestorationFailed)` and keeps the last valid checkpoint.

Accepted locators are enriched with the first visible element's CSS selector and text from Readium, so a passage inside a long resource is restorable after reflow. The full Readium JSON is preserved.

Accepted envelopes update `SavedStateHandle` immediately. `EpubCheckpointWriter` is a single actor coroutine that performs every diagnostic write inline: at most one physical write at a time, pending values conflated to the newest, a 300 ms coalescing delay, and `flush()` returning `Success` only after the newest value accepted before the call is stored (`Failure` otherwise; the value is retried on the next save or flush). Writes go to a temporary file followed by an atomic replace, so a failed write keeps the previous file. Flushes run on pause, stop, explicit checkpoint, reopen, and back.

### Diagnostic close-and-open checkpoint

`EpubDiagnosticCheckpointStore` keeps one file per fixture id under `cacheDir/epub-diagnostic-checkpoints/`. It is a P01-01 diagnostic aid, not the durable production reading-state repository (P01-04). Precedence when a reader instance installs a session: `SavedStateHandle` checkpoint (same task or process restoration) → diagnostic file → publication start. Every candidate is decoded by `EpubLocatorCodec` (size, version, target, fingerprint, resource membership). Missing files start silently; unreadable or invalid ones start with a recovery reason and are replaced by the next accepted checkpoint.

## Future production resolver

P01-02 will resolve an existing title/item to exactly one of:

- `ImagePages`: preserve the current image reader and existing source URL/page-list behavior.
- `EpubPublication`: item identity plus an available, owned or user-owned asset reference, media type/layout, and verified fingerprint.
- `Error`: typed missing, permission, corrupt, unsupported protection, or unsupported format.

The future item and title identities remain Mihon's authoritative database identities. EPUB spine resources are navigation units, not fake chapters. Durable managed assets must be atomically committed and independently available; user-owned Local files must not be deleted by Mihon. Replaced bytes retain old locator state under the old fingerprint until a separately validated recovery succeeds.

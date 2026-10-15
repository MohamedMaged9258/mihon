# EPUB contracts

## P01-01 concrete target

`EpubFixtureReference(id, assetPath, expectedSha256)` is the only launch target. The asset is debug-only, copied to app-private cache while hashing, and owned by one closeable session. Cancellation or replacement closes partial and prior acquisitions. Availability errors are typed; fixture bytes are never put in an Intent or saved state.

`EpubPublicationSession` is the sole owner of the private file, Readium asset, publication, and navigator factory. It closes them in reverse acquisition order. `EpubOpenError` distinguishes missing, corrupt, unsupported format, protected publication, fingerprint mismatch, and resource unavailable.

`EpubReaderPreferences(fontSizeOverride)` uses `null` to inherit the diagnostic default. Persistence is intentionally temporary.

`EpubLocatorEnvelope(version, targetId, publicationFingerprint, locatorJson)` has schema version `1`. `locatorJson` is the complete Readium JSON string, not a reconstructed subset. Decoding rejects inputs over 64 KiB before JSON parsing, wrong versions/targets/fingerprints, invalid locator structure, and resources absent from the opened publication. Encoded paths, query, and fragments are parsed by Readium's URL model. Recovery returns a typed reason and starts the publication; it never treats a percentage or page number as an exact location.

Checkpoint writes are gated by `Opening → Attaching → Restoring → Ready → Reflowing → Closing/Error`. A generation and expected resource prevent default, stale, and reflow emissions from overwriting the accepted checkpoint. Accepted writes update `SavedStateHandle` immediately, serialize through a mutex, coalesce diagnostic file writes, and flush on pause, stop, explicit checkpoint, reopen, and back.

## Future production resolver

P01-02 will resolve an existing title/item to exactly one of:

- `ImagePages`: preserve the current image reader and existing source URL/page-list behavior.
- `EpubPublication`: item identity plus an available, owned or user-owned asset reference, media type/layout, and verified fingerprint.
- `Error`: typed missing, permission, corrupt, unsupported protection, or unsupported format.

The future item and title identities remain Mihon's authoritative database identities. EPUB spine resources are navigation units, not fake chapters. Durable managed assets must be atomically committed and independently available; user-owned Local files must not be deleted by Mihon. Replaced bytes retain old locator state under the old fingerprint until a separately validated recovery succeeds.

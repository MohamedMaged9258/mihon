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

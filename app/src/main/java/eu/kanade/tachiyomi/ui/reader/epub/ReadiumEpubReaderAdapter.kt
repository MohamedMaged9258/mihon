package eu.kanade.tachiyomi.ui.reader.epub

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs

/** The only class in the feature exposing Readium navigator/publication model APIs. */
@OptIn(ExperimentalReadiumApi::class)
class ReadiumEpubReaderAdapter(
    val sessionId: Long,
    private val session: EpubPublicationSession,
    private var preferences: EpubReaderPreferences,
    private val onEvent: (EpubOwner, Event) -> Unit,
) {
    data class TocEntry(val id: String, val title: String, val depth: Int)

    sealed interface Event {
        data class LocatorChanged(val locator: Locator) : Event
        data object PageRendered : Event
        data class ResourceFailed(val detail: String) : Event
    }

    /**
     * One navigator attachment. Its Readium listeners are created for this binding only, so a
     * callback always carries the identity of the navigator instance that produced it.
     */
    inner class Binding internal constructor(id: Long, initialLocator: Locator?) {
        val owner = EpubOwner(sessionId, id)
        var navigator: EpubNavigatorFragment? = null
            private set
        var detached = false
            private set
        var bound = false
            private set

        /** Incremented for every rendered page/location computed by this binding's navigator. */
        internal val renderSignals = MutableStateFlow(0L)
        private var collector: Job? = null
        private var input: DirectionalNavigationAdapter? = null

        /**
         * Threading contract: binding, gate, capture and session state are main-thread owned.
         * Pagination and locator callbacks already arrive on the main thread and are delivered
         * inline. Others (the resource-error listener runs in WebView resource interception) are
         * posted to the main looper without waiting, so the resource thread is never blocked. The
         * event keeps this binding's owner; `detached` and the ViewModel's ownership check run at
         * delivery, so an event queued before a rebinding or replacement is dropped.
         */
        private fun emit(event: Event) {
            if (Looper.getMainLooper().isCurrentThread) deliver(event) else mainHandler.post { deliver(event) }
        }

        private fun deliver(event: Event) {
            if (!detached) onEvent(owner, event)
        }

        private val readiumFactory = session.navigatorFactory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = EpubPreferences(fontSize = preferences.fontSizeOverride),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onResourceLoadFailed(href: Url, error: ReadError) =
                    reportResourceFailure(error.message)

                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    // Diagnostic fixtures never launch external links. A production confirmation UX
                    // belongs to a later package.
                }
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageLoaded() = signal()

                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    // Issued after Readium computed the location, including when it equals the
                    // previous StateFlow value (which then does not emit).
                    signal()
                }
            },
        )

        /** Readium calls this from WebView resource interception, which may be a worker thread. */
        @VisibleForTesting
        internal fun reportResourceFailure(detail: String) = emit(Event.ResourceFailed(detail))

        private fun signal() {
            if (detached) return
            renderSignals.value += 1
            emit(Event.PageRendered)
        }

        /** Records the navigator instance it creates so ownership can be checked by identity. */
        val factory: FragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                readiumFactory.instantiate(classLoader, className).also {
                    if (it is EpubNavigatorFragment && !detached) navigator = it
                }
        }

        internal fun bind(lifecycleOwner: LifecycleOwner) {
            val epubNavigator = checkNotNull(navigator) { "Binding has no navigator" }
            check(!detached && !bound)
            bound = true
            input = DirectionalNavigationAdapter(epubNavigator).also(epubNavigator::addInputListener)
            collector = lifecycleOwner.lifecycleScope.launch {
                epubNavigator.currentLocator.collect { emit(Event.LocatorChanged(it)) }
            }
        }

        internal fun detach() {
            if (detached) return
            detached = true
            collector?.cancel()
            collector = null
            input?.let { listener -> navigator?.removeInputListener(listener) }
            input = null
            navigator = null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tocLinks = linkedMapOf<String, Link>()
    private var bindingCounter = 0L
    var currentBinding: Binding? = null
        private set

    val title: String = session.publication.metadata.title ?: "EPUB fixture"
    val tableOfContents: List<TocEntry> = buildList {
        fun addLinks(links: List<Link>, depth: Int) {
            links.forEachIndexed { index, link ->
                val id = "$depth:$size:$index"
                tocLinks[id] = link
                add(TocEntry(id, link.title ?: link.href.toString(), depth))
                addLinks(link.children, depth + 1)
            }
        }
        addLinks(session.publication.tableOfContents, 0)
    }

    /** Replaces (and detaches) any previous binding, so at most one collector/listener exists. */
    fun newBinding(initialLocator: Locator?): Binding {
        currentBinding?.detach()
        return Binding(++bindingCounter, initialLocator).also { currentBinding = it }
    }

    fun bind(binding: Binding, lifecycleOwner: LifecycleOwner): Boolean {
        if (binding !== currentBinding || binding.detached || binding.navigator == null) return false
        binding.bind(lifecycleOwner)
        return true
    }

    fun detach(binding: Binding?) {
        binding?.detach()
    }

    fun detachAll() {
        currentBinding?.detach()
        currentBinding = null
    }

    fun owns(fragment: Fragment?): Boolean =
        fragment != null && currentBinding?.let { !it.detached && it.navigator === fragment } == true

    private fun navigatorFor(owner: EpubOwner): EpubNavigatorFragment? =
        currentBinding?.takeIf { it.owner == owner && !it.detached && it.bound }?.navigator

    private val navigator: EpubNavigatorFragment?
        get() = currentBinding?.takeIf { !it.detached && it.bound }?.navigator

    fun currentLocator(): Locator? = navigator?.currentLocator?.value
    fun previous(): Boolean = navigator?.goBackward(animated = true) == true
    fun next(): Boolean = navigator?.goForward(animated = true) == true
    fun goToToc(id: String): Boolean = tocLinks[id]?.let { navigator?.go(it, animated = true) } == true
    fun go(locator: Locator): Boolean = navigator?.go(locator, animated = false) == true

    /** Diagnostic jump to an element id inside a reading-order resource. */
    fun goToElement(hrefSuffix: String, elementId: String): Boolean {
        val link = session.publication.readingOrder.firstOrNull { it.href.toString().endsWith(hrefSuffix) }
            ?: return false
        val locator = session.publication.locatorFromLink(link) ?: return false
        return go(
            locator.copy(
                locations = Locator.Locations(
                    fragments = listOf(elementId),
                    otherLocations = mapOf("cssSelector" to "#$elementId"),
                ),
            ),
        )
    }

    /**
     * Adds the first visible element's selector and text to a Readium locator, so a later restore
     * can distinguish passages inside one long spine resource. The full Readium JSON is kept.
     *
     * Returns `null` while the locator is transitional: Readium's location can still name the
     * previous resource after a jump while the DOM already shows the next one.
     */
    suspend fun anchored(owner: EpubOwner, locator: Locator): Locator? {
        val navigator = navigatorFor(owner) ?: return null
        val element = suspendCatching("first visible element") { navigator.firstVisibleElementLocator() }
            ?: return locator
        if (element.href != locator.href) {
            logcat(LogPriority.DEBUG) { "EPUB transitional location ${locator.href}, visible ${element.href}" }
            return null
        }
        val selector = element.locations.cssSelector
            ?.takeIf { it.length <= MAX_SELECTOR_LENGTH }
            ?: return locator
        return locator.copy(
            locations = locator.locations.copy(
                otherLocations = locator.locations.otherLocations + ("cssSelector" to selector),
            ),
            text = element.text.takeIf { !it.highlight.isNullOrBlank() } ?: locator.text,
        )
    }

    /**
     * Navigates to [locator] and acknowledges only completed rendering: Readium must report a
     * computed location for this binding after the request, then no further location for a quiet
     * window while the anchored element intersects the viewport (or, without an anchor, the
     * resource matches). Equal before/after locators are handled because the DOM is checked, and
     * late relayouts (seen on older WebViews after rotation) restart the wait or navigation.
     */
    suspend fun restore(owner: EpubOwner, locator: Locator): Boolean {
        val binding = currentBinding?.takeIf { it.owner == owner } ?: return false
        attempts@ for (attempt in 1..RESTORE_ATTEMPTS) {
            val navigator = navigatorFor(owner) ?: return false
            val before = binding.renderSignals.value
            if (!navigator.go(locator, animated = false)) return false
            withTimeoutOrNull(SIGNAL_TIMEOUT_MS) { binding.renderSignals.first { it > before } }
                ?: continue@attempts
            for (check in 1..SETTLE_CHECKS) {
                val seen = binding.renderSignals.value
                val moved = withTimeoutOrNull(SETTLE_MS) { binding.renderSignals.first { it > seen } } != null
                // Quiet: either restored, or stable at the wrong place and worth another go().
                if (!moved) {
                    if (isAt(owner, locator)) return true
                    continue@attempts
                }
            }
        }
        return false
    }

    /**
     * Whether [locator]'s resource is current and its anchored element intersects the viewport.
     * A locator without an element anchor (Readium's own report, e.g. a pending Back/Reopen
     * target whose capture never completed) must be within about a page of its progression, so
     * a navigator still at the start of the resource does not count as restored.
     */
    suspend fun isShowing(owner: EpubOwner, locator: Locator): Boolean = isAt(owner, locator)

    private suspend fun isAt(owner: EpubOwner, locator: Locator): Boolean {
        val navigator = navigatorFor(owner) ?: return false
        if (navigator.currentLocator.value.href != locator.href) return false
        val selector = locator.locations.cssSelector ?: return isNearProgression(owner, navigator, locator)
        val result = evaluate(owner, VISIBLE_SCRIPT.format(JSONObject.quote(selector)))
        logcat(LogPriority.DEBUG) { "EPUB restore check $selector -> $result" }
        return result == "yes"
    }

    private suspend fun isNearProgression(
        owner: EpubOwner,
        navigator: EpubNavigatorFragment,
        locator: Locator,
    ): Boolean {
        val expected = locator.locations.progression ?: return true
        val current = navigator.currentLocator.value.locations.progression ?: return false
        // One viewport as a fraction of the resource, along the reading axis.
        val page = evaluate(owner, PAGE_FRACTION_SCRIPT)?.toDoubleOrNull() ?: return false
        val near = abs(current - expected) <= page * 1.5
        logcat(LogPriority.DEBUG) { "EPUB restore check progression $current vs $expected (page $page): $near" }
        return near
    }

    /** Ids of elements intersecting the viewport, for diagnostics and instrumented assertions. */
    suspend fun visibleAnchors(): List<String> {
        val owner = currentBinding?.owner ?: return emptyList()
        return evaluate(owner, VISIBLE_IDS_SCRIPT)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    }

    private suspend fun evaluate(owner: EpubOwner, script: String): String? {
        val navigator = navigatorFor(owner) ?: return null
        val raw = suspendCatching("script") { navigator.evaluateJavascript(script) } ?: return null
        // WebView returns the JSON encoding of the script's value.
        return raw.removeSurrounding("\"")
    }

    /**
     * Readium's WebView calls resume through `suspendCoroutine`, so they may also complete after
     * the caller was cancelled; callers re-validate. Cancellation itself is never swallowed.
     */
    private suspend fun <T> suspendCatching(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.DEBUG, e) { "EPUB $what lookup failed" }
        null
    }

    /** Submits the font preference and waits until the navigator applied it. */
    suspend fun setFontSize(owner: EpubOwner, fontSize: Double?): EpubReaderPreferences? {
        val navigator = navigatorFor(owner) ?: return null
        val editor = session.navigatorFactory.createPreferencesEditor(
            EpubPreferences(fontSize = preferences.fontSizeOverride),
        )
        editor.fontSize.set(fontSize)
        navigator.submitPreferences(editor.preferences)
        val expected = editor.preferences.fontSize
        if (expected != null) {
            withTimeoutOrNull(SIGNAL_TIMEOUT_MS) { navigator.settings.first { it.fontSize == expected } }
                ?: return null
        }
        preferences = EpubReaderPreferences(expected)
        return preferences
    }

    companion object {
        private const val MAX_SELECTOR_LENGTH = 512
        private const val RESTORE_ATTEMPTS = 3
        private const val SIGNAL_TIMEOUT_MS = 5_000L
        private const val SETTLE_CHECKS = 10
        private const val SETTLE_MS = 500L

        private const val VISIBLE_FUNCTION =
            "function v(e){var r=e.getBoundingClientRect();return r.width+r.height>0&&r.right>0&&" +
                "r.left<window.innerWidth&&r.bottom>0&&r.top<window.innerHeight;}"
        private const val VISIBLE_SCRIPT =
            "(function(){$VISIBLE_FUNCTION var e=document.querySelector(%s);" +
                "return e?(v(e)?'yes':'no'):'missing';})()"
        private const val PAGE_FRACTION_SCRIPT =
            "(function(){var e=document.scrollingElement;" +
                "return Math.min(window.innerWidth/e.scrollWidth,window.innerHeight/e.scrollHeight);})()"
        private const val VISIBLE_IDS_SCRIPT =
            "(function(){$VISIBLE_FUNCTION return Array.prototype.filter.call(" +
                "document.querySelectorAll('[id]'),v).map(function(e){return e.id;}).join(',');})()"

        fun dummyFragmentFactory(): FragmentFactory = EpubNavigatorFragment.createDummyFactory()
    }
}

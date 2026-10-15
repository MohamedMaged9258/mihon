package eu.kanade.tachiyomi.ui.reader.epub

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/** The only class in the feature exposing Readium navigator/publication model APIs. */
@OptIn(ExperimentalReadiumApi::class)
class ReadiumEpubReaderAdapter(
    private val session: EpubPublicationSession,
    initialLocator: Locator?,
    private var preferences: EpubReaderPreferences,
    private val onLocator: (Locator) -> Unit,
    private val onResourceError: (String) -> Unit,
) {
    data class TocEntry(val id: String, val title: String, val depth: Int)

    private val tocLinks = linkedMapOf<String, Link>()
    private var navigator: EpubNavigatorFragment? = null
    private var directionalNavigation: DirectionalNavigationAdapter? = null

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

    val fragmentFactory: FragmentFactory = session.navigatorFactory.createFragmentFactory(
        initialLocator = initialLocator,
        initialPreferences = EpubPreferences(fontSize = preferences.fontSizeOverride),
        listener = object : EpubNavigatorFragment.Listener {
            override fun onResourceLoadFailed(
                href: org.readium.r2.shared.util.Url,
                error: org.readium.r2.shared.util.data.ReadError,
            ) {
                onResourceError(error.message)
            }

            override fun onExternalLinkActivated(url: org.readium.r2.shared.util.AbsoluteUrl) {
                // Diagnostic fixtures never launch external links. A production confirmation UX
                // belongs to P01-03.
            }
        },
        paginationListener = object : EpubNavigatorFragment.PaginationListener {
            override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                // Readium's StateFlow does not emit when restoration computes a locator equal to
                // its initial value. This callback is issued after the requested page has loaded
                // and therefore provides the acknowledgement needed to reopen checkpoint writes.
                onLocator(locator)
            }
        },
    )

    fun bind(fragment: Fragment, scope: LifecycleCoroutineScope) {
        val epubNavigator = fragment as EpubNavigatorFragment
        if (navigator === epubNavigator) return
        navigator = epubNavigator
        directionalNavigation = DirectionalNavigationAdapter(epubNavigator).also {
            epubNavigator.addInputListener(it)
        }
        scope.launch {
            // The first value is the constructor default. The ViewModel's resource gate decides
            // when subsequent restoration/reflow emissions are eligible for persistence.
            epubNavigator.currentLocator.drop(1).onEach(onLocator).collect {}
        }
    }

    fun unbind() {
        directionalNavigation?.let { listener -> navigator?.removeInputListener(listener) }
        directionalNavigation = null
        navigator = null
    }

    fun currentLocator(): Locator? = navigator?.currentLocator?.value
    fun previous(): Boolean = navigator?.goBackward(animated = true) == true
    fun next(): Boolean = navigator?.goForward(animated = true) == true
    fun goToToc(id: String): Boolean = tocLinks[id]?.let { navigator?.go(it, animated = true) } == true

    fun restore(locator: Locator): Boolean {
        val navigator = navigator ?: return false
        if (!navigator.go(locator, animated = false)) return false

        // A reflow can keep the exact same StateFlow value, in which case no new emission is
        // produced. Only acknowledge the post-go value when it is the exact requested locator;
        // otherwise the pagination callback will acknowledge restoration after the page loads.
        navigator.currentLocator.value
            .takeIf { it == locator }
            ?.let(onLocator)
        return true
    }

    fun setFontSize(fontSize: Double?): EpubReaderPreferences {
        val current = EpubPreferences(fontSize = preferences.fontSizeOverride)
        val editor = session.navigatorFactory.createPreferencesEditor(current)
        editor.fontSize.set(fontSize)
        navigator?.submitPreferences(editor.preferences)
        preferences = EpubReaderPreferences(editor.preferences.fontSize)
        return preferences
    }

    companion object {
        fun dummyFragmentFactory(): FragmentFactory = EpubNavigatorFragment.createDummyFactory()
    }
}

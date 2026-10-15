package eu.kanade.tachiyomi.ui.reader.epub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import eu.kanade.tachiyomi.R
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.util.WeakHashMap

/**
 * Hosts exactly one Readium navigator, owned by the active publication session. A child
 * navigator is reused only when the current binding instantiated it; dummy (process-restored) and
 * obsolete navigators are removed explicitly before a replacement is bound.
 */
class EpubReaderFragment : Fragment() {
    private val viewModel: EpubReaderViewModel get() = (requireActivity() as EpubReaderActivity).viewModel
    private var binding: ReadiumEpubReaderAdapter.Binding? = null

    /** Which session's publication each navigator instance uses, for release on destruction. */
    private val navigatorSessions = WeakHashMap<Fragment, Long>()

    /** Session of the navigator bound right now, or `0`. */
    val boundSessionId: Long
        get() = binding?.takeIf { it.bound && !it.detached }?.owner?.sessionId ?: 0L

    /** The child navigator currently attached to this host, for diagnostics and tests. */
    val navigatorFragment: Fragment?
        get() = if (view == null) null else childFragmentManager.findFragmentById(R.id.epub_navigator_container)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate(): restored children are instantiated by this factory. A
        // fresh binding per host instance gives restored navigators a new owner identity.
        binding = viewModel.newBinding()
        childFragmentManager.fragmentFactory =
            binding?.factory ?: ReadiumEpubReaderAdapter.dummyFragmentFactory()
        childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                    navigatorSessions.remove(f)?.let(viewModel::navigatorReleased)
                }
            },
            false,
        )
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = R.id.epub_navigator_container }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attach()
    }

    /** Idempotent: ensures the attached navigator belongs to the ViewModel's active session. */
    fun attach() {
        if (view == null || !isAdded) return
        val sessionId = viewModel.state.value.sessionId
        val existing = navigatorFragment
        if (sessionId == 0L || viewModel.adapter?.sessionId != sessionId) {
            // No installed session (opening or failed): an old navigator must not outlive it.
            removeNavigators()
            return
        }
        var current = binding
        if (current != null && current.bound && !current.detached && current.owner.sessionId == sessionId &&
            existing === current.navigator
        ) {
            return
        }
        if (current == null || current.detached || current.owner.sessionId != sessionId) {
            current = viewModel.newBinding() ?: return
            binding = current
        }
        val navigator = current.navigator
        if (existing != null && existing === navigator) {
            navigatorSessions[existing] = sessionId
            viewModel.bindNavigator(current, viewLifecycleOwner)
            return
        }

        removeNavigators()
        childFragmentManager.fragmentFactory = current.factory
        val created = current.factory.instantiate(requireContext().classLoader, EpubNavigatorFragment::class.java.name)
        navigatorSessions[created] = sessionId
        childFragmentManager.beginTransaction()
            .replace(R.id.epub_navigator_container, created, NAVIGATOR_TAG)
            .commitNowAllowingStateLoss()
        viewModel.bindNavigator(current, viewLifecycleOwner)
    }

    private fun removeNavigators() {
        val children = childFragmentManager.fragments
        if (children.isEmpty()) return
        val transaction = childFragmentManager.beginTransaction()
        children.forEach(transaction::remove)
        transaction.commitNowAllowingStateLoss()
    }

    override fun onDestroyView() {
        viewModel.detachBinding(binding)
        super.onDestroyView()
    }

    companion object {
        const val TAG = "epub-reader-parent"
        private const val NAVIGATOR_TAG = "epub-readium-navigator"
    }
}

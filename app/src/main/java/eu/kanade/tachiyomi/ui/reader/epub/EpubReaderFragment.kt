package eu.kanade.tachiyomi.ui.reader.epub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.R

class EpubReaderFragment : Fragment() {
    private val host: EpubReaderActivity get() = requireActivity() as EpubReaderActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        childFragmentManager.fragmentFactory =
            host.viewModel.adapter?.fragmentFactory ?: ReadiumEpubReaderAdapter.dummyFragmentFactory()
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = R.id.epub_navigator_container }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attachFreshNavigator()
    }

    fun attachFreshNavigator() {
        val adapter = host.viewModel.adapter
        val restored = childFragmentManager.findFragmentByTag(NAVIGATOR_TAG)
        if (adapter != null && restored != null) {
            adapter.bind(restored, viewLifecycleOwner.lifecycleScope)
            host.viewModel.navigatorAttached()
            return
        }
        adapter?.unbind()
        childFragmentManager.fragments.forEach {
            childFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
        }
        if (adapter == null) return
        childFragmentManager.fragmentFactory = adapter.fragmentFactory
        val navigator = childFragmentManager.fragmentFactory.instantiate(
            requireContext().classLoader,
            "org.readium.r2.navigator.epub.EpubNavigatorFragment",
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.epub_navigator_container, navigator, NAVIGATOR_TAG)
            .commitNowAllowingStateLoss()
        adapter.bind(navigator, viewLifecycleOwner.lifecycleScope)
        host.viewModel.navigatorAttached()
    }

    override fun onDestroyView() {
        host.viewModel.adapter?.unbind()
        super.onDestroyView()
    }

    companion object {
        const val TAG = "epub-reader-parent"
        private const val NAVIGATOR_TAG = "epub-readium-navigator"
    }
}

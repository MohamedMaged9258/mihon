package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph

class EpubReaderActivity : BaseActivity() {
    private val graph: AppGraph by lazy { metroGraph() }
    val viewModel by viewModels<EpubReaderViewModel> { graph.viewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.addView(
            FrameLayout(this).apply { id = R.id.epub_reader_container },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            ComposeView(this).apply { setComposeContent { ReaderHeader() } },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        root.addView(
            ComposeView(this).apply { setComposeContent { ReaderFooter() } },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    viewModel.checkpoint()
                    finish()
                }
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                var attachedGeneration = -1L
                viewModel.state.collect { state ->
                    if (state.phase == EpubRestorationPhase.Opening && attachedGeneration >= 0) {
                        (supportFragmentManager.findFragmentByTag(EpubReaderFragment.TAG) as? EpubReaderFragment)
                            ?.attachFreshNavigator()
                    }
                    if (state.sessionGeneration > 0 && state.sessionGeneration != attachedGeneration) {
                        attachedGeneration = state.sessionGeneration
                        attachReader()
                    }
                }
            }
        }
    }

    private fun attachReader() {
        val current = supportFragmentManager.findFragmentByTag(EpubReaderFragment.TAG)
        if (current is EpubReaderFragment) {
            current.attachFreshNavigator()
        } else {
            supportFragmentManager.beginTransaction()
                .replace(R.id.epub_reader_container, EpubReaderFragment(), EpubReaderFragment.TAG)
                .commitNowAllowingStateLoss()
        }
    }

    override fun onPause() {
        viewModel.flushCheckpoint()
        super.onPause()
    }

    override fun onStop() {
        viewModel.flushCheckpoint()
        super.onStop()
    }

    @Composable
    private fun ReaderHeader() {
        val state by viewModel.state.collectAsState()
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Button(onClick = {
                    viewModel.checkpoint()
                    finish()
                }) { Text("Back") }
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.error?.detail
                            ?: ("${state.phase}" + (state.recoveryReason?.let { " · recovery: $it" } ?: "")),
                        color = if (state.error == null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (
                    state.phase == EpubRestorationPhase.Opening ||
                    state.phase == EpubRestorationPhase.Attaching ||
                    state.phase == EpubRestorationPhase.Restoring
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }

    @Composable
    private fun ReaderFooter() {
        val state by viewModel.state.collectAsState()
        var showToc by remember { mutableStateOf(false) }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            ) {
                Button(onClick = { viewModel.previous() }) { Text("Previous") }
                Button(onClick = { viewModel.next() }) { Text("Next") }
                Button(onClick = { showToc = true }) { Text("ToC") }
                Button(onClick = { viewModel.changeFontSize(-0.1) }) { Text("A−") }
                Button(onClick = { viewModel.changeFontSize(0.1) }) { Text("A+") }
                Button(onClick = viewModel::checkpoint) { Text("Checkpoint") }
                Button(onClick = viewModel::reopen) { Text("Reopen") }
            }
        }

        if (showToc) {
            AlertDialog(
                onDismissRequest = { showToc = false },
                confirmButton = { Button(onClick = { showToc = false }) { Text("Close") } },
                title = { Text("Table of contents") },
                text = {
                    LazyColumn {
                        items(state.tableOfContents, key = { it.id }) { entry ->
                            Button(
                                onClick = {
                                    viewModel.goToToc(entry.id)
                                    showToc = false
                                },
                                modifier = Modifier.padding(start = (entry.depth * 16).dp),
                            ) { Text(entry.title) }
                        }
                    }
                },
            )
        }
    }

    companion object {
        fun newIntent(context: Context, target: EpubFixtureReference): Intent =
            Intent(context, EpubReaderActivity::class.java).putExtra(
                EpubReaderViewModel.KEY_TARGET,
                EpubReaderViewModel.json.encodeToString(target),
            )
    }
}

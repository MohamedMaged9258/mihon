package eu.kanade.tachiyomi.ui.reader.epub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
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

    /** Header and footer controls; they take measured space and never overlay the navigator. */
    lateinit var header: View
        private set
    lateinit var footer: View
        private set
    private lateinit var inputBlocker: View

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        super.onCreate(savedInstanceState)

        // The controls take measured space instead of overlaying the navigator, so Readium's
        // viewport (and the restoration visibility checks based on it) is the readable area.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = ComposeView(this).apply { setComposeContent { ReaderHeader() } }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val readingArea = FrameLayout(this)
        readingArea.addView(
            FrameLayout(this).apply { id = R.id.epub_reader_container },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        // Consumes navigator touches (taps, swipes) while navigation is not allowed.
        inputBlocker = View(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Reader busy"
        }
        readingArea.addView(inputBlocker, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(readingArea, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        footer = ComposeView(this).apply { setComposeContent { ReaderFooter() } }
        root.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = viewModel.exit()
            },
        )

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.epub_reader_container, EpubReaderFragment(), EpubReaderFragment.TAG)
                .commitNow()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // Compare session identity rather than observing transitions: StateFlow conflates
                // intermediate values and this collector is stopped while the Activity is stopped.
                viewModel.state.collect { readerFragment?.attach() }
            }
        }
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                inputBlocker.isVisible = !state.canNavigate
                if (state.exit == EpubReaderViewModel.Exit.Done) finish()
            }
        }
    }

    val readerFragment: EpubReaderFragment?
        get() = supportFragmentManager.findFragmentByTag(EpubReaderFragment.TAG) as? EpubReaderFragment

    /** Navigator key input (arrows, page, space) is ignored while navigation is not allowed. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val passThrough = event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (!passThrough && !viewModel.state.value.canNavigate) return true
        return super.dispatchKeyEvent(event)
    }

    /** Best effort only; Back ([EpubReaderViewModel.exit]) is the path that reports a result. */
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
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                val saving = state.exit == EpubReaderViewModel.Exit.Saving
                Button(onClick = viewModel::exit, enabled = !saving) { Text(if (saving) "Saving…" else "Back") }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.error?.detail
                            ?: ("${state.phase}" + (state.recoveryReason?.let { " · recovery: $it" } ?: "")),
                        color = if (state.error == null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (
                    state.phase == EpubRestorationPhase.Opening ||
                    state.phase == EpubRestorationPhase.Attaching ||
                    state.phase == EpubRestorationPhase.Rendering ||
                    state.phase == EpubRestorationPhase.Restoring ||
                    state.phase == EpubRestorationPhase.Reflowing
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }

        val exit = state.exit
        if (exit is EpubReaderViewModel.Exit.Failed) {
            AlertDialog(
                onDismissRequest = viewModel::stayAfterFailedExit,
                title = { Text("Reading position not saved") },
                text = { Text("${exit.detail}\nThe previously saved position is kept.") },
                confirmButton = { Button(onClick = viewModel::exit) { Text("Retry") } },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = viewModel::stayAfterFailedExit) { Text("Stay") }
                        Button(onClick = ::finish) { Text("Exit without saving") }
                    }
                },
            )
        }

        val reopenFailed = state.reopenFailed
        if (reopenFailed != null && exit == EpubReaderViewModel.Exit.Idle) {
            AlertDialog(
                onDismissRequest = viewModel::dismissReopenFailure,
                title = { Text("Reading position not saved") },
                text = { Text("$reopenFailed\nThe reader was not reopened.") },
                confirmButton = { Button(onClick = viewModel::reopen) { Text("Retry") } },
                dismissButton = { Button(onClick = viewModel::dismissReopenFailure) { Text("Stay") } },
            )
        }
    }

    @Composable
    private fun ReaderFooter() {
        val state by viewModel.state.collectAsState()
        var showToc by remember { mutableStateOf(false) }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            ) {
                val enabled = state.canNavigate
                Button(onClick = { viewModel.previous() }, enabled = enabled) { Text("Previous") }
                Button(onClick = { viewModel.next() }, enabled = enabled) { Text("Next") }
                Button(onClick = { showToc = true }) { Text("ToC") }
                Button(onClick = { viewModel.changeFontSize(-0.1) }, enabled = enabled) { Text("A−") }
                Button(onClick = { viewModel.changeFontSize(0.1) }, enabled = enabled) { Text("A+") }
                Button(onClick = viewModel::checkpoint) { Text("Checkpoint") }
                // Exit and Reopen compete; the ViewModel also rejects Reopen while exiting.
                Button(onClick = viewModel::reopen, enabled = state.exit == EpubReaderViewModel.Exit.Idle) {
                    Text("Reopen")
                }
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
                                enabled = state.canNavigate,
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

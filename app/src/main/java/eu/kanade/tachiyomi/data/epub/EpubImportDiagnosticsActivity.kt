package eu.kanade.tachiyomi.data.epub

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.domain.epub.model.PublicationAsset
import tachiyomi.source.local.LocalSource

/**
 * Debug-only route to the production import services: picks documents through the system picker (any document
 * provider) and imports them into the configured library storage. Not exported, disabled outside debug builds;
 * the user-facing Import action and reader routing are P01-03.
 */
class EpubImportDiagnosticsActivity : BaseActivity() {

    private val graph by lazy { appGraph }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        setComposeContent { Diagnostics() }
    }

    @Composable
    private fun Diagnostics() {
        val scope = rememberCoroutineScope()
        val log = remember { mutableStateListOf<String>() }
        var assets by remember { mutableStateOf<List<PublicationAsset>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        val cancel = remember { MutableStateFlow(false) }

        fun refresh() = scope.launch { assets = graph.publicationAssetRepository.getAll() }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            busy = true
            cancel.value = false
            scope.launch {
                log += "Importing ${uris.size} document(s) into ${graph.publicationStorage.currentRoot().redacted()}"
                val seen = mutableSetOf<Int>()
                try {
                    graph.epubImportService.import(uris, cancel).collect { result ->
                        seen += result.index
                        log += describe(result)
                    }
                } finally {
                    uris.indices.filterNot { it in seen }.forEach { log += "#$it unfinished (not committed)" }
                    busy = false
                    refresh()
                }
            }
        }

        LaunchedEffect(Unit) { refresh() }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("EPUB import diagnostics")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { picker.launch(arrayOf("application/epub+zip", "*/*")) }) {
                    Text("Import EPUBs")
                }
                OutlinedButton(enabled = busy, onClick = { cancel.value = true }) { Text("Cancel batch") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            log += "Recovery: ${graph.epubImportService.recover()}"
                            refresh()
                        }
                    },
                ) { Text("Run recovery") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            graph.mangaRepository.getFavorites().filter {
                                it.source == LocalSource.ID
                            }.forEach { manga ->
                                graph.localEpubRegistrar.registerAll(manga.id).forEach { log += "Register: $it" }
                            }
                            refresh()
                        }
                    },
                ) { Text("Register library Local EPUBs") }
            }
            HorizontalDivider()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(log.reversed()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                item { HorizontalDivider() }
                val currentRoot = graph.publicationStorage.currentRoot()
                items(assets, key = { it.id }) { asset ->
                    Column {
                        Text(
                            "asset ${asset.id} item ${asset.chapterId} ${asset.ownership} ${asset.availability}\n" +
                                "${asset.metadata.title} / ${asset.metadata.authors.joinToString()} " +
                                "[${asset.metadata.language}] ${asset.layout} ${asset.contentKind}\n" +
                                "${asset.relativePath} ${asset.byteSize} B sha ${asset.fingerprint.take(16)}… " +
                                "root ${if (asset.storageRoot == currentRoot) "current" else "other"}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                        OutlinedButton(onClick = {
                            scope.launch {
                                log += open(asset)
                                refresh()
                            }
                        }) {
                            Text("Resolve & open")
                        }
                    }
                }
            }
        }
    }

    private suspend fun open(asset: PublicationAsset): String {
        return when (val result = graph.localPublicationOpener.open(asset.chapterId)) {
            is LocalPublicationOpener.Result.Failure -> "Open ${asset.id}: ${result.error}"
            is LocalPublicationOpener.Result.Success -> result.session.use { session ->
                "Open ${asset.id}: ${session.publication.readingOrder.size} reading-order items, " +
                    "working copy=${session.usesWorkingCopy}, " +
                    "blocked requests=${DenyAllHttpClient.attemptedRequestCount}"
            }
        }
    }

    private fun describe(result: EpubImportResult): String = when (result) {
        is EpubImportResult.Imported ->
            "#${result.index} IMPORTED title ${result.mangaId} item ${result.chapterId} asset ${result.assetId} " +
                "key ${result.importKey} reused=${result.reusedKey} sha ${result.fingerprint.take(16)}… " +
                "\"${result.metadata.title}\" by ${result.metadata.authors.joinToString()}"
        is EpubImportResult.Duplicate ->
            "#${result.index} DUPLICATE of title ${result.mangaId} item ${result.chapterId} (${result.ownership})"
        is EpubImportResult.Unsupported -> "#${result.index} UNSUPPORTED ${result.error}"
        is EpubImportResult.Failed -> "#${result.index} FAILED ${result.error}"
        is EpubImportResult.Canceled -> "#${result.index} CANCELED (not committed)"
    }

    // Storage roots can be content URIs naming a user's folders; only the scheme and authority are shown
    private fun String.redacted() = Uri.parse(this).let { "${it.scheme}://${it.authority.orEmpty()}/…" }
}

package eu.kanade.tachiyomi.ui.reader.epub

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import org.json.JSONObject

class EpubFixtureLauncherActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        val fixtures = loadFixtures()
        setComposeContent { FixtureList(fixtures) }
    }

    @Composable
    private fun FixtureList(fixtures: List<Pair<String, EpubFixtureReference>>) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            Text("Native EPUB fixtures")
            fixtures.forEach { (title, target) ->
                Button(
                    onClick = {
                        startActivity(EpubReaderActivity.newIntent(this@EpubFixtureLauncherActivity, target))
                    },
                ) {
                    Text(title)
                }
            }
        }
    }

    private fun loadFixtures(): List<Pair<String, EpubFixtureReference>> = runCatching {
        val root = JSONObject(assets.open("epub-fixtures/fixtures.json").bufferedReader().use { it.readText() })
        val fixtures = root.getJSONArray("fixtures")
        List(fixtures.length()) { index ->
            val item = fixtures.getJSONObject(index)
            item.getString("title") to EpubFixtureReference(
                id = item.getString("id"),
                assetPath = item.getString("assetPath"),
                expectedSha256 = item.getString("sha256"),
            )
        }
    }.getOrElse { emptyList() }
}

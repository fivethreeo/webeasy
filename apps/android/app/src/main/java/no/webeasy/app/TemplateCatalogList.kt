package no.webeasy.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun TemplateCatalogList(
    catalog: JSONObject?,
    onSelect: (JSONObject) -> Unit,
    header: @Composable () -> Unit = {},
) {
    LazyColumn {
        item(key = "template-controls") { header() }
        for ((group, title) in listOf("templates" to "Page templates", "components" to "Reusable partials")) {
            val entries = catalog?.optJSONArray(group)
            item(key = group) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
            items(entries?.length() ?: 0, key = { "$group/${entries!!.getJSONObject(it).getString("id")}" }) { index ->
                val entry = entries!!.getJSONObject(index)
                ListItem(
                    headlineContent = { Text(entry.getString("title")) },
                    supportingContent = { Text(entry.getString("description")) },
                    modifier = Modifier.clickable { onSelect(entry) },
                )
            }
        }
    }
}

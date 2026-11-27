package no.webeasy.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkDetails(details: JSONObject, validate: (String, (Boolean) -> Unit) -> Unit, finish: (JSONObject?) -> Unit) {
    var href by remember { mutableStateOf(details.optString("href")) }
    var text by remember { mutableStateOf(details.optString("text")) }
    var expanded by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val editing = details.optBoolean("editing")
    val pages = details.optJSONArray("pages")
    val choices = (0 until (pages?.length() ?: 0)).map { pages!!.getJSONObject(it) }
    var selectedPage by remember { mutableStateOf(href.takeIf { url -> choices.any { it.getString("href") == url } } ?: "") }
    BackHandler { finish(null) }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(ui(if (editing) "Edit link" else "Insert link")) },
                navigationIcon = { OutlinedButton(onClick = { finish(null) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(ui("Cancel")) } },
                actions = {
                    Button(enabled = !checking, contentPadding = PaddingValues(horizontal = 12.dp), onClick = {
                        val url = href.trim()
                        val label = text.ifEmpty { url }
                        checking = true
                        validate(url) { valid ->
                            checking = false
                            if (valid) finish(JSONObject().put("href", url).put("text", label))
                            else error = "Use a web, email, telephone, or local page URL."
                        }
                    }) { Text(ui(if (editing) "Save link" else "Insert link")) }
                })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(value = choices.firstOrNull { it.getString("href") == selectedPage }?.getString("title") ?: ui("Enter a URL"),
                        onValueChange = {}, readOnly = true, label = { Text(ui("Page or subpage")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(ui("Enter a URL")) }, onClick = { selectedPage = ""; expanded = false })
                        choices.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.getString("title")) }, onClick = {
                                href = choice.getString("href")
                                selectedPage = href
                                if (text.isEmpty()) text = choice.getString("title")
                                error = ""
                                expanded = false
                            })
                        }
                    }
                }
                OutlinedTextField(href, { href = it; selectedPage = ""; error = "" }, Modifier.fillMaxWidth(), label = { Text(ui("Link URL")) },
                    enabled = !checking, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(ui("Link text")) },
                    enabled = !checking, readOnly = details.optBoolean("textReadOnly"))
                if (error.isNotEmpty()) Text(ui(error), color = MaterialTheme.colorScheme.error)
                if (editing) OutlinedButton(onClick = { finish(JSONObject().put("remove", true)) }) { Text(ui("Remove link")) }
            }
        }
    }
}

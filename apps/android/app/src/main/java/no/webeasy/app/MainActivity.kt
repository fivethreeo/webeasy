package no.webeasy.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val storeDelegate = lazy { ContentStore(this) }
    private val store by storeDelegate
    private lateinit var generator: WebView
    private var project by mutableStateOf<JSONObject?>(null)
    private var catalog by mutableStateOf<JSONObject?>(null)
    private var revision by mutableIntStateOf(0)
    private var status by mutableStateOf("Opening local SQLite project...")
    private var ready by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var tab by mutableIntStateOf(0)
    private val screens = mutableStateListOf<Screen>()
    private var pendingMedia: PendingMedia? = null
    private val drafts = ContentDrafts()
    private var pendingBack by mutableStateOf<Screen?>(null)
    private var pendingPublish by mutableStateOf(false)
    private var pendingHostKey by mutableStateOf<HostKeyApproval?>(null)
    private val hosting by lazy { HostingCredentials(this) }
    private var hostingStatus by mutableStateOf("Import your hosting setup QR. Pictures are decoded on this phone, not uploaded.")

    private data class Screen(val kind: String, val record: JSONObject? = null, val url: String? = null, val directory: File? = null,
                              val contentKey: String? = null)
    private data class PendingMedia(val id: String, val kind: String, val view: WebView)

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val pending = pendingMedia ?: return@registerForActivityResult
        pendingMedia = null
        if (uri == null) { respond(pending.view, pending.id); return@registerForActivityResult }
        worker.execute {
            runCatching {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else "media"
                } ?: "media"
                val type = contentResolver.getType(uri) ?: URLConnection.guessContentTypeFromName(name) ?: ""
                require(type.startsWith("${pending.kind}/"))
                store.importMedia(contentResolver.openInputStream(uri) ?: error("Cannot read media."), type, name)
            }.fold(onSuccess = { media ->
                runOnUiThread {
                    project?.getJSONArray("media")?.put(media)
                    respond(pending.view, pending.id, media)
                }

            }, onFailure = { respond(pending.view, pending.id, error = it.message ?: "Cannot import media.") })
        }
    }

    private val qrPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { InputImage.fromFilePath(this, uri) }
                .onSuccess(::readQR).onFailure { hostingStatus = "Cannot read this picture." }
        }
    }
    private val qrCamera = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { image ->
        if (image != null) readQR(InputImage.fromBitmap(image, 0))
    }

    private fun readQR(image: InputImage) {
        hostingStatus = "Reading QR on this phone..."
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        scanner.process(image).addOnSuccessListener(this) { codes ->
            val code = codes.singleOrNull()?.rawValue
            if (code == null) hostingStatus = "Choose a picture containing one Webeasy hosting setup QR."
            else importHostingQR(code)
        }.addOnFailureListener(this) { hostingStatus = "Cannot read this QR picture." }
            .addOnCompleteListener { scanner.close() }
    }
    private fun importHostingQR(code: String) {
        if (!ready) { hostingStatus = "Wait for the app to finish opening, then try again."; return }
        generator.evaluateJavascript("window.WebeasyGenerator.readConnection(${JSONObject.quote(code)})") { serialized ->
            val output = runCatching { JSONObject(JSONTokener(serialized).nextValue() as String) }.getOrNull()
            val connection = output?.optJSONObject("connection")
            if (connection == null) { hostingStatus = output?.optString("error") ?: "Cannot read this hosting QR."; return@evaluateJavascript }
            worker.execute {
                runCatching { hosting.save(connection) }.fold(
                    onSuccess = { runOnUiThread { hostingStatus = "Saved hosting: ${connection.getString("host")}. Not connected yet." } },
                    onFailure = { runOnUiThread { hostingStatus = "Cannot save hosting securely." } })
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        generator = localWebView().apply {
            webViewClient = client(loader(), onFinished = { ready = true })
            loadUrl("$base/assets/website/generator.html")
        }
        worker.execute {
            runCatching {
                store.project() to JSONObject(assets.open("website/catalog.json").bufferedReader().use { it.readText() })
            }.fold(onSuccess = { (content, examples) -> runOnUiThread {
                drafts.markSaved(drafts.capture(content))
                project = content; catalog = examples; status = "Local SQLite. Save changes before closing."
            } }, onFailure = { runOnUiThread { status = it.message ?: "Cannot open local storage." } })
            runCatching { hosting.load()?.getString("host") }.getOrNull()?.let { host ->
                runOnUiThread { hostingStatus = "Saved hosting: $host." }
            }
        }
        setContent {
            MaterialTheme {
                BackHandler(enabled = screens.isNotEmpty()) { back() }
                if (pendingPublish) AlertDialog(onDismissRequest = { pendingPublish = false },
                    title = { Text("Publish website?") },
                    text = { Text("Save and upload the website to your saved hosting destination. Matching files are replaced; unrelated files and old pages are kept. Plain FTP sends your password and content without encryption.") },
                    confirmButton = { TextButton(onClick = { pendingPublish = false; generate(true, publish = true) }) { Text("Publish") } },
                    dismissButton = { TextButton(onClick = { pendingPublish = false }) { Text("Cancel") } })
                pendingHostKey?.let { key ->
                    AlertDialog(onDismissRequest = { pendingHostKey = null }, title = { Text("Trust SFTP server key?") },
                        text = { Text("${key.host}:${key.port}\n${key.fingerprint}\nCompare this fingerprint with your server before trusting it. No password has been sent.") },
                        confirmButton = { TextButton(onClick = {
                            runCatching {
                                val connection = hosting.load() ?: error("Import hosting first.")
                                check(connection.getString("host") == key.host && connection.getInt("port") == key.port)
                                hosting.save(connection.put("_hostKeyFingerprint", key.fingerprint))
                                pendingHostKey = null; generate(true, publish = true)
                            }.onFailure { status = "Cannot save the trusted server key." }
                        }) { Text("Trust and publish") } },
                        dismissButton = { TextButton(onClick = { pendingHostKey = null }) { Text("Cancel") } })
                }
                pendingBack?.let { target ->
                    AlertDialog(onDismissRequest = { pendingBack = null }, title = { Text("Save changes?") },
                        text = { Text("Save changes before going back? Don't save discards changes to this item.") },
                        confirmButton = { TextButton(onClick = {
                            pendingBack = null
                            generate(false, afterSave = { if (screens.lastOrNull() === target) popScreen() })
                        }, enabled = ready && !busy) { Text("Save") } },
                        dismissButton = { Row {
                            TextButton(onClick = {
                                drafts.discard(target.record!!, project!!); changed(); status = "Changes discarded."
                                pendingBack = null; popScreen()
                            }) { Text("Don't save") }
                            TextButton(onClick = { pendingBack = null }) { Text("Cancel") }
                        } })
                }
                Scaffold(topBar = {
                    TopAppBar(title = { Text(screens.lastOrNull()?.record?.optString("title")?.takeIf(String::isNotEmpty) ?: "Webeasy") },
                        navigationIcon = { if (screens.isNotEmpty()) TextButton(onClick = ::back) { Text(getString(R.string.back)) } },
                        actions = {
                            TextButton(onClick = { generate(false) }, enabled = ready && !busy && project != null) { Text("Save") }
                            TextButton(onClick = { generate(true, export = true) }, enabled = ready && !busy && project != null) { Text("Export") }
                        })
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        val screen = screens.lastOrNull()
                        if (screen?.contentKey == null) Text(status, Modifier.padding(16.dp))
                        when {
                            screen?.url != null -> key(screen.url, screen.directory) { Preview(screen) }
                            screen?.contentKey != null -> key(screen.record!!.getString("id"), screen.contentKey) {
                                RichField(screen.record, screen.contentKey)
                            }
                            screen?.record != null -> key(screen.record.getString("id")) { RecordForm(screen, revision) }
                            else -> RootList(revision)
                        }
                    }
                }
            }
        }
    }

    private fun changed() {
        project?.let { content ->
            val home = content.optString("homePageId")
            if (pagePath(home) == null) content.put("homePageId", content.getJSONArray("pages").optJSONObject(0)?.getString("id") ?: JSONObject.NULL)
        }
        revision += 1; status = "Unsaved changes."
    }
    private fun back() {
        if (busy || screens.isEmpty()) return
        val screen = screens.last()
        if (screen.kind in listOf("page", "news") && drafts.hasChanges(screen.record!!, project!!)) pendingBack = screen
        else popScreen()
    }
    private fun popScreen() { screens.removeAt(screens.lastIndex).directory?.deleteRecursively() }
    private fun JSONArray.records() = List(length()) { getJSONObject(it) }
    private fun array(record: JSONObject, key: String): JSONArray = record.optJSONArray(key) ?: JSONArray().also { record.put(key, it) }

    private fun create(kind: String, siblings: JSONArray): JSONObject {
        val prefix = if (kind == "news") "news" else "page"
        var index = siblings.length() + 1
        while (siblings.records().any { it.optString("slug") == "$prefix-$index" }) index += 1
        return JSONObject().put("id", UUID.randomUUID().toString()).put("title", "New $prefix").put("menuTitle", "New page")
            .put("slug", "$prefix-$index").put("date", LocalDate.now().toString()).put("heading", if (kind == "section") "New section" else "")
            .put("content", "<p></p>").put("shortContent", "<p></p>").put("sections", JSONArray()).put("asides", JSONArray()).put("children", JSONArray())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RootList(@Suppress("UNUSED_PARAMETER") revision: Int) {
        ScrollableTabRow(selectedTabIndex = tab) {
            listOf("Pages", "News", "Templates", "Building blocks", "Hosting").forEachIndexed { index, name ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name) })
            }
        }
        if (tab == 4) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(hostingStatus)
                Button(onClick = { qrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text("Read QR from picture")
                }
                Button(onClick = { runCatching { qrCamera.launch(null) }.onFailure { hostingStatus = "Camera is unavailable." } }) {
                    Text("Take a QR picture")
                }
                TextButton(onClick = {
                    worker.execute {
                        runCatching { hosting.remove() }.fold(
                            onSuccess = { runOnUiThread { hostingStatus = "Hosting credentials removed." } },
                            onFailure = { runOnUiThread { hostingStatus = "Cannot remove hosting credentials." } })
                    }
                }) { Text("Forget hosting") }
                TextButton(onClick = { pendingPublish = true }, enabled = ready && !busy) { Text("Publish website") }
                if (busy) CircularProgressIndicator()
                Text("Credentials and trusted SFTP keys are encrypted with Android Keystore. FTPS validates the server certificate; SFTP requires a trusted server key.")
            }
            return
        }
        val source = if (tab < 2) project else catalog
        val key = listOf("pages", "news", "templates", "components")[tab]
        val records = source?.optJSONArray(key)?.records() ?: emptyList()
        LazyColumn {
            if (tab == 1) item { TextButton(onClick = { generate(true, path = "news/index.html") }) { Text("Latest three news items") } }
            items(records.size, key = { records[it].getString("id") }) { index ->
                val record = records[index]
                if (tab < 2) RowEntry(record, source!!.getJSONArray(key), index, if (tab == 0) "page" else "news")
                else ListItem(headlineContent = { Text(record.getString("title")) },
                    supportingContent = { Text(record.getString("description")) },
                    modifier = Modifier.clickable { screens.add(Screen("preview", url = "$base/assets/website/${record.getString("preview")}")) })
            }
            if (tab < 2 && source != null) item {
                TextButton(onClick = {
                    val kind = if (tab == 0) "page" else "news"
                    val collection = source.getJSONArray(key)
                    val record = create(kind, collection); collection.put(record); changed(); screens.add(Screen(kind, record))
                }) { Text(if (tab == 0) "Add page" else "Add news") }
            }
        }
    }

    @Composable
    private fun RowEntry(record: JSONObject, collection: JSONArray, index: Int, kind: String) {
        Column {
            ListItem(headlineContent = { Text(if (kind == "page") record.optString("menuTitle", "Page")
                else record.optString("title").ifEmpty { record.optString("heading").ifEmpty { "Aside" } }) },
                modifier = Modifier.clickable { screens.add(Screen(kind, record)) })
            Row {
                TextButton(onClick = { collection.remove(index); changed() }) { Text("Remove") }
                TextButton(onClick = {
                    if (index > 0) {
                        val previous = collection.getJSONObject(index - 1)
                        collection.put(index - 1, record); collection.put(index, previous); changed()
                    }
                }, enabled = index > 0) { Text("Move up") }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RecordForm(screen: Screen, @Suppress("UNUSED_PARAMETER") revision: Int) {
        val record = screen.record!!
        var dateDialog by remember(record.getString("id")) { mutableStateOf(false) }
        var layoutDialog by remember(record.getString("id")) { mutableStateOf(false) }
        val layouts = catalog?.getJSONArray("templates")?.records()?.filter { it.optBoolean("pageLayout") } ?: emptyList()
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (screen.kind in listOf("page", "news")) {
                item { NativeText(record, "title", "Title") }
                if (screen.kind == "page") {
                    item { NativeText(record, "menuTitle", "Menu title") }
                    item {
                        val selected = record.optString("templateId", "page")
                        TextButton(onClick = { layoutDialog = true }) {
                            Text("Page layout: ${layouts.find { it.getString("id") == selected }?.getString("title") ?: selected}")
                        }
                    }
                    item {
                        val current = project!!.optString("homePageId") == record.getString("id")
                        TextButton(onClick = { project!!.put("homePageId", record.getString("id")); changed() }, enabled = !current) {
                            Text(if (current) "Current homepage" else "Use as homepage")
                        }
                    }
                }
                item { NativeText(record, "slug", "Slug") }
                item { TextButton(onClick = { dateDialog = true }) { Text("Date: ${record.getString("date")}") } }
            } else item { NativeText(record, "heading", if (screen.kind == "aside") "Heading (optional)" else "Heading") }
            item {
                Text(if (screen.kind == "news") "Short content" else "Content")
                OutlinedButton(onClick = {
                    screens.add(Screen("content", record, contentKey = if (screen.kind == "news") "shortContent" else "content"))
                }) { Text(if (screen.kind == "news") "Edit short content" else "Edit content") }
            }
            val related = buildList {
                if (screen.kind in listOf("page", "news")) add(Triple("sections", "Sections", "section"))
                if (screen.kind != "aside") add(Triple("asides", "Asides", "aside"))
                if (screen.kind == "page") add(Triple("children", "Subpages", "page"))
            }
            related.forEach { (key, label, kind) ->
                item { Text(label, style = MaterialTheme.typography.titleMedium) }
                val collection = array(record, key)
                items(collection.length(), key = { "$key-${collection.getJSONObject(it).getString("id")}" }) { index ->
                    RowEntry(collection.getJSONObject(index), collection, index, kind)
                }
                item { TextButton(onClick = {
                    val child = create(kind, collection); collection.put(child); changed(); screens.add(Screen(kind, child))
                }) { Text("Add ${if (key == "children") "subpage" else kind}") } }
            }
            if (screen.kind in listOf("page", "news")) item {
                TextButton(onClick = { generate(true, path = if (screen.kind == "news")
                    "news/${record.getString("date")}/${record.getString("slug")}/index.html" else pagePath(record.getString("id"))) }) { Text("Preview") }
            }
        }
        if (layoutDialog) {
            AlertDialog(onDismissRequest = { layoutDialog = false }, title = { Text("Page layout") },
                text = { Column { layouts.forEach { layout ->
                    TextButton(onClick = { record.put("templateId", layout.getString("id")); changed(); layoutDialog = false }) {
                        Text(layout.getString("title"))
                    }
                } } },
                confirmButton = { TextButton(onClick = { layoutDialog = false }) { Text("Cancel") } })
        }
        if (dateDialog) {
            val dateState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(record.getString("date")).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { dateDialog = false },
                confirmButton = { TextButton(onClick = {
                    dateState.selectedDateMillis?.let { record.put("date", Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()); changed() }
                    dateDialog = false
                }) { Text("Select") } },
                dismissButton = { TextButton(onClick = { dateDialog = false }) { Text("Cancel") } }) { DatePicker(state = dateState) }
        }
    }

    @Composable
    private fun NativeText(record: JSONObject, key: String, label: String) {
        OutlinedTextField(value = record.optString(key), onValueChange = { record.put(key, it); changed() },
            label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = if (key == "slug") KeyboardCapitalization.None else KeyboardCapitalization.Sentences))
    }

    private fun pagePath(id: String): String? {
        fun find(pages: JSONArray, prefix: String): String? {
            for (page in pages.records()) {
                val route = "$prefix${page.getString("slug")}/"
                if (page.getString("id") == id) return route + "index.html"
                find(array(page, "children"), route)?.let { return it }
            }
            return null
        }
        return project?.getJSONArray("pages")?.let { find(it, "") }
    }

    private fun generate(compile: Boolean, path: String? = null, export: Boolean = false, afterSave: (() -> Unit)? = null, publish: Boolean = false) {
        if (busy || !ready || project == null) return
        busy = true
        val savedRevision = revision
        val savedSnapshot = drafts.capture(project!!)
        val destination = if (publish) runCatching { hosting.load() }.getOrNull() else null
        if (publish && destination == null) { busy = false; status = "Import your hosting setup QR first."; return }
        val input = if (compile) JSONObject().put("project", project).put("catalog", catalog) else project!!
        generator.evaluateJavascript("window.WebeasyGenerator.${if (compile) "compile" else "normalize"}(${JSONObject.quote(input.toString())})") { serialized ->
            val output = runCatching { JSONObject(JSONTokener(serialized).nextValue() as String) }
            if (output.isFailure || output.getOrNull()?.has("error") == true) {
                status = output.getOrNull()?.optString("error") ?: "Cannot generate content."; busy = false
                return@evaluateJavascript
            }
            val result = output.getOrThrow()
            worker.execute {
                runCatching {
                    if (!compile || export || publish) {
                        store.save(result.getJSONObject("project"))
                        runOnUiThread { drafts.markSaved(savedSnapshot) }
                    }
                    if (!compile) null else if (publish) {
                        val directory = store.prepareSite(result.getJSONArray("files"), result.getJSONArray("referencedMedia"))
                        try { SitePublisher.publish(destination!!, directory) }
                        finally { directory.deleteRecursively() }
                        null
                    } else if (export) store.exportSite(result.getJSONArray("files"), result.getJSONArray("referencedMedia"))
                    else store.prepareSite(result.getJSONArray("files"), result.getJSONArray("referencedMedia"))
                }.fold(onSuccess = { file -> runOnUiThread {
                    busy = false
                    if (publish) status = if (savedRevision == revision) "Website published." else "Published snapshot excludes newer edits."
                    else if (!compile) {
                        status = if (savedRevision == revision) "Saved locally to SQLite." else "More recent changes remain unsaved."
                        if (savedRevision == revision) afterSave?.invoke()
                    }
                    else if (export && file != null) {
                        val uri = FileProvider.getUriForFile(this, "$packageName.exports", file)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, getString(R.string.share_website)))
                        status = "Website snapshot ready to share."
                    } else if (file != null) screens.add(Screen("preview", url = "$base/preview/${path ?: "index.html"}", directory = file))
                } }, onFailure = { runOnUiThread {
                    busy = false
                    if (it is HostKeyApproval) { pendingHostKey = it; status = "Confirm the SFTP server key before publishing." }
                    else status = it.message ?: "The operation failed."
                } })
            }
        }
    }

    @Composable
    private fun RichField(record: JSONObject, key: String) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            localWebView().apply {
                val bridge = FieldBridge(this, record, key)
                tag = bridge
                addJavascriptInterface(bridge, "WebeasyNative")
                loadUrl("$base/assets/website/field.html")
            }
        }, update = { view ->
            val bridge = view.tag as FieldBridge
            if (bridge.ready && bridge.value != record.optString(key)) bridge.pushValue(record.optString(key))
        }, onRelease = { it.removeJavascriptInterface("WebeasyNative"); it.destroy() })
    }

    private inner class FieldBridge(val view: WebView, val record: JSONObject, val key: String) {
        var ready = false
        var value = ""
        fun pushValue(content: String) { value = content; view.evaluateJavascript("window.WebeasyField.setValue(${JSONObject.quote(content)});", null) }
        @JavascriptInterface fun request(serialized: String) {
            val message = runCatching { JSONObject(serialized) }.getOrNull() ?: return
            runOnUiThread {
                val id = message.optString("id")
                val payload = message.optJSONObject("payload") ?: JSONObject()
                when (message.optString("method")) {
                    "ready" -> { ready = true; pushValue(record.optString(key)); respond(view, id) }
                    "change" -> { value = payload.getString("content"); record.put(key, value); changed(); respond(view, id) }
                    "resize" -> respond(view, id)
                    "pageLinks" -> respond(view, id, JSONObject().put("pages", project?.getJSONArray("pages") ?: JSONArray())
                        .put("homePageId", project?.opt("homePageId") ?: JSONObject.NULL))
                    "pickMedia" -> {
                        val kind = payload.optString("kind")
                        if (pendingMedia != null || kind !in listOf("image", "video")) respond(view, id, error = "Cannot open media picker.")
                        else {
                            pendingMedia = PendingMedia(id, kind, view)
                            mediaPicker.launch(PickVisualMediaRequest(if (kind == "image") ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.VideoOnly))
                        }
                    }
                    else -> respond(view, id, error = "Unknown content-field operation.")
                }
            }
        }
    }

    @Composable
    private fun Preview(screen: Screen) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            localWebView().apply {
                webViewClient = client(loader(screen.directory), preview = screen.directory != null)
                loadUrl(screen.url!!)
            }
        }, onRelease = { it.destroy() })
    }

    private fun local(uri: Uri) = uri.scheme == "https" && uri.host == "appassets.androidplatform.net"
    private fun denied() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
    private fun loader(preview: File? = null): WebViewAssetLoader {
        val builder = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/media/") { path -> runCatching {
                WebResourceResponse(URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream", null,
                    ContentStore.safeFile(File(filesDir, "Media"), path).inputStream())
            }.getOrElse { denied() } }
        if (preview != null) builder.addPathHandler("/preview/", WebViewAssetLoader.InternalStoragePathHandler(this, preview))
        return builder.build()
    }
    private fun client(loader: WebViewAssetLoader, preview: Boolean = false, onFinished: () -> Unit = {}) = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            if (!local(request.url)) return denied()
            var url = request.url
            if (preview && url.path?.startsWith("/preview/") != true) {
                url = url.buildUpon().path("/preview" + url.path).build()
            }
            if (url.path?.endsWith("/") == true) url = url.buildUpon().path(url.path + "index.html").build()
            if (url.path?.startsWith("/assets/website/media/") == true) url = url.buildUpon().path(url.path!!.removePrefix("/assets/website")).build()
            return loader.shouldInterceptRequest(url) ?: denied()
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (local(request.url)) return false
            if (preview && request.hasGesture() && request.url.scheme in listOf("https", "http", "mailto", "tel")) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            }
            return true
        }
        override fun onPageFinished(view: WebView, url: String) { onFinished() }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun localWebView() = WebView(this).apply {
        settings.javaScriptEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
        webChromeClient = WebChromeClient(); webViewClient = client(loader())
    }
    private fun respond(view: WebView, id: String, result: Any = JSONObject.NULL, error: String? = null) {
        val message = JSONObject().put("id", id).put("result", result)
        if (error != null) message.put("error", error)
        runOnUiThread { view.evaluateJavascript("window.WebeasyBridge.receive($message);", null) }
    }
    override fun onDestroy() {
        generator.destroy()
        worker.execute { if (storeDelegate.isInitialized()) store.close() }; worker.shutdown()
        super.onDestroy()
    }
    companion object { private const val base = "https://appassets.androidplatform.net" }
}

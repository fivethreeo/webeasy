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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

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
    private var advancedUI by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var tab by mutableIntStateOf(0)
    private val screens = mutableStateListOf<Screen>()
    private var pendingMedia: PendingMedia? = null
    private var pendingMediaDetails by mutableStateOf<PendingMediaDetails?>(null)
    private var pendingLinkDetails by mutableStateOf<PendingLinkDetails?>(null)
    private var pendingQRPhoto: File? = null
    private val drafts = ContentDrafts()
    private var pendingBack by mutableStateOf<Screen?>(null)
    private var pendingPublish by mutableStateOf(false)
    private var pendingHostKey by mutableStateOf<HostKeyApproval?>(null)
    private var hostKeyForTemplates = false
    private val hosting by lazy { HostingCredentials(this) }
    private var hostingStatus by mutableStateOf("")
    private var hostingServer by mutableStateOf<String?>(null)
    private var hostingDirectory by mutableStateOf("")
    private var publicPathPrefix by mutableStateOf("")
    private var uploadSubfolder by mutableStateOf("")
    private var savedPublicPathPrefix by mutableStateOf("")
    private var savedUploadSubfolder by mutableStateOf("")
    private var savedContent by mutableStateOf<String?>(null)
    private var publishedContent by mutableStateOf<String?>(null)

    private fun loadPublishingSettings(connection: JSONObject) {
        hostingServer = if (connection.has("host"))
            "${connection.getString("protocol").uppercase(java.util.Locale.ROOT)} ${connection.getString("host")}:${connection.getInt("port")}" else null
        hostingDirectory = connection.optString("directory")
        publicPathPrefix = connection.optString("publicPathPrefix")
        uploadSubfolder = connection.optString("uploadSubfolder")
        savedPublicPathPrefix = publicPathPrefix
        savedUploadSubfolder = uploadSubfolder
    }
    private fun publishingSettingsChanged() = publicPathPrefix != savedPublicPathPrefix || uploadSubfolder != savedUploadSubfolder

    private fun savePublishingSettings() {
        if (!ready || busy) return
        busy = true
        val input = JSONObject().put("publicPathPrefix", publicPathPrefix).put("uploadSubfolder", uploadSubfolder)
        generator.evaluateJavascript("window.WebeasyGenerator.readPublishingSettings(${JSONObject.quote(input.toString())})") { serialized ->
            val output = runCatching { JSONObject(JSONTokener(serialized).nextValue() as String) }.getOrNull()
            val settings = output?.optJSONObject("settings")
            if (settings == null) {
                hostingStatus = output?.optString("error") ?: "Cannot validate publishing settings."
                busy = false
                return@evaluateJavascript
            }
            worker.execute {
                runCatching {
                    val connection = hosting.load() ?: error("Import your hosting setup QR first.")
                    connection.put("publicPathPrefix", settings.getString("publicPathPrefix"))
                    connection.put("uploadSubfolder", settings.getString("uploadSubfolder"))
                    hosting.save(connection)
                    connection
                }.fold(onSuccess = { connection -> runOnUiThread {
                    loadPublishingSettings(connection); hostingStatus = "Publishing settings saved."; busy = false
                } }, onFailure = { runOnUiThread {
                    hostingStatus = it.message ?: "Cannot save publishing settings."; busy = false
                } })
            }
        }
    }

    private data class Screen(val kind: String, val record: JSONObject? = null, val url: String? = null, val directory: File? = null,
                              val contentKey: String? = null)
    private data class PendingMedia(val id: String, val kind: String, val view: WebView?, val basename: String,
                                    val width: Int?, val quality: Int?, val coverRecord: JSONObject? = null, val coverAlt: String = "")
    private data class PendingMediaDetails(val id: String, val kind: String, val view: WebView?, val coverRecord: JSONObject? = null)
    private data class PendingLinkDetails(val id: String, val view: WebView, val details: JSONObject)

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val pending = pendingMedia ?: return@registerForActivityResult
        pendingMedia = null
        if (uri == null) { pending.view?.let { respond(it, pending.id) }; return@registerForActivityResult }
        if (pending.coverRecord != null) busy = true
        worker.execute {
            runCatching {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else "media"
                } ?: "media"
                val type = contentResolver.getType(uri) ?: URLConnection.guessContentTypeFromName(name) ?: ""
                require(type.startsWith("${pending.kind}/"))
                store.importMedia(contentResolver.openInputStream(uri) ?: error("Cannot read media."), type, name,
                    pending.basename, pending.width, pending.quality, if (pending.coverRecord != null) 1200 else null)
            }.fold(onSuccess = { media ->
                runOnUiThread {
                    project?.getJSONArray("media")?.put(media)
                    pending.view?.let { respond(it, pending.id, media) }
                    pending.coverRecord?.let {
                        it.put("coverMediaId", media.getString("id")).put("coverAlt", pending.coverAlt)
                        changed(); busy = false
                    }
                }

            }, onFailure = { error ->
                pending.view?.let { respond(it, pending.id, error = error.message ?: "Cannot import media.") }
                if (pending.coverRecord != null) runOnUiThread { busy = false; status = error.message ?: "Cannot import cover." }
            })
        }
    }

    private val qrPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { InputImage.fromFilePath(this, uri) }
                .onSuccess(::readQR).onFailure { hostingStatus = "Cannot read this picture." }
        }
    }
    private val qrCamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val photo = pendingQRPhoto
        pendingQRPhoto = null
        if (photo != null) {
            try {
                if (success) readQR(InputImage.fromFilePath(this,
                    FileProvider.getUriForFile(this, "$packageName.exports", photo)))
            } catch (_: Exception) {
                hostingStatus = "Cannot read this QR picture."
            } finally {
                photo.delete()
            }
        }
    }

    private fun photographQR() {
        runCatching {
            pendingQRPhoto?.delete()
            val directory = File(cacheDir, "exports").apply { mkdirs() }
            val photo = File.createTempFile("hosting-qr-", ".jpg", directory)
            pendingQRPhoto = photo
            qrCamera.launch(FileProvider.getUriForFile(this, "$packageName.exports", photo))
        }.onFailure {
            pendingQRPhoto?.delete()
            pendingQRPhoto = null
            hostingStatus = "Camera is unavailable."
        }
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
                    onSuccess = { runOnUiThread {
                        loadPublishingSettings(connection)
                        hostingStatus = "Hosting added successfully."
                    } },
                    onFailure = { runOnUiThread { hostingStatus = "Cannot save hosting securely." } })
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        advancedUI = getSharedPreferences("editor-ui", MODE_PRIVATE).getBoolean("advanced", false)
        tab = savedInstanceState?.getInt("selectedTab", 0)?.takeIf { it in 0..4 } ?: 0
        savedInstanceState?.getString("hostingQRPhoto")?.takeIf {
            it.startsWith("hosting-qr-") && it.endsWith(".jpg") && File(it).name == it
        }?.let { pendingQRPhoto = File(File(cacheDir, "exports"), it) }
        generator = localWebView().apply {
            webViewClient = client(loader(), onFinished = { ready = true })
            loadUrl("$base/assets/website/generator.html")
        }
        worker.execute {
            runCatching {
                val examples = JSONObject(assets.open("website/catalog.json").bufferedReader().use { it.readText() })
                store.templateSources()?.let { sources ->
                    for (group in listOf("templates", "components")) {
                        for (entry in examples.getJSONArray(group).records()) {
                            val path = "webeasy_templates/$group/${entry.getString("id")}.html"
                            if (sources.has(path)) entry.put("source", sources.getString(path))
                        }
                    }
                }
                Triple(store.project(), examples,
                    store.publishedSnapshot())
            }.fold(onSuccess = { (content, examples, published) -> runOnUiThread {
                drafts.markSaved(drafts.capture(content))
                savedContent = content.toString(); publishedContent = published
                project = content; catalog = examples; status = "Local SQLite. Save changes before closing."
            } }, onFailure = { runOnUiThread { status = it.message ?: "Cannot open local storage." } })
            runCatching { hosting.load() }.getOrNull()?.let { connection ->
                runOnUiThread {
                    loadPublishingSettings(connection)
                }
            }
        }
        setContent {
            MaterialTheme {
                BackHandler(enabled = screens.isNotEmpty()) { back() }
                if (pendingPublish) AlertDialog(onDismissRequest = { pendingPublish = false },
                    title = { Text("Publish website?") },
                    text = { Text("Save and upload the website to your saved hosting destination. Matching files are replaced; unrelated files and old pages are kept. Plain FTP sends your password and content without encryption.") },
                    confirmButton = { Button(onClick = { pendingPublish = false; generate(true, publish = true) }) { Text("Publish") } },
                    dismissButton = { OutlinedButton(onClick = { pendingPublish = false }) { Text("Cancel") } })
                pendingHostKey?.let { key ->
                    AlertDialog(onDismissRequest = { pendingHostKey = null }, title = { Text("Trust SFTP server key?") },
                        text = { Text("${key.host}:${key.port}\n${key.fingerprint}\nCompare this fingerprint with your server before trusting it. No password has been sent.") },
                        confirmButton = { Button(onClick = {
                            runCatching {
                                val connection = hosting.load() ?: error("Import hosting first.")
                                check(connection.getString("host") == key.host && connection.getInt("port") == key.port)
                                hosting.save(connection.put("_hostKeyFingerprint", key.fingerprint))
                                pendingHostKey = null
                                if (hostKeyForTemplates) loadServerTemplates() else generate(true, publish = true)
                            }.onFailure { status = "Cannot save the trusted server key." }
                        }) { Text(if (hostKeyForTemplates) "Trust and load" else "Trust and publish") } },
                        dismissButton = { OutlinedButton(onClick = { pendingHostKey = null }) { Text("Cancel") } })
                }
                pendingBack?.let { target ->
                    AlertDialog(onDismissRequest = { pendingBack = null }, title = { Text("Save changes?") },
                        text = { Text("Save changes before going back? Don't save discards changes to this item.") },
                        confirmButton = { Button(onClick = {
                            pendingBack = null
                            generate(false, afterSave = { if (screens.lastOrNull() === target) popScreen() })
                        }, enabled = ready && !busy) { Text("Save") } },
                        dismissButton = { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                drafts.discard(target.record!!, project!!); changed(); status = "Changes discarded."
                                pendingBack = null; popScreen()
                            }) { Text("Don't save") }
                            OutlinedButton(onClick = { pendingBack = null }) { Text("Cancel") }
                        } })
                }
                val primaryNavigation = screens.isEmpty()
                val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
                Box(Modifier.fillMaxSize()) {
                    Scaffold(bottomBar = {
                        if (primaryNavigation && !tablet) PrimaryNavigation(rail = false, selectedTab = tab, onSelect = { tab = it })
                    }, topBar = {
                        TopAppBar(title = { Text(screens.lastOrNull()?.record?.optString("title")?.takeIf(String::isNotEmpty)
                            ?: if (screens.lastOrNull()?.kind == "templates") "Templates" else "Webeasy",
                            maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = { if (screens.isNotEmpty()) OutlinedButton(onClick = ::back,
                                contentPadding = PaddingValues(horizontal = 12.dp)) { Text(getString(R.string.back)) } },
                            actions = {
                                Button(onClick = { generate(false) }, enabled = ready && !busy && project != null,
                                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Save") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { generate(true, export = true) }, enabled = ready && !busy && project != null,
                                    contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Export") }
                            })
                    }) { padding ->
                        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                            if (primaryNavigation && tablet) PrimaryNavigation(rail = true, selectedTab = tab, onSelect = { tab = it })
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                val screen = screens.lastOrNull()
                                if (screen?.contentKey == null) Text(status, Modifier.padding(16.dp))
                                when {
                                    screen?.url != null -> key(screen.url, screen.directory) { Preview(screen) }
                                    screen?.contentKey != null -> key(screen.record!!.getString("id"), screen.contentKey) {
                                        RichField(screen.record, screen.contentKey)
                                    }
                                    screen?.kind == "template" -> TemplateSource(screen.record!!)
                                    screen?.kind == "templates" -> Templates()
                                    screen?.record != null -> key(screen.record.getString("id")) { RecordForm(screen, revision) }
                                    else -> RootList(revision)
                                }
                            }
                        }
                    }
                    pendingMediaDetails?.let { pending -> key(pending.id) { MediaDetails(pending) } }
                    pendingLinkDetails?.let { pending -> key(pending.id) {
                        LinkDetails(pending.details, validate = { href, completion ->
                            pending.view.evaluateJavascript("window.WebeasyField.validLink(${JSONObject.quote(href)})") { result ->
                                completion(result == "true")
                            }
                        }, finish = { result ->
                            if (pendingLinkDetails?.id == pending.id) {
                                pendingLinkDetails = null
                                respond(pending.view, pending.id, result ?: JSONObject.NULL)
                            }
                        })
                    } }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingQRPhoto?.let { outState.putString("hostingQRPhoto", it.name) }
        outState.putInt("selectedTab", tab)
        super.onSaveInstanceState(outState)
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
        if (tab == 2) {
            Settings()
            return
        }
        if (tab == 4) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = if (hostingServer != null)
                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (hostingServer != null) "Hosting configured" else "No hosting added",
                            style = MaterialTheme.typography.headlineSmall)
                        hostingServer?.let { server ->
                            Text(server)
                            Text("Destination: $hostingDirectory")
                            Text("Saved securely on this device. The connection is checked when you publish.")
                        } ?: Text("Import your hosting setup QR. Pictures are decoded on this phone, not uploaded.")
                        if (hostingStatus.isNotEmpty()) Text(hostingStatus)
                    }
                }
                Text(if (hostingServer == null) "Add hosting" else "Replace hosting", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { qrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                    Text("Read QR from picture")
                }
                Button(onClick = ::photographQR, enabled = !busy) {
                    Text("Take a QR picture")
                }
                OutlinedButton(onClick = {
                    worker.execute {
                        runCatching { hosting.remove() }.fold(
                            onSuccess = { runOnUiThread {
                                loadPublishingSettings(JSONObject())
                                hostingStatus = "Hosting credentials removed."
                            } },
                            onFailure = { runOnUiThread { hostingStatus = "Cannot remove hosting credentials." } })
                    }
                }, enabled = !busy && hostingServer != null) { Text("Forget hosting") }
                if (advancedUI) OutlinedTextField(value = publicPathPrefix, onValueChange = { publicPathPrefix = it },
                    label = { Text("Public URL prefix (optional)") }, singleLine = true, enabled = !busy && hostingServer != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = uploadSubfolder, onValueChange = { uploadSubfolder = it },
                    label = { Text("Upload subfolder (optional)") }, singleLine = true, enabled = !busy && hostingServer != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth())
                Text("Use / for a website at the domain root, or /site/ for a website under that path. The upload subfolder is relative to the QR's destination; leave it blank to publish directly there. A blank public prefix uses relative website links.")
                Button(onClick = ::savePublishingSettings, enabled = ready && !busy && hostingServer != null && publishingSettingsChanged()) {
                    Text("Save publishing settings")
                }
                if (publishingSettingsChanged()) Text("Save publishing settings before publishing.")
                Button(onClick = { pendingPublish = true }, enabled = ready && !busy && hostingServer != null && !publishingSettingsChanged()) { Text("Publish website") }
                if (busy) CircularProgressIndicator()
                Text("Credentials and trusted SFTP keys are encrypted with Android Keystore. FTPS validates the server certificate; SFTP requires a trusted server key.")
            }
            return
        }
        if (tab == 3) {
            Changes()
            return
        }
        val key = if (tab == 0) "pages" else "news"
        val records = project?.optJSONArray(key)?.records() ?: emptyList()
        LazyColumn {
            if (tab == 1) item { OutlinedButton(onClick = { generate(true, path = "news/index.html") }) { Text("Latest three news items") } }
            items(records.size, key = { records[it].getString("id") }) { index ->
                RowEntry(records[index], project!!.getJSONArray(key), index, if (tab == 0) "page" else "news")
            }
            if (project != null) item {
                FilledTonalButton(onClick = {
                    val kind = if (tab == 0) "page" else "news"
                    val collection = project!!.getJSONArray(key)
                    val record = create(kind, collection); collection.put(record); changed(); screens.add(Screen(kind, record))
                }) { Text(if (tab == 0) "Add page" else "Add news") }
            }
        }
    }

    @Composable
    private fun Settings() {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Editor mode", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((advanced, name) in listOf(false to "Simple", true to "Advanced")) {
                    FilterChip(selected = advancedUI == advanced, enabled = !busy, onClick = {
                        advancedUI = advanced
                        getSharedPreferences("editor-ui", MODE_PRIVATE).edit().putBoolean("advanced", advanced).apply()
                    }, label = { Text(name) })
                }
            }
            Text("Simple hides sections, covers, template loading, and the public URL prefix. Advanced shows all options.")
            OutlinedButton(onClick = { screens.add(Screen("templates")) }, enabled = ready && !busy) { Text("Templates") }
        }
    }

    @Composable
    private fun Templates() {
        TemplateCatalogList(catalog, onSelect = { screens.add(Screen("template", it)) }) {
            if (advancedUI) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::loadServerTemplates, enabled = ready && !busy && project != null && hostingServer != null && !publishingSettingsChanged()) {
                        Text("Load templates from server")
                    }
                    Text("Publish once to create webeasy_templates on your hosting. Edit its HTML files, then load them here. Existing server templates are never replaced by publishing.")
                    if (busy) CircularProgressIndicator()
                }
            }
        }
    }

    @Composable
    private fun TemplateSource(record: JSONObject) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            OutlinedButton(onClick = {
                previewTemplate(record.getString("id"))
            }, enabled = ready && !busy) { Text("Preview template") }
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(record.getString("source"), fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    private fun loadServerTemplates() {
        if (!ready || busy || project == null || catalog == null) return
        val destination = runCatching { hosting.load() }.getOrNull()
        if (destination == null) { status = "Import your hosting setup QR first."; return }
        busy = true
        status = "Loading templates from server..."
        val currentCatalog = catalog!!
        generator.evaluateJavascript("window.WebeasyGenerator.templates(${JSONObject.quote(currentCatalog.toString())})") { serialized ->
            val expected = runCatching {
                val result = JSONObject(JSONTokener(serialized).nextValue() as String)
                check(!result.has("error")) { result.getString("error") }
                result.getJSONObject("sources").keys().asSequence().toList()
            }
            if (expected.isFailure) {
                status = expected.exceptionOrNull()?.message ?: "Cannot list templates."; busy = false
                return@evaluateJavascript
            }
            worker.execute {
                runCatching { SitePublisher.loadTemplates(destination, expected.getOrThrow()) }.fold(
                    onSuccess = { sources -> runOnUiThread {
                        val input = JSONObject().put("catalog", currentCatalog).put("sources", sources).put("project", project)
                        generator.evaluateJavascript("window.WebeasyGenerator.loadTemplates(${JSONObject.quote(input.toString())})") { response ->
                            val updated = runCatching {
                                val result = JSONObject(JSONTokener(response).nextValue() as String)
                                check(!result.has("error")) { result.getString("error") }
                                result.getJSONObject("catalog")
                            }
                            if (updated.isFailure) {
                                status = "Templates not loaded: ${updated.exceptionOrNull()?.message} Local templates have not changed."
                                busy = false
                            } else worker.execute {
                                runCatching { store.saveTemplateSources(sources) }.fold(
                                    onSuccess = { runOnUiThread {
                                        catalog = updated.getOrThrow(); busy = false
                                        status = "Templates loaded and saved locally. Previews, exports and the next publish use them."
                                    } },
                                    onFailure = { runOnUiThread {
                                        status = "Cannot save templates. Local templates have not changed."; busy = false
                                    } })
                            }
                        }
                    } },
                    onFailure = { error -> runOnUiThread {
                        busy = false
                        if (error is HostKeyApproval) {
                            hostKeyForTemplates = true; pendingHostKey = error
                            status = "Confirm the SFTP server key before loading templates."
                        } else status = error.message ?: "Cannot load templates."
                    } })
            }
        }
    }

    private fun previewTemplate(id: String) {
        if (!ready || busy || catalog == null) return
        busy = true
        val input = JSONObject().put("catalog", catalog).put("id", id).put("media", project?.optJSONArray("media"))
        generator.evaluateJavascript("window.WebeasyGenerator.previewTemplate(${JSONObject.quote(input.toString())})") { serialized ->
            val result = runCatching {
                val output = JSONObject(JSONTokener(serialized).nextValue() as String)
                check(!output.has("error")) { output.getString("error") }
                output
            }
            if (result.isFailure) {
                status = result.exceptionOrNull()?.message ?: "Cannot preview template."; busy = false
                return@evaluateJavascript
            }
            worker.execute {
                runCatching {
                    val output = result.getOrThrow()
                    store.prepareSite(output.getJSONArray("files"), output.getJSONArray("referencedMedia"))
                }.fold(onSuccess = { directory -> runOnUiThread {
                    busy = false
                    screens.add(Screen("preview", url = "$base/preview/index.html", directory = directory))
                } }, onFailure = { runOnUiThread {
                    busy = false; status = it.message ?: "Cannot preview template."
                } })
            }
        }
    }

    private suspend fun contentChanges(published: Boolean): JSONArray? {
        val baseline = (if (published) publishedContent else savedContent) ?: return null
        val input = JSONObject().put("before", JSONObject(baseline)).put("after", project)
        return suspendCancellableCoroutine { continuation ->
            generator.evaluateJavascript("window.WebeasyGenerator.changes(${JSONObject.quote(input.toString())})") { serialized ->
                if (continuation.isActive) continuation.resumeWith(runCatching {
                    val output = JSONObject(JSONTokener(serialized).nextValue() as String)
                    check(!output.has("error")) { output.getString("error") }
                    output.getJSONArray("changes")
                })
            }
        }
    }

    @Composable
    private fun Changes() {
        var published by remember { mutableStateOf(false) }
        var changes by remember { mutableStateOf(JSONArray()) }
        var message by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        LaunchedEffect(published, revision, savedContent, publishedContent, ready, busy) {
            changes = JSONArray()
            loading = false
            message = ""
            if (!ready || busy) {
                message = "Wait for the current operation to finish."
                return@LaunchedEffect
            }
            loading = true
            try {
                val result = contentChanges(published)
                changes = result ?: JSONArray()
                message = if (result == null)
                    "No published snapshot recorded yet. Publish successfully from this version to start tracking unpublished changes."
                else if (result.length() == 0)
                    if (published) "No unpublished content changes." else "No unsaved content changes."
                else ""
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = error.message ?: "Cannot compare content."
            }
            loading = false
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Show changes", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !published, onClick = { published = false }, label = { Text("Unsaved") })
                    FilterChip(selected = published, onClick = { published = true }, label = { Text("Unpublished") })
                }
                Text(if (published)
                    "Current content compared with the last successful publish recorded on this device. This does not inspect the server."
                else "Current content compared with the last local save.")
                Text("Content changes only; hosting settings are not included.")
                if (loading) CircularProgressIndicator()
                if (message.isNotEmpty()) Text(message)
            }
            items(changes.length(), key = { changes.getJSONObject(it).getString("id") }) { index ->
                val change = changes.getJSONObject(index)
                val isPageOrNews = change.optString("kind") in listOf("Page", "News")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(change.getString("title"), style = MaterialTheme.typography.titleLarge)
                    Text(change.getString("status"), style = MaterialTheme.typography.titleMedium)
                    if (isPageOrNews) {
                        SelectionContainer { Text("ID: ${change.getString("id")}", fontFamily = FontFamily.Monospace) }
                    }
                    change.getJSONArray("fields").records().forEach { field ->
                        Text(field.getString("name"), style = MaterialTheme.typography.titleMedium)
                        if (isPageOrNews && change.getString("status") != "Changed") {
                            val value = field.getString(if (change.getString("status") == "Added") "after" else "before")
                            SelectionContainer { Text(value.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace) }
                        } else {
                            Text("Before")
                            SelectionContainer { Text(field.getString("before").ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace) }
                            Text("After")
                            SelectionContainer { Text(field.getString("after").ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun RowEntry(record: JSONObject, collection: JSONArray, index: Int, kind: String) {
        Column {
            ListItem(headlineContent = { Text(if (kind == "page") record.optString("menuTitle", "Page")
                else record.optString("title").ifEmpty { record.optString("heading").ifEmpty { "Aside" } }) },
                modifier = Modifier.clickable { screens.add(Screen(kind, record)) })
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { collection.remove(index); changed() }) { Text("Remove") }
                OutlinedButton(onClick = {
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
                        OutlinedButton(onClick = { layoutDialog = true }) {
                            Text("Page layout: ${layouts.find { it.getString("id") == selected }?.getString("title") ?: selected}")
                        }
                    }
                    item {
                        val current = project!!.optString("homePageId") == record.getString("id")
                        OutlinedButton(onClick = { project!!.put("homePageId", record.getString("id")); changed() }, enabled = !current) {
                            Text(if (current) "Current homepage" else "Use as homepage")
                        }
                    }
                }
                item { NativeText(record, "slug", "Slug") }
                item { OutlinedButton(onClick = { dateDialog = true }) { Text("Date: ${record.getString("date")}") } }
            } else item { NativeText(record, "heading", if (screen.kind == "aside") "Heading (optional)" else "Heading") }
            if (screen.kind == "section") item { NativeText(record, "subheading", "Subheading (optional)") }
            if (advancedUI && screen.kind in listOf("page", "news")) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cover image (optional)", style = MaterialTheme.typography.titleMedium)
                    val coverId = record.optString("coverMediaId")
                    val media = project?.getJSONArray("media")?.records()?.find { it.getString("id") == coverId }
                    if (media != null) Text(media.getString("filename"))
                    OutlinedButton(onClick = { pendingMediaDetails = PendingMediaDetails("cover", "image", null, record) }, enabled = !busy) {
                        Text(if (coverId.isEmpty()) "Add cover image" else "Replace cover image")
                    }
                    if (coverId.isNotEmpty()) {
                        NativeText(record, "coverAlt", "Cover alternative text")
                        OutlinedButton(onClick = { record.put("coverMediaId", "").put("coverAlt", ""); changed() }, enabled = !busy) {
                            Text("Remove cover image")
                        }
                    }
                    Text("Covers must be at least 1200 pixels wide. Optional resizing and JPEG quality are available.")
                }
            }
            item {
                Text(if (screen.kind == "news") "Short content" else "Content")
                OutlinedButton(onClick = {
                    screens.add(Screen("content", record, contentKey = if (screen.kind == "news") "shortContent" else "content"))
                }) { Text(if (screen.kind == "news") "Edit short content" else "Edit content") }
            }
            if (screen.kind == "news") item {
                Text("Long content")
                OutlinedButton(onClick = { screens.add(Screen("content", record, contentKey = "content")) }) { Text("Edit long content") }
            }
            val related = buildList {
                if (advancedUI && screen.kind in listOf("page", "news")) add(Triple("sections", "Sections", "section"))
                if (screen.kind != "aside") add(Triple("asides", "Asides", "aside"))
                if (screen.kind == "page") add(Triple("children", "Subpages", "page"))
            }
            related.forEach { (key, label, kind) ->
                item { Text(label, style = MaterialTheme.typography.titleMedium) }
                val collection = array(record, key)
                items(collection.length(), key = { "$key-${collection.getJSONObject(it).getString("id")}" }) { index ->
                    RowEntry(collection.getJSONObject(index), collection, index, kind)
                }
                item { FilledTonalButton(onClick = {
                    val child = create(kind, collection); collection.put(child); changed(); screens.add(Screen(kind, child))
                }) { Text("Add ${if (key == "children") "subpage" else kind}") } }
            }
            if (screen.kind in listOf("page", "news")) item {
                OutlinedButton(onClick = { generate(true, path = if (screen.kind == "news")
                    "news/${record.getString("date")}/${record.getString("slug")}/index.html" else pagePath(record.getString("id"))) }) { Text("Preview") }
            }
        }
        if (layoutDialog) {
            AlertDialog(onDismissRequest = { layoutDialog = false }, title = { Text("Page layout") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { layouts.forEach { layout ->
                    OutlinedButton(onClick = { record.put("templateId", layout.getString("id")); changed(); layoutDialog = false },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(layout.getString("title"))
                    }
                } } },
                confirmButton = { OutlinedButton(onClick = { layoutDialog = false }) { Text("Cancel") } })
        }
        if (dateDialog) {
            val dateState = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(record.getString("date")).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { dateDialog = false },
                confirmButton = { Button(onClick = {
                    dateState.selectedDateMillis?.let { record.put("date", Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()); changed() }
                    dateDialog = false
                }) { Text("Select") } },
                dismissButton = { OutlinedButton(onClick = { dateDialog = false }) { Text("Cancel") } }) { DatePicker(state = dateState) }
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
        if (compile && destination != null) input.put("publicPathPrefix", destination.optString("publicPathPrefix"))
        generator.evaluateJavascript("window.WebeasyGenerator.${if (compile) "prepare" else "normalize"}(${JSONObject.quote(input.toString())})") { serialized ->
            val output = runCatching { JSONObject(JSONTokener(serialized).nextValue() as String) }
            if (output.isFailure || output.getOrNull()?.has("error") == true) {
                status = output.getOrNull()?.optString("error") ?: "Cannot generate content."; busy = false
                return@evaluateJavascript
            }
            val prepared = output.getOrThrow()
            worker.execute {
                runCatching {
                    val result = if (compile) NativeRenderer.compile(prepared) else prepared
                    if (!compile || export || publish) {
                        store.save(result.getJSONObject("project"))
                        runOnUiThread {
                            drafts.markSaved(savedSnapshot)
                            savedContent = result.getJSONObject("project").toString()
                        }
                    }
                    if (!compile) null else if (publish) {
                        val directory = store.prepareSite(result.getJSONArray("files"), result.getJSONArray("referencedMedia"))
                        try {
                            SitePublisher.publish(destination!!, directory)
                            try { store.savePublishedSnapshot(result.getJSONObject("project")) }
                            catch (error: Exception) { throw IllegalStateException("Website published, but its change snapshot could not be saved.", error) }
                            runOnUiThread { publishedContent = result.getJSONObject("project").toString() }
                        }
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
                    if (it is HostKeyApproval) { hostKeyForTemplates = false; pendingHostKey = it; status = "Confirm the SFTP server key before publishing." }
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
        fun pushValue(content: String) {
            value = content
            val options = JSONObject().put("paragraphsOnly", key == "shortContent").put("simple", !advancedUI)
            view.evaluateJavascript("window.WebeasyField.setValue(${JSONObject.quote(content)}, $options);", null)
        }
        @JavascriptInterface fun request(serialized: String) {
            val message = runCatching { JSONObject(serialized) }.getOrNull() ?: return
            runOnUiThread {
                val id = message.optString("id")
                val payload = message.optJSONObject("payload") ?: JSONObject()
                when (message.optString("method")) {
                    "ready" -> { ready = true; pushValue(record.optString(key)); respond(view, id, JSONObject().put("mediaDetails", true).put("linkDetails", true)) }
                    "change" -> { value = payload.getString("content"); record.put(key, value); changed(); respond(view, id) }
                    "resize" -> respond(view, id)
                    "pageLinks" -> respond(view, id, JSONObject().put("pages", project?.getJSONArray("pages") ?: JSONArray())
                        .put("homePageId", project?.opt("homePageId") ?: JSONObject.NULL))
                    "linkDetails" -> {
                        if (pendingLinkDetails != null || pendingMediaDetails != null) respond(view, id, error = "Cannot open link details.")
                        else pendingLinkDetails = PendingLinkDetails(id, view, payload)
                    }
                    "mediaDetails" -> {
                        val kind = payload.optString("kind")
                        if (key == "shortContent" || pendingMediaDetails != null || kind !in listOf("image", "video"))
                            respond(view, id, error = "Cannot open media details.")
                        else pendingMediaDetails = PendingMediaDetails(id, kind, view)
                    }
                    "pickMedia" -> {
                        val kind = payload.optString("kind")
                        if (key == "shortContent" || pendingMedia != null || kind !in listOf("image", "video")) respond(view, id, error = "Cannot open media picker.")
                        else {
                            pendingMedia = PendingMedia(id, kind, view, payload.optString("basename"),
                                if (payload.has("width")) payload.optInt("width") else null,
                                if (payload.has("quality")) payload.optInt("quality") else null)
                            mediaPicker.launch(PickVisualMediaRequest(if (kind == "image") ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.VideoOnly))
                        }
                    }
                    else -> respond(view, id, error = "Unknown content-field operation.")
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MediaDetails(pending: PendingMediaDetails) {
        var basename by remember { mutableStateOf("") }
        var alt by remember { mutableStateOf("") }
        var caption by remember { mutableStateOf("") }
        var width by remember { mutableStateOf(if (!advancedUI && pending.kind == "image" && pending.coverRecord == null) "400" else "") }
        var quality by remember { mutableStateOf(if (!advancedUI && pending.kind == "image" && pending.coverRecord == null) "85" else "") }
        var error by remember { mutableStateOf("") }
        fun finish(result: JSONObject? = null) {
            pendingMediaDetails = null
            pending.view?.let { respond(it, pending.id, result ?: JSONObject.NULL) }
            if (pending.coverRecord != null && result != null) {
                val options = result.getJSONObject("imageOptions")
                pendingMedia = PendingMedia("cover", "image", null, result.getString("basename"),
                    if (options.has("width")) options.getInt("width") else null,
                    if (options.has("quality")) options.getInt("quality") else null, pending.coverRecord, result.getString("alt"))
                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        BackHandler { finish() }
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(if (pending.coverRecord != null) "Cover image" else if (pending.kind == "image") "Image details" else "Video details",
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { OutlinedButton(onClick = { finish() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Cancel") } },
                    actions = { Button(contentPadding = PaddingValues(horizontal = 12.dp), onClick = {
                        val name = basename.trim()
                        if (name.isNotEmpty() && !name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) {
                            error = "Use up to 80 letters, numbers, hyphens, or underscores for the basename, without an extension."
                        } else {
                            val options = JSONObject()
                            val values = listOf(Triple("width", width, 16384), Triple("quality", quality, 100))
                            if (pending.kind == "image" && values.any { (_, value, limit) ->
                                value.isNotBlank() && (value.trim().toIntOrNull()?.let { it in 1..limit } != true)
                            }) error = "Image width must be 1-16384 pixels and JPEG quality 1-100%."
                            else if (pending.coverRecord != null && width.isNotBlank() && width.trim().toInt() < 1200)
                                error = "Cover width must be at least 1200 pixels."
                            else {
                                if (pending.kind == "image") values.forEach { (key, value, _) ->
                                    if (value.isNotBlank()) options.put(key, value.trim().toInt())
                                }
                                finish(JSONObject().put("basename", name).put("alt", alt).put("caption", caption).put("imageOptions", options))
                            }
                        }
                    }) { Text("Choose file") } })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(basename, { basename = it }, Modifier.fillMaxWidth(), label = { Text("File basename (optional)") }, singleLine = true)
                    Text("Leave blank for an automatic name. Use letters, numbers, hyphens, or underscores, without an extension. Duplicate names get a number.")
                    if (pending.kind == "image") {
                        OutlinedTextField(alt, { alt = it }, Modifier.fillMaxWidth(), label = { Text("Alternative text") })
                    }
                    if (pending.coverRecord == null) OutlinedTextField(caption, { caption = it }, Modifier.fillMaxWidth(), label = { Text("Caption (optional)") })
                    if (pending.kind == "image") {
                        Text("Image optimization (optional)", style = MaterialTheme.typography.titleMedium)
                        ImagePreset("Common image widths", width, listOf("320", "400", "640", "960", "1280", "1600", "1920", "2560")
                            .filter { pending.coverRecord == null || it.toInt() >= 1200 }, " px") { width = it }
                        OutlinedTextField(width, { width = it }, Modifier.fillMaxWidth(), label = { Text("Maximum width (pixels, optional)") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        ImagePreset("Common JPEG qualities", quality, listOf("60", "70", "75", "80", "85", "90", "95", "100"), "%") { quality = it }
                        OutlinedTextField(quality, { quality = it }, Modifier.fillMaxWidth(), label = { Text("JPEG quality (%, optional)") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Text("Leave both blank for normal import. Width preserves proportions and never enlarges images. Resized JPEG uses 85% quality unless specified. PNG remains lossless and keeps transparency. Animated images must be imported unchanged.")
                    } else Text("MP4 is recommended for videos.")
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ImagePreset(label: String, value: String, choices: List<String>, unit: String, onChange: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(value = if (value.isEmpty()) "None" else value + unit,
                onValueChange = {}, readOnly = true, label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("None") }, onClick = { onChange(""); expanded = false })
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice + unit) }, onClick = { onChange(choice); expanded = false })
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

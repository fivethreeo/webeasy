package no.webeasy.app

import org.json.JSONArray
import org.json.JSONObject

internal object NativeRenderer {
    private val library by lazy { System.loadLibrary("webeasy_renderer") }

    private external fun renderBytes(request: ByteArray): ByteArray
    private external fun generateBytes(request: ByteArray, compile: Boolean): ByteArray

    // JNI transfers UTF-8 bytes, not Java's modified UTF-8. Project text reaches
    // the same C validator/sanitizer/compiler used by Swift and browser WASM.
    fun generate(input: JSONObject, compile: Boolean): JSONObject {
        library
        val response = JSONObject(generateBytes(input.toString().toByteArray(Charsets.UTF_8), compile).toString(Charsets.UTF_8))
        require(!response.has("error")) { response.optString("error", "Cannot generate content.") }
        require(response.optJSONObject("project") != null) { "Invalid renderer response." }
        if (compile) {
            files(response)
            val media = response.optJSONArray("referencedMedia") ?: error("Invalid referenced media.")
            for (index in 0 until media.length()) require(media.opt(index) is String) { "Invalid referenced media." }
        }
        return response
    }

    fun render(request: JSONObject): JSONArray {
        library
        val response = renderBytes(request.toString().toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)
        return files(JSONObject(response))
    }

    internal fun files(response: JSONObject): JSONArray {
        require(!response.has("error")) { response.optString("error", "Cannot render website.") }
        val files = response.optJSONArray("files") ?: error("Invalid renderer response.")
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: error("Invalid rendered file.")
            require(file.opt("path") is String && file.opt("html") is String) { "Invalid rendered file." }
        }
        return files
    }

    fun compile(prepared: JSONObject): JSONObject {
        val rendered = render(prepared.getJSONObject("renderRequest"))
        val sources = files(JSONObject().put("files", prepared.getJSONArray("files")))
        for (index in 0 until sources.length()) rendered.put(sources.getJSONObject(index))
        return prepared.put("files", rendered).also { it.remove("renderRequest") }
    }
}

package no.webeasy.app

import org.json.JSONArray
import org.json.JSONObject

internal object NativeRenderer {
    private val library by lazy { System.loadLibrary("webeasy_renderer") }

    private external fun renderBytes(request: ByteArray): ByteArray

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

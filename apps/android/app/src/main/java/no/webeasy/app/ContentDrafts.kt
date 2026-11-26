package no.webeasy.app

import org.json.JSONArray
import org.json.JSONObject

class ContentDrafts {
    data class Snapshot(val records: Map<String, String>, val homePageId: String?)
    private var saved = Snapshot(emptyMap(), null)
    private val owned = listOf("children", "sections", "asides")

    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun home(project: JSONObject): String? = project.optString("homePageId").takeIf { !project.isNull("homePageId") && it.isNotEmpty() }
        ?: project.getJSONArray("pages").optJSONObject(0)?.getString("id")
    private fun serialize(record: JSONObject, home: String?): String =
        canonical(JSONObject(record.toString()).put("_homepage", record.getString("id") == home))

    fun capture(project: JSONObject): Snapshot {
        val records = mutableMapOf<String, String>()
        val home = home(project)
        fun collect(items: JSONArray) {
            for (index in 0 until items.length()) {
                val record = items.getJSONObject(index)
                records[record.getString("id")] = serialize(record, home)
                owned.forEach { record.optJSONArray(it)?.let(::collect) }
            }
        }
        collect(project.getJSONArray("pages")); collect(project.getJSONArray("news"))
        return Snapshot(records, home)
    }
    fun markSaved(snapshot: Snapshot) { saved = snapshot }
    fun isSaved(id: String): Boolean = id in saved.records
    fun hasChanges(record: JSONObject, project: JSONObject): Boolean =
        saved.records[record.getString("id")] != serialize(record, home(project))

    fun discard(record: JSONObject, project: JSONObject) {
        val id = record.getString("id")
        if (id == home(project) || id == saved.homePageId) project.put("homePageId", saved.homePageId ?: JSONObject.NULL)
        val data = saved.records[id]
        if (data != null) restore(record, JSONObject(data).apply { remove("_homepage") })
        else {
            fun remove(items: JSONArray) {
                for (index in items.length() - 1 downTo 0) {
                    val item = items.getJSONObject(index)
                    if (item.getString("id") == id) items.remove(index)
                    else owned.forEach { item.optJSONArray(it)?.let(::remove) }
                }
            }
            remove(project.getJSONArray("pages")); remove(project.getJSONArray("news"))
        }
    }
    private fun restore(target: JSONObject, source: JSONObject) {
        val children = owned.associateWith { key ->
            val items = target.optJSONArray(key) ?: JSONArray()
            (0 until items.length()).associate { items.getJSONObject(it).let { item -> item.getString("id") to item } }
        }
        target.keys().asSequence().toList().forEach(target::remove)
        source.keys().forEach { key ->
            if (key in owned) {
                val items = source.getJSONArray(key)
                val restored = JSONArray()
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val existing = children.getValue(key)[item.getString("id")]
                    if (existing != null) { restore(existing, item); restored.put(existing) } else restored.put(item)
                }
                target.put(key, restored)
            } else target.put(key, source.get(key))
        }
    }
}

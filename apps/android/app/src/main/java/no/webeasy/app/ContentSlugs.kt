package no.webeasy.app

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

internal object ContentSlugs {
    private val reserved = setOf("news", "assets", "media", "examples", "components", "editor", "provision", "catalog", "content", "index")

    fun fromTitle(title: String, fallback: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { fallback }

    private fun unique(base: String, taken: Set<String>): String {
        if (base.isEmpty()) return base
        var slug = base
        var suffix = 2
        while (slug in taken) slug = "$base-${suffix++}"
        return slug
    }

    fun generated(title: String, kind: String, record: JSONObject, siblings: List<JSONObject>, rootPage: Boolean): String {
        val taken = siblings.filter {
            it.optString("id") != record.optString("id") &&
                (kind != "news" || it.optString("date") == record.optString("date"))
        }.map { it.optString("slug") }.toMutableSet()
        if (rootPage) taken.addAll(reserved)
        return unique(fromTitle(title, if (kind == "news") "news" else "page"), taken)
    }

    fun update(record: JSONObject, kind: String?, key: String, value: String, automatic: Boolean,
               siblings: List<JSONObject> = emptyList(), rootPage: Boolean = false) {
        record.put(key, value)
        val source = when (kind) { "page" -> "menuTitle"; "news" -> "title"; else -> null }
        if (automatic && key == source) record.put("slug", generated(value, kind!!, record, siblings, rootPage))
    }

    fun resolve(records: List<JSONObject>, isSaved: (String) -> Boolean, rootPage: Boolean = false, news: Boolean = false): Boolean {
        var changed = false
        val groups = if (news) records.groupBy { it.optString("date") }.values else listOf(records)
        for (group in groups) {
            val taken = group.filter { isSaved(it.getString("id")) }.map { it.optString("slug") }.toMutableSet()
            if (rootPage) taken.addAll(reserved)
            for (record in group.filterNot { isSaved(it.getString("id")) }) {
                val previous = record.optString("slug")
                val slug = unique(previous, taken)
                if (slug != previous) { record.put("slug", slug); changed = true }
                taken.add(slug)
            }
        }
        return changed
    }
}

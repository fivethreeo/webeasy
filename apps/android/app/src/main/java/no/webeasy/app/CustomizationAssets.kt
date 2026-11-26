package no.webeasy.app

internal object CustomizationAssets {
    val bundled = listOf("assets/site.css", "assets/site.js", "assets/logo.svg", "assets/coast.svg")
    private val trusted = setOf("editor.css", "editor.js", "editor.js.LICENSE.txt", "field.css", "field.js",
        "field.js.LICENSE.txt", "generator.js", "generator.js.LICENSE.txt")
    const val maximumFileBytes = 16 * 1024 * 1024
    const val maximumTotalBytes = 64 * 1024 * 1024

    fun validPath(path: String): Boolean =
        path.startsWith("assets/") && path.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")) &&
            path.split("/").none { it.isEmpty() || it == "." || it == ".." } &&
            path.removePrefix("assets/") !in trusted

    fun validate(files: Map<String, ByteArray>, complete: Boolean = true) {
        require(files.size <= 1024 && files.all { (path, bytes) -> validPath(path) && bytes.size <= maximumFileBytes } &&
            files.values.sumOf { it.size.toLong() } <= maximumTotalBytes) { "Invalid or oversized website assets." }
        require(!complete || bundled.take(3).all(files::containsKey)) { "Include assets/site.css, assets/site.js and assets/logo.svg." }
    }
}

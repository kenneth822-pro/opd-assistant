package np.opd.speech

import np.opd.engine.J

/** One file of a model. A file can only be downloaded automatically if both [url] and [sha256] are pinned. */
data class ModelFileSpec(val name: String, val url: String?, val sha256: String?, val sizeBytes: Long?)

/** [verified] = someone actually read the licence on the official page. Never assumed. */
data class LicenseInfo(val name: String, val verified: Boolean, val nonCommercial: Boolean, val note: String)

/** Where the weights came from. [verified] = the repo and revision were checked against the official page. */
data class SourceInfo(val repo: String, val revision: String?, val verified: Boolean, val note: String)

/** One installable speech model, as described in `asr/models.json`. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val language: String,
    val backend: AsrBackend,
    /** "ggml-whisper" (whisper.cpp .bin) or, reserved for the NPU path, "qnn-dlc". */
    val format: String,
    val files: List<ModelFileSpec>,
    val source: SourceInfo,
    val license: LicenseInfo,
    val notes: String = ""
) {
    val primaryFile: ModelFileSpec get() = files.first()

    /** True only if every file has both a URL and a checksum: the app never installs unchecked downloads. */
    val downloadable: Boolean get() = files.all { !it.url.isNullOrBlank() && !it.sha256.isNullOrBlank() }
}

object ModelCatalog {

    private val hex64 = Regex("^[0-9a-fA-F]{64}$")

    fun parse(json: String): List<ModelSpec> {
        val root = J.parse(json) as? J.Obj ?: throw IllegalArgumentException("models.json is not an object")
        return root.objs("models").map { m ->
            val src = m.obj("source")
            val lic = m.obj("license")
            ModelSpec(
                id = m.req("id"),
                displayName = m.strOr("display_name", m.req("id")),
                language = m.strOr("language", "ne"),
                backend = AsrBackend.entries.firstOrNull { it.name == m.str("backend") } ?: AsrBackend.CPU_GGML,
                format = m.strOr("format", "ggml-whisper"),
                files = m.objs("files").map {
                    ModelFileSpec(it.req("name"), it.str("url")?.takeIf { u -> u.isNotBlank() }, it.str("sha256")?.lowercase()?.takeIf { h -> h.isNotBlank() }, it.str("size_bytes")?.toLongOrNull())
                },
                source = SourceInfo(src?.strOr("repo", "") ?: "", src?.str("revision")?.takeIf { it.isNotBlank() }, src?.bool("verified") ?: false, src?.strOr("note", "") ?: ""),
                license = LicenseInfo(lic?.strOr("name", "unknown") ?: "unknown", lic?.bool("verified") ?: false, lic?.bool("non_commercial") ?: true, lic?.strOr("note", "") ?: ""),
                notes = m.strOr("notes", "")
            )
        }
    }

    fun loadBundled(): List<ModelSpec> {
        val stream = ModelCatalog::class.java.getResourceAsStream("/asr/models.json")
            ?: throw IllegalStateException("asr/models.json is missing")
        return parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /** Problems with the manifest (empty = fine). Run by tests and at app start-up. */
    fun check(models: List<ModelSpec>): List<String> {
        val errors = mutableListOf<String>()
        if (models.isEmpty()) errors += "no models listed"
        val ids = HashSet<String>()
        for (m in models) {
            if (!ids.add(m.id)) errors += "${m.id}: duplicate id"
            if (m.files.isEmpty()) errors += "${m.id}: no files"
            if (m.format !in setOf("ggml-whisper", "qnn-dlc")) errors += "${m.id}: unknown format '${m.format}'"
            if (m.license.name.isBlank() || m.license.name == "unknown") errors += "${m.id}: licence must be stated"
            if (m.source.repo.isBlank()) errors += "${m.id}: source repo must be stated"
            for (f in m.files) {
                if (f.name.contains('/') || f.name.contains('\\') || f.name.contains("..")) errors += "${m.id}/${f.name}: file name must be a plain name"
                if (f.sha256 != null && !hex64.matches(f.sha256)) errors += "${m.id}/${f.name}: sha256 must be 64 hex characters"
                if (f.url != null && !f.url.startsWith("https://")) errors += "${m.id}/${f.name}: url must be https"
            }
        }
        return errors
    }
}

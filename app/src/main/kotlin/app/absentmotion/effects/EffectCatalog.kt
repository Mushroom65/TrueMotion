package app.absentmotion.effects

import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import app.absentmotion.R
import app.absentmotion.i18n.currentLanguage
import app.absentmotion.platform.Context
import app.absentmotion.ui.editor.matchesSearch
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

val EffectCategories: List<Pair<String, String>> = EffectCatalog.Categories.toList() + ("ai" to "AI")

data class EffectCatalog(
    val definitions: List<EffectDefinition> = emptyList(),
    val message: String? = null,
    private val open: ((String) -> InputStream)? = null,
    val newest: Set<String> = emptySet(),
    val categoryNames: Map<String, String> = emptyMap(),
) {
    fun find(id: String): EffectDefinition? = definitions.firstOrNull { it.id == id }

    fun isNew(effect: EffectDefinition): Boolean = effect.since in newest

    fun listed(category: String? = null): List<EffectDefinition> =
        definitions.filter { category == null || it.category == category || (category == "new" && isNew(it)) }

    fun search(query: String): List<EffectDefinition> {
        val byName = definitions.filter { matchesSearch(query, it.name) }.sortedBy { it.name.lowercase() }
        return byName + definitions.filter { it !in byName && matchesSearch(query, it.name, it.description) }.sortedBy { it.name.lowercase() }
    }

    val categories: List<Pair<String, String>> get() =
        ((if (definitions.any(::isNew)) listOf("new" to "New") else emptyList()) + EffectCategories)
            .filter { (key, _) -> !key.startsWith("amz-") || definitions.any { it.category == key } }
            .map { (k, v) -> k to (categoryNames[k] ?: v) }

    fun drawable(path: String?): Drawable? = path?.let { p ->
        runCatching { open?.invoke(p)?.use { ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(it.readBytes()))) } }.getOrNull()
    }

    fun loadInto(load: (String) -> String): EffectCatalog {
        val status = definitions.associate { it.id to load(it.runtimeXml()).substringBefore('\n') }
        return copy(definitions = definitions.map { it.copy(status = status.getValue(it.id)) })
    }

    companion object {
        val Categories = linkedMapOf("color" to "Color", "blur" to "Blur", "distort" to "Distort", "stylize" to "Stylize",
            "noise" to "Noise", "repeat" to "Repeat", "key" to "Key", "move" to "Move", "3d" to "3D", "text" to "Text", "true" to "True Motion",
            "amz-color" to "AfterMotionZ+ · Color & Light", "amz-blur" to "AfterMotionZ+ · Blur & Glow",
            "amz-distort" to "AfterMotionZ+ · Distortion", "amz-procedural" to "AfterMotionZ+ · Procedural",
            "amz-drawing" to "AfterMotionZ+ · Drawing & Edges", "amz-transform" to "AfterMotionZ+ · Transform",
            "amz-3d" to "AfterMotionZ+ · 3D", "amz-text" to "AfterMotionZ+ · Text",
            "amz-opacity" to "AfterMotionZ+ · Opacity", "amz-matte" to "AfterMotionZ+ · Matte & Key",
            "amz-repeat" to "AfterMotionZ+ · Repeat", "amz-other" to "AfterMotionZ+ · Other")

        private var cached: Pair<String, EffectCatalog>? = null

        @Synchronized private fun assets(context: Context, language: String): EffectCatalog {
            cached?.takeIf { it.first == language }?.let { return it.second }
            val app = context.applicationContext
            return parse(language) { app.assets.open("effects/$it") }.also {
                if (it.message == null) cached = language to it
            }
        }

        fun read(context: Context): EffectCatalog = assets(context, currentLanguage(context).tag)
            .let { catalog ->
                val prefs = context.getSharedPreferences("true-plugins", 0)
                if (catalog.message != null) catalog.copy(message = context.getString(R.string.effects_not_loaded))
                else catalog.copy(definitions = catalog.definitions.filter { it.pluginId == null || prefs.getBoolean(it.pluginId, false) })
            }

        fun parse(language: String = "en", open: (String) -> InputStream): EffectCatalog = runCatching {
            fun text(path: String) = open(path).bufferedReader().use { it.readText() }
            val keys = JSONArray(text("index.json"))
            val definitions = List(keys.length()) { keys.getString(it) }.mapNotNull { key ->
                runCatching { EffectDefinition.parse(JSONObject(text("$key.json"))) }.getOrNull()
            }
            val releases = runCatching { JSONArray(text("releases.json")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
            val catalog = EffectCatalog(definitions, open = open, newest = releases.takeLast(5).toSet())
            if (language == "en") catalog else runCatching { catalog.translated(JSONObject(text("i18n/$language.json"))) }.getOrDefault(catalog)
        }.getOrElse { EffectCatalog(message = "The effects could not be loaded") }
    }
}

fun EffectCatalog.translated(json: JSONObject): EffectCatalog {
    val effects = json.optJSONObject("effects") ?: JSONObject()
    val names = json.optJSONObject("categories")
    return copy(
        categoryNames = names?.keys()?.asSequence()?.associateWith { names.getString(it) }.orEmpty(),
        definitions = definitions.map { d -> effects.optJSONObject(d.key)?.let(d::translated) ?: d },
    )
}

private fun EffectDefinition.translated(json: JSONObject): EffectDefinition {
    val params = json.optJSONObject("params") ?: JSONObject()
    val presetNames = json.optJSONObject("presets") ?: JSONObject()
    return copy(
        name = json.optString("name").ifBlank { name },
        description = json.optString("description").ifBlank { description },
        params = this.params.map { p ->
            val t = params.optJSONObject(p.id) ?: return@map p
            val options = t.optJSONArray("options")?.let { a -> List(a.length()) { a.optString(it) } }
            p.copy(
                label = t.optString("label").ifBlank { p.label },
                section = t.optString("section").ifBlank { p.section },
                options = if (options != null && options.size == p.options.size && options.none { it.isBlank() }) options else p.options,
            )
        },
        presets = presets.map { it.copy(name = presetNames.optString(it.slug).ifBlank { it.name }) },
    )
}

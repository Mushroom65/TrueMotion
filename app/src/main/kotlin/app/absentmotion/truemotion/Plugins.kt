package app.absentmotion.truemotion

import android.content.Context
import app.absentmotion.compat.EffectMigration
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.*
import app.absentmotion.platform.readAtMost
import app.truemotion.port.PluginRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

data class TruePlugin(val manifest: JSONObject, val script: String) {
    val id: String get() = manifest.getString("id")
    fun label(value: Any?, language: String): String = (value as? JSONObject)?.let {
        it.optString(language.substringBefore('-'), it.optString("en", id))
    } ?: value?.toString().orEmpty()
    val commands: List<JSONObject> get() = manifest.optJSONArray("commands")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
    fun defaults(array: JSONArray?): JSONObject = JSONObject().also { out ->
        if (array != null) for (i in 0 until array.length()) {
            val p = array.getJSONObject(i)
            out.put(p.getString("key"), p.opt("default") ?: JSONObject.NULL)
        }
    }
}

object Plugins {
    private val properties = linkedMapOf("position" to PropRef.Location, "pivot" to PropRef.Pivot, "scale" to PropRef.Scale,
        "rotation" to PropRef.Rotation, "opacity" to PropRef.Opacity, "skew" to PropRef.Skew)

    fun bundled(context: Context): List<TruePlugin> = context.assets.list("true-plugins/bundled").orEmpty()
        .filter { it.endsWith(".tmplugin") }.map { name -> context.assets.open("true-plugins/bundled/$name").use(::read) }

    fun read(input: InputStream): TruePlugin {
        val files = HashMap<String, ByteArray>()
        var budget = 64 * 1024 * 1024
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val path = entry.name.replace('\\', '/')
                require(!path.startsWith('/') && ':' !in path && path.split('/').none { it == ".." || it == "." })
                require(files.size < 2048 && path !in files)
                val bytes = zip.readAtMost(minOf(budget, 16 * 1024 * 1024) + 1)
                budget -= bytes.size
                require(budget >= 0 && bytes.size <= 16 * 1024 * 1024)
                files[path] = bytes
            }
        }
        val manifest = JSONObject(files.getValue("plugin.json").toString(Charsets.UTF_8))
        require(manifest.getInt("api") in 1..2 && manifest.getString("id").matches(Regex("[a-z][a-z0-9_.-]{2,79}")))
        val source = files.getValue(manifest.optString("entry", "main.js"))
        require(source.size <= 262144)
        return TruePlugin(manifest, source.toString(Charsets.UTF_8))
    }

    fun snapshot(scene: Scene, selected: Long?, timeMs: Int): JSONObject = JSONObject()
        .put("width", scene.width).put("height", scene.height).put("fps", scene.fps).put("time", timeMs / 1000.0)
        .put("duration", scene.totalTime / 1000.0).put("selected", selected?.toString() ?: JSONObject.NULL)
        .put("layers", JSONArray(scene.layers.map { layer ->
            val at = layer.at(timeMs, scene.fps)
            val pose = JSONObject()
            val keys = JSONObject()
            for ((name, ref) in properties) {
                val fallback = when (ref) {
                    PropRef.Location, PropRef.Pivot -> floatArrayOf(0f, 0f, 0f)
                    PropRef.Scale -> floatArrayOf(1f, 1f)
                    PropRef.Opacity -> floatArrayOf(1f)
                    else -> floatArrayOf(0f)
                }
                pose.put(name, JSONArray(floats(at.element(ref)?.valueAt(at.t), fallback).map(Float::toDouble)))
                keys.put(name, JSONArray(layer.keyframes(ref).map { k -> JSONObject().put("time", k.t * (layer.endTime - layer.startTime) / 1000.0)
                    .put("value", JSONArray(floats(k.v, fallback).map(Float::toDouble))).also { if (k.e != null) it.put("easing", k.e) } }))
            }
            JSONObject().put("id", "${layer.id}").put("name", layer.label).put("type", layer.kind)
                .put("start", layer.startTime / 1000.0).put("end", layer.endTime / 1000.0).put("transform", pose).put("keys", keys)
        }))

    fun plan(scene: Scene, operations: JSONArray, timeMs: Int, catalog: EffectCatalog, migration: EffectMigration): Scene {
        require(operations.length() <= 10000)
        var result = scene
        val newIds = HashMap<String, Long>()
        fun number(value: Double): Float { require(value.isFinite() && kotlin.math.abs(value) <= 10_000_000); return value.toFloat() }
        fun values(value: Any): String = if (value is JSONArray) formatFloats(FloatArray(value.length()) { number(value.getDouble(it)) })
            else formatFloats(floatArrayOf(number((value as Number).toDouble())))
        fun transform(layer: Layer, property: String, keys: JSONArray): Layer {
            val ref = properties.getValue(property)
            val span = maxOf(1, layer.endTime - layer.startTime) / 1000.0
            require(keys.length() in 1..100000)
            var previous = -1.0
            val frames = List(keys.length()) { i ->
                val key = keys.getJSONObject(i)
                val seconds = key.optDouble("time", 0.0)
                require(seconds.isFinite() && seconds in 0.0..span && seconds > previous)
                previous = seconds
                val v = values(key.get("value"))
                val dimensions = v.split(',').size
                require(dimensions == when (property) { "position" -> 3; "pivot" -> dimensions.coerceIn(2, 3); "scale", "skew" -> 2; else -> 1 })
                Keyframe((seconds / span).toFloat(), v, key.optString("easing").takeIf { it.isNotEmpty() })
            }
            return layer.withElement(ref, XmlNode(ref.name).withKeyframes(frames))
        }
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val externalId = op.getString("id")
            if (op.getString("op") == "addShape") {
                require(externalId.startsWith("new:") && externalId !in newIds)
                val data = op.getJSONObject("data")
                val kind = data.optString("kind", "rectangle")
                require(kind in setOf("text", "path", "rectangle", "ellipse"))
                val id = Layer.newId()
                val start = (number(data.optDouble("start", timeMs / 1000.0)) * 1000).roundToInt()
                val end = (number(data.optDouble("end", start / 1000.0 + 2)) * 1000).roundToInt()
                require(start >= 0 && end > start)
                val width = number(data.optDouble("width", 100.0))
                val height = number(data.optDouble("height", 100.0))
                require(width > 0 && height > 0)
                val path = if (kind == "path") data.getString("path") else if (kind == "ellipse")
                    "M ${-width / 2},0 A ${width / 2},${height / 2} 0 1 0 ${width / 2},0 A ${width / 2},${height / 2} 0 1 0 ${-width / 2},0 Z"
                else "M0,0 L$width,0 L$width,$height L0,$height Z"
                var node = XmlNode(if (kind == "text") "text" else "shape", mapOf("id" to "$id", "startTime" to "$start", "endTime" to "$end",
                    "label" to data.optString("name", "Plugin"), "fillType" to "color"), children = listOf(
                    XmlNode("fillColor", mapOf("value" to data.optString("color", "#ffffffff"))),
                    if (kind == "text") XmlNode("content", text = data.optString("text", "Text")) else XmlNode("path", mapOf("d" to path))))
                if (kind == "text") node = node.with("size", "${number(data.optDouble("fontSize", 48.0))}")
                    .with("wrapWidth", "$width").with("align", "center").with("font", "googlefonts?name=Roboto&weight=400")
                val layer = Layer(node).withTransform("location", number(data.optDouble("x", scene.width / 2.0)), number(data.optDouble("y", scene.height / 2.0)), 0f)
                result = result.add(layer)
                newIds[externalId] = id
                continue
            }
            val id = newIds[externalId] ?: externalId.toLong()
            require(result.layer(id) != null)
            result = result.update(id) { layer -> when (op.getString("op")) {
                "rename" -> layer.withLabel(op.getString("name").take(512))
                "shift" -> {
                    val start = layer.startTime.toLong() + (number(op.getDouble("seconds")) * 1000).roundToInt()
                    require(start >= 0 && start + layer.endTime - layer.startTime <= Int.MAX_VALUE)
                    layer.movedTo(start.toInt())
                }
                "keys" -> transform(layer, op.getString("property"), op.getJSONArray("keys"))
                "transform" -> {
                    val data = op.getJSONObject("data")
                    data.keys().asSequence().fold(layer) { l, p -> transform(l, p, JSONArray().put(JSONObject().put("time", 0).put("value", data.get(p)))) }
                }
                "effect" -> {
                    val params = op.getJSONObject("params")
                    val effect = XmlNode("effect", mapOf("id" to op.getString("effect")), params.keys().asSequence().map { p ->
                        XmlNode("property", mapOf("name" to p, "value" to when (val value = params.get(p)) {
                            is Number, is JSONArray -> values(value)
                            else -> value.toString()
                        }))
                    }.toList())
                    val temporary = Scene(scene.node.copy(children = listOf(layer.copy(node = layer.node.copy(children = listOf(effect))).node)))
                    val migrated = migration.migrate(temporary).scene.layers.single().effects.single()
                    require(catalog.find(migrated["id"].orEmpty())?.available == true) { "Unsupported plugin effect: ${effect["id"]}" }
                    layer.addEffect(migrated)
                }
                else -> error("Unknown plugin operation: ${op.getString("op")}")
            } }
        }
        return result
    }

    fun run(plugin: TruePlugin, command: String, scene: Scene, selected: Long?, timeMs: Int, args: JSONObject): JSONObject =
        PluginRuntime.run(plugin.script, command, snapshot(scene, selected, timeMs), plugin.defaults(plugin.manifest.optJSONArray("settings")), args)
}

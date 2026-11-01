package app.absentmotion.truemotion

import app.absentmotion.audio.AUDIO_FX_TAG
import app.absentmotion.audio.AudioEnvelope
import app.absentmotion.audio.soundUri
import app.absentmotion.model.*
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.roundToInt

internal object AudioMotion {
    const val TAG = "trueAudioMotion"
    const val MAX_KEYS = 100_000
    enum class Mode { Envelope, Bop }
    enum class Property(val ref: PropRef, val default: String, val minimum: Float, val maximum: Float, val amount: Float) {
        Scale(PropRef.Scale, "1,1", -1f, 5f, .3f), Rotation(PropRef.Rotation, "0", -360f, 360f, 20f),
        Y(PropRef.Location, "0,0,0", -5000f, 5000f, -100f), Opacity(PropRef.Opacity, "1", 0f, 1f, 1f),
    }
    data class Settings(
        val property: Property = Property.Scale, val mode: Mode = Mode.Envelope,
        val source: Long? = null, val amount: Float = .3f,
        val band: AudioEnvelope.Band = AudioEnvelope.Band.Full, val gain: Float = 1f, val gate: Float = .03f,
        val attackMs: Float = 15f, val releaseMs: Float = 180f, val normalize: Boolean = true, val invert: Boolean = false,
        val decayMs: Float = 180f, val offsetMs: Float = 0f, val keyRate: Int = 30, val respectVolume: Boolean = true,
    ) {
        fun attrs(): Map<String, String> = linkedMapOf(
            "property" to property.name, "mode" to mode.name, "source" to (source?.toString() ?: "mix"),
            "amount" to amount.toString(), "band" to band.name, "gain" to gain.toString(), "gate" to gate.toString(),
            "attackMs" to attackMs.toString(), "releaseMs" to releaseMs.toString(), "normalize" to normalize.toString(),
            "invert" to invert.toString(), "decayMs" to decayMs.toString(), "offsetMs" to offsetMs.toString(),
            "keyRate" to keyRate.toString(), "respectVolume" to respectVolume.toString(),
        )
        companion object {
            fun read(attrs: Map<String, String>): Settings {
                val p = Property.entries.firstOrNull { it.name == attrs["property"] } ?: Property.Scale
                fun number(key: String, default: Float, low: Float, high: Float) = attrs[key]?.finiteFloat()?.coerceIn(low, high) ?: default
                return Settings(p, Mode.entries.firstOrNull { it.name == attrs["mode"] } ?: Mode.Envelope, attrs["source"]?.toLongOrNull(),
                    number("amount", p.amount, p.minimum, p.maximum), AudioEnvelope.Band.entries.firstOrNull { it.name == attrs["band"] } ?: AudioEnvelope.Band.Full,
                    number("gain", 1f, 0f, 8f), number("gate", .03f, 0f, .95f), number("attackMs", 15f, 0f, 1000f), number("releaseMs", 180f, 0f, 3000f),
                    attrs["normalize"] != "false", attrs["invert"] == "true", number("decayMs", 180f, 10f, 5000f), number("offsetMs", 0f, -3000f, 3000f),
                    attrs["keyRate"]?.toIntOrNull()?.coerceIn(0, 120) ?: 30, attrs["respectVolume"] != "false")
            }
        }
    }
    data class Profile(val signature: String, val versions: Map<String, String?>, val data: AudioEnvelope.Data, val beats: List<Int>, val spectrum: app.absentmotion.audio.AudioSpectrum.Data? = null)

    fun binding(layer: Layer, property: Property): XmlNode? = layer.node.children.firstOrNull { it.name == TAG && it["property"] == property.name && it["version"] == "1" }
    fun original(layer: Layer, property: Property): XmlNode = binding(layer, property)?.child("base")?.children?.singleOrNull()
        ?: layer.element(property.ref) ?: XmlNode(property.ref.name, mapOf("value" to property.default))

    /** A render-independent audio scene: visual edits and generated keys do not invalidate analysis. */
    fun sourceScene(scene: Scene, source: Long?, respectVolume: Boolean): Scene {
        fun strip(node: XmlNode): XmlNode = when {
            node.name == "scene" -> node.copy(children = node.children.filter { it.name in Scene.LAYER_TAGS || it.name == "media" }.map(::strip))
            node.name in Scene.LAYER_TAGS -> {
                val layer = Layer(node)
                val sound = layer.soundUri != null
                val chosen = source == null || source == layer.id
                val attrs = when { sound && !chosen -> node.attrs + ("hidden" to "true"); sound && source == layer.id && !respectVolume -> node.attrs - "hidden"; else -> node.attrs }
                node.copy(attrs = attrs, children = node.children.filter {
                    it.name == "scene" || it.name == "speedMap" || ((it.name == "gain" || it.name == AUDIO_FX_TAG) && !(source != null && chosen && !respectVolume))
                }.map(::strip))
            }
            else -> node
        }
        return Scene(strip(scene.node))
    }

    fun signature(scene: Scene, source: Long?, respectVolume: Boolean): String =
        MessageDigest.getInstance("SHA-256").digest(sourceScene(scene, source, respectVolume).node.with("modifiedTime", null).toXml().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun keyCount(layer: Layer, fps: Int, rate: Int): Int = ceil((layer.endTime - layer.startTime).coerceAtLeast(1) * minOf(if (rate == 0) fps else rate, fps) / 1000.0).toInt() + 1

    fun pulse(beats: List<Int>, timeMs: Double, decayMs: Float): Float {
        val found = beats.binarySearch(timeMs.toInt())
        val index = if (found >= 0) found else -found - 2
        if (index < 0 || beats[index] > timeMs) return 0f
        val falling = (1f - ((timeMs - beats[index]) / decayMs).toFloat()).coerceIn(0f, 1f)
        return falling * falling
    }

    fun bake(scene: Scene, layer: Layer, settings: Settings, response: AudioEnvelope.Response? = null): Layer {
        require(settings.mode != Mode.Bop || settings.property == Property.Scale)
        require(settings.mode != Mode.Envelope || response != null)
        require(settings.amount.isFinite() && settings.amount in settings.property.minimum..settings.property.maximum)
        require(settings.keyRate in 0..120 && settings.offsetMs.isFinite() && settings.decayMs.isFinite() && settings.decayMs > 0)
        val count = keyCount(layer, scene.fps, settings.keyRate)
        require(count <= MAX_KEYS) { "Too many keyframes: $count" }
        val rate = minOf(if (settings.keyRate == 0) scene.fps else settings.keyRate, scene.fps).coerceAtLeast(1)
        val span = (layer.endTime - layer.startTime).coerceAtLeast(1)
        val base = original(layer, settings.property)
        val beats = scene.bookmarks.sorted().distinct()
        val keys = (0 until count).map { i ->
            val local = minOf(span, (i * 1000.0 / rate).roundToInt())
            val t = local.toFloat() / span
            val time = layer.startTime + local - settings.offsetMs.toDouble()
            val signal = if (settings.mode == Mode.Bop) pulse(beats, time, settings.decayMs) else response!!.at(time)
            val values = floats(base.valueAt(t), floats(settings.property.default, floatArrayOf(0f)))
            when (settings.property) {
                Property.Scale -> for (c in values.indices) values[c] *= 1f + settings.amount * signal
                Property.Rotation -> values[0] += settings.amount * signal
                Property.Y -> if (values.size > 1) values[1] += settings.amount * signal
                Property.Opacity -> values[0] = (values[0] * (1f - settings.amount * (1f - signal))).coerceIn(0f, 1f)
            }
            Keyframe(t, formatFloats(values))
        }.distinctBy { it.t }
        val prior = binding(layer, settings.property)
        val hadProperty = prior?.get("hadProperty") ?: (layer.element(settings.property.ref) != null).toString()
        val hadTransform = prior?.get("hadTransform") ?: (layer.node.child("transform") != null && layer.node.children.none { it.name == TAG && it["hadTransform"] == "false" }).toString()
        val metadata = XmlNode(TAG, settings.attrs() + mapOf("version" to "1", "hadProperty" to hadProperty, "hadTransform" to hadTransform), listOf(XmlNode("base", children = listOf(base))))
        val changed = layer.withElement(settings.property.ref, base.withKeyframes(keys))
        return changed.copy(node = changed.node.copy(children = changed.node.children.filterNot { it.name == TAG && it["property"] == settings.property.name } + metadata))
    }

    fun restore(layer: Layer, property: Property): Layer {
        val binding = binding(layer, property) ?: return layer
        val restored = if (binding["hadProperty"] != "false") layer.withElement(property.ref, original(layer, property)) else {
            val transform = layer.node.child("transform")
            val kept = transform?.copy(children = transform.children.filterNot { it.name == property.ref.name })
            layer.copy(node = layer.node.withChild("transform", kept?.takeUnless { binding["hadTransform"] == "false" && it.children.isEmpty() && it.attrs.isEmpty() }))
        }
        return detach(restored, property)
    }
    fun detach(layer: Layer, property: Property): Layer = layer.copy(node = layer.node.copy(children = layer.node.children.filterNot { it.name == TAG && it["property"] == property.name }))

    fun addMarkers(scene: Scene, times: List<Int>): Scene {
        val existing = scene.bookmarks.toMutableList()
        for (ms in times.filter { it in 0..scene.totalTime }.sorted()) {
            val time = scene.frameStart((ms * scene.fps / 1000.0).roundToInt().coerceIn(0, scene.lastFrame))
            if (existing.none { kotlin.math.abs(it - time) < 1000.0 / scene.fps }) existing += time
        }
        return scene.withBookmarks(existing.sorted())
    }

    fun grid(startMs: Int, endMs: Int, bpm: Float, offsetMs: Float): List<Int> {
        require(bpm.isFinite() && bpm in 20f..400f && offsetMs.isFinite())
        val step = 60_000.0 / bpm
        val first = startMs + offsetMs
        val from = ceil((startMs - first) / step).toInt()
        val count = ceil((endMs - first) / step).toInt() - from
        require(count.coerceAtLeast(0) <= MAX_KEYS)
        return (from until from + count.coerceAtLeast(0)).map { (first + it * step).roundToInt() }.filter { it in startMs until endMs }
    }
}

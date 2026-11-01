package app.absentmotion.truemotion

import app.absentmotion.audio.AudioEnvelope
import app.absentmotion.model.*
import java.security.MessageDigest

/** Persistent dependency configuration. Sample buffers belong to the renderer, never to project keys. */
internal object AudioReactive {
    const val ID = "lab.true.audioMotion"
    data class Signal(val key: String, val response: AudioEnvelope.Response)
    data class Prepared(val input: Scene, val scene: Scene, val signals: List<Signal>)

    fun settings(effect: XmlNode): AudioMotion.Settings {
        val saved = AudioMotion.Settings.read(effect.attrs)
        val target = effect.effectProperty("target")?.get("value")?.toIntOrNull()
        val property = AudioMotion.Property.entries.getOrNull(target ?: saved.property.ordinal) ?: AudioMotion.Property.Scale
        return saved.copy(property = property,
            amount = effect.effectProperty("amount")?.get("value")?.finiteFloat()?.coerceIn(property.minimum, property.maximum) ?: saved.amount,
            offsetMs = effect.effectProperty("offsetMs")?.get("value")?.finiteFloat()?.coerceIn(-3000f, 3000f) ?: saved.offsetMs,
            mode = AudioMotion.Mode.Envelope)
    }

    fun index(layer: Layer, property: AudioMotion.Property): Int = layer.effects.indexOfFirst { it["id"] == ID && settings(it).property == property }

    fun put(layer: Layer, settings: AudioMotion.Settings, index: Int = index(layer, settings.property)): Layer {
        val base = AudioMotion.restore(layer, settings.property)
        val prior = base.effects.getOrNull(index)?.takeIf { it["id"] == ID }
        val props = listOf("target" to settings.property.ordinal.toString(), "amount" to settings.amount.toString(), "offsetMs" to settings.offsetMs.toString())
            .map { (name, value) -> XmlNode("property", mapOf("name" to name, "type" to "float", "value" to value)) }
        val node = XmlNode("effect", prior?.attrs.orEmpty().minus("signalKey") + settings.attrs() + mapOf("id" to ID, "trueAudioVersion" to "1"), props)
        return if (prior == null) base.addEffect(node) else base.withEffect(index) { node }
    }

    fun remove(layer: Layer, property: AudioMotion.Property): Layer = index(layer, property).let { if (it < 0) layer else layer.removeEffect(it) }
    fun active(node: XmlNode): Boolean = node.name == "effect" && node["id"] == ID && node["hidden"] != "true"
    fun has(node: XmlNode): Boolean = active(node) || Spectrum.active(node) || node.children.any(::has)

    fun responseKey(profileKey: String, settings: AudioMotion.Settings): String = hash(listOf(profileKey, settings.band.name, settings.gain,
        settings.gate, settings.attackMs, settings.releaseMs, settings.normalize, settings.invert).joinToString("|"))

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Visits every composition in its own local clock, including retimed groups. */
    fun configurations(scene: Scene): List<Pair<Scene, AudioMotion.Settings>> {
        val found = mutableListOf<Pair<Scene, AudioMotion.Settings>>()
        fun visit(node: XmlNode, composition: Scene) {
            val local = if (node.name == "scene") Scene(node) else composition
            if (active(node)) found += local to settings(node)
            node.children.forEach { visit(it, local) }
        }
        visit(scene.node, scene)
        return found
    }

    fun bind(scene: Scene, key: (Scene, AudioMotion.Settings) -> String?): Scene {
        fun visit(node: XmlNode, composition: Scene): XmlNode {
            val local = if (node.name == "scene") Scene(node) else composition
            val attrs = if (active(node)) node.with("signalKey", key(local, settings(node))).attrs else node.attrs
            return node.copy(attrs = attrs, children = node.children.map { visit(it, local) })
        }
        return Scene(visit(scene.node, scene))
    }
}

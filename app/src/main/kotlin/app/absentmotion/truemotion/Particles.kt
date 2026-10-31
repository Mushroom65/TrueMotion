package app.absentmotion.truemotion

import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*

object Particles {
    const val ID = "lab.true.particles"
    const val LEGACY = "lab.toji.particles3d"
    fun isEffect(node: XmlNode) = node["id"] in setOf(ID, LEGACY)
    val sections = listOf(
        "dimension amount life lifeRandom timeScale timeOffset seed emitter originX originY originZ spawnX spawnY spawnZ",
        "velocityX velocityY velocityZ spread directionSpread velocityRandom speedStart speedEnd",
        "gravityX gravity gravityZ windX windY windZ forceRandom drag turbulence noiseFrequency attractorX attractorY attractorZ attractStrength attractRadius",
        "radius sizeStart sizeEnd lifeCurve variation tint colorStart colorEnd colorRandom opacityStart opacityEnd opacityRandom softFade rotation rotationRandom rotationSpeed spinRandom alignVelocity useTexture additive spriteAspect textureResolution",
        "cameraX cameraY cameraZ camRotationX camRotationY camRotationZ fov worldScale",
    ).map { it.split(' ').toSet() }

    fun create(scene: Scene, timeMs: Int, definition: EffectDefinition, label: String): Layer {
        require(definition.id == ID)
        val start = scene.frameStart(scene.frameAt(timeMs.coerceAtLeast(0)))
        val end = maxOf(scene.totalTime, scene.endTimeFor(scene.frameAt(start), 4000))
        return Layer(XmlNode("shape", mapOf("id" to "${Layer.newId()}", "label" to label, "s" to ".rect",
            "startTime" to "$start", "endTime" to "$end", "fillType" to "color"), listOf(
            XmlNode("transform", children = listOf(XmlNode("location", mapOf("value" to "${scene.width/2f},${scene.height/2f},0")))),
            XmlNode("property", mapOf("name" to "size", "type" to "vec2", "value" to "100,100")),
            XmlNode("fillColor", mapOf("value" to "#ffffffff")),
            definition.instance().withEffectProperty("worldScale", "float", "${minOf(scene.width, scene.height)/2f}", 0f, 0f..0f),
        )))
    }

    fun source(effect: XmlNode): Long = effect.effectProperty("sourceLayer")?.get("value")?.toLongOrNull() ?: 0

    fun canSource(scene: Scene, emitter: Long, source: Long): Boolean {
        if (source == 0L) return true
        val layer = scene.layer(source) ?: return false
        if (layer.kind in setOf("audio", "camera", "nullobj")) return false
        val visiting = mutableSetOf<Long>()
        fun reaches(id: Long, depth: Int): Boolean {
            if (id == emitter || depth >= 8) return true
            if (!visiting.add(id)) return true
            val cyclic = scene.layer(id)?.effects.orEmpty().filter { isEffect(it) && it["hidden"] != "true" }
                .map(::source).filter { it != 0L }.any { reaches(it, depth+1) }
            visiting.remove(id)
            return cyclic
        }
        return !reaches(source, 0)
    }

    fun withSource(layer: Layer, index: Int, id: Long, aspect: Float? = null): Layer = layer.withEffect(index) { effect ->
        var next = effect.withEffectProperty("sourceLayer", "string", "$id", 0f, 0f..0f)
            .withEffectProperty("useTexture", "bool", (id != 0L || layer.mediaFill != null).toString(), layer.t, layer.snap)
        if (aspect != null && aspect.isFinite() && aspect > 0) next = next.withEffectProperty("spriteAspect", "float", "${aspect.coerceIn(.01f,100f)}", layer.t, layer.snap)
        next
    }

    /** Decoding-only view: reveal referenced video/embedded sources without changing
     * the rendered project or unmuting its audio. Resolve closure once per scene. */
    fun mediaScene(scene: Scene): Scene {
        val required = mutableSetOf<Long>()
        fun refs(layer: Layer): List<Long> = layer.effects.filter { effect ->
            isEffect(effect) && effect["hidden"] != "true" && effect.effectProperty("useTexture")?.let { p ->
                p["value"] == "true" || p["value"] == "1" || p.keyframes.any { it.v in setOf("true", "1") }
            } == true
        }.map(::source).filter { it != 0L }
        var todo = scene.layers.filterNot { it.hidden }.flatMap(::refs)
        while (todo.isNotEmpty()) {
            val added = todo.filter { required.add(it) }
            todo = added.mapNotNull(scene::layer).flatMap(::refs)
        }
        var changed = false
        val layers = scene.layers.map { layer ->
            var next = layer
            if (layer.id in required && layer.hidden) next = next.withHidden(false)
            layer.nested?.let { nested ->
                val inner = mediaScene(nested)
                if (inner != nested) next = next.copy(node = next.node.withChild("scene", inner.node))
            }
            if (next != layer) changed = true
            next
        }
        if (!changed) return scene
        var index = 0
        return Scene(scene.node.copy(children = scene.node.children.map { if (it.name in Scene.LAYER_TAGS) layers[index++].node else it }))
    }
}

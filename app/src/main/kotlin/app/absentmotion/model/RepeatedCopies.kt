package app.absentmotion.model

import kotlin.math.roundToInt

data class CopyOffset(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val rotation: Float = 0f,
    val scaleX: Float = 1f, val scaleY: Float = 1f, val opacity: Float = 1f, val delayFrames: Float = 0f) {
    internal fun node(source: Long) = XmlNode("trueCopy", mapOf("source" to "$source", "x" to "$x", "y" to "$y", "z" to "$z",
        "rotation" to "$rotation", "scaleX" to "$scaleX", "scaleY" to "$scaleY", "opacity" to "$opacity", "delayFrames" to "$delayFrames"))
}
val Layer.copySource: Long? get() = node.child("trueCopy")?.get("source")?.toLongOrNull()
val Layer.copyOffset: CopyOffset? get() = node.child("trueCopy")?.let { n ->
    fun f(name: String, fallback: Float = 0f) = n[name]?.finiteFloat() ?: fallback
    CopyOffset(f("x"), f("y"), f("z"), f("rotation"), f("scaleX", 1f), f("scaleY", 1f), f("opacity", 1f), f("delayFrames"))
}
fun Scene.repeatedCopies(source: Long): List<Layer> = layers.filter { it.copySource == source }

/** The original remains editable; generated copies keep their own independent offsets. */
fun Scene.withRepeatedCopies(sourceId: Long, count: Int, step: CopyOffset = CopyOffset(x = 100f)): Scene {
    require(count in 1..200)
    require(listOf(step.x, step.y, step.z, step.rotation, step.scaleX, step.scaleY, step.opacity, step.delayFrames).all(Float::isFinite))
    val source = requireNotNull(layer(sourceId))
    require(source.kind !in setOf("camera", "audio", "bone", "nullobj") && source.copySource == null)
    val existing = repeatedCopies(sourceId)
    var result = this
    for (extra in existing.drop(count - 1)) result = result.remove(extra.id)
    for (index in existing.size until count - 1) {
        val i = index + 1
        val offset = CopyOffset(step.x * i, step.y * i, step.z * i, step.rotation * i,
            1f + (step.scaleX - 1f) * i, 1f + (step.scaleY - 1f) * i, (1f + (step.opacity - 1f) * i).coerceIn(0f, 1f), step.delayFrames * i)
        val copy = Layer(source.node.with("id", "${Layer.newId()}").with("label", "${source.label} ${i + 1}").withChild("trueCopy", offset.node(sourceId)))
        result = result.addAbove(copy, result.repeatedCopies(sourceId).lastOrNull()?.id ?: sourceId)
    }
    return result.refreshRepeatedCopies()
}

fun Scene.withCopyOffset(id: Long, offset: CopyOffset): Scene {
    val source = layer(id)?.copySource ?: return this
    val values = listOf(offset.x, offset.y, offset.z, offset.rotation, offset.scaleX, offset.scaleY, offset.opacity, offset.delayFrames)
    require(values.all(Float::isFinite) && offset.opacity in 0f..1f)
    return update(id) { it.copy(node = it.node.withChild("trueCopy", offset.node(source).copy(children = it.node.child("trueCopy")!!.children))) }.refreshRepeatedCopies()
}

fun Scene.detachRepeatedCopy(id: Long): Scene = update(id) { layer ->
    fun detach(node: XmlNode): XmlNode = node.copy(children = node.children.filterNot { it.name in setOf("trueCopy", "trueValueLink", "trueLinkBase") }.map(::detach))
    layer.copy(node = detach(layer.node))
}

fun Scene.refreshRepeatedCopies(): Scene {
    var result = this
    for (copy in layers.filter { it.copySource != null }) {
        val source = layer(copy.copySource!!)
        if (source == null || source.copySource != null) { result = result.detachRepeatedCopy(copy.id); continue }
        val offset = copy.copyOffset!!
        val start = source.startTime.toLong() + (offset.delayFrames * 1000.0 / fps).roundToInt()
        require(start >= 0 && start + source.endTime - source.startTime <= Int.MAX_VALUE) { "Copy time is outside the timeline" }
        val metadata = copy.node.child("trueCopy")!!
        val idMap = metadata.children.filter { it.name == "trueCopyId" }.mapNotNull { n ->
            n["from"]?.toLongOrNull()?.let { from -> n["to"]?.toLongOrNull()?.let { from to it } }
        }.toMap().toMutableMap().apply { put(source.id, copy.id) }
        // copyLayers walks IDs in document order. Keep the mapping stable between edits.
        val sourceIds = linkedSetOf<Long>()
        fun collect(n: XmlNode) {
            if (n.name in Scene.LAYER_TAGS || n.name == "bone") n["id"]?.toLongOrNull()?.let(sourceIds::add)
            n.children.forEach(::collect)
        }
        collect(source.node)
        val ids = sourceIds.map { old -> idMap.getOrPut(old, Layer::newId) }.iterator()
        var rebuilt = copyLayers(listOf(source), preserveOutsideParents = true, newId = { ids.next() }).single().movedTo(start.toInt())
            .withLabel(copy.label)
        fun adjust(ref: PropRef, add: FloatArray, multiply: FloatArray) {
            val old = rebuilt.element(ref) ?: XmlNode(ref.name, mapOf("value" to formatFloats(ValueChannel(ref).defaults())))
            fun shifted(text: String?): String {
                val v = floats(text, ValueChannel(ref).defaults())
                return formatFloats(FloatArray(maxOf(v.size, add.size, multiply.size)) { i ->
                    (v.getOrNull(i) ?: 0f) * (multiply.getOrNull(i) ?: 1f) + (add.getOrNull(i) ?: 0f)
                })
            }
            val clean = old.copy(children = old.children.filterNot { it.name in setOf("trueValueLink", "trueLinkBase") })
            val changed = if (old.isKeyframed) clean.withKeyframes(old.keyframes.map { it.copy(v = shifted(it.v)) }) else clean.with("value", shifted(old["value"]))
            rebuilt = rebuilt.withElement(ref, changed)
        }
        adjust(PropRef.Location, floatArrayOf(offset.x, offset.y, offset.z), floatArrayOf())
        adjust(PropRef.Rotation, floatArrayOf(-offset.rotation), floatArrayOf())
        adjust(PropRef.Scale, floatArrayOf(), floatArrayOf(offset.scaleX, offset.scaleY))
        adjust(PropRef.Opacity, floatArrayOf(), floatArrayOf(offset.opacity))
        val mapping = idMap.filterKeys { it in sourceIds }.map { (from, to) -> XmlNode("trueCopyId", mapOf("from" to "$from", "to" to "$to")) }
        rebuilt = rebuilt.copy(node = rebuilt.node.withChild("trueCopy", offset.node(source.id).copy(children = mapping)))
        result = result.update(copy.id) { rebuilt }
    }
    return result
}

fun Scene.refreshMotionTools(): Scene = refreshValueLinks().refreshRepeatedCopies()

/** Viewport/Transform edits on a copy edit its offsets, rather than being overwritten. */
fun Scene.captureCopyTransformEdits(before: Scene, timeMs: Int): Scene {
    var result = this
    for (copy in layers) {
        val sourceId = copy.copySource ?: continue
        val old = before.layer(copy.id) ?: continue
        if (old.node.child("trueCopy") != copy.node.child("trueCopy")) continue
        val source = layer(sourceId) ?: continue
        if (before.layer(sourceId)?.node != source.node) continue
        val offset = copy.copyOffset ?: continue
        if (copy.endTime - copy.startTime != old.endTime - old.startTime) {
            result = result.detachRepeatedCopy(copy.id); continue
        }
        var changed = offset
        val at = copy.at(timeMs, fps); val was = old.at(timeMs, fps)
        if (copy.element(PropRef.Location) != old.element(PropRef.Location)) {
            val a = at.location; val b = was.location
            changed = changed.copy(x = offset.x + a[0] - b[0], y = offset.y + a[1] - b[1], z = offset.z + a[2] - b[2])
        }
        if (copy.element(PropRef.Rotation) != old.element(PropRef.Rotation)) changed = changed.copy(rotation = offset.rotation + at.rotation - was.rotation)
        val original = source.at(timeMs - (offset.delayFrames * 1000f / fps).roundToInt(), fps)
        if (copy.element(PropRef.Scale) != old.element(PropRef.Scale)) {
            val s = original.scale
            changed = changed.copy(scaleX = if (s[0] != 0f) at.scale[0] / s[0] else offset.scaleX, scaleY = if (s[1] != 0f) at.scale[1] / s[1] else offset.scaleY)
        }
        if (copy.element(PropRef.Opacity) != old.element(PropRef.Opacity) && original.opacity != 0f) changed = changed.copy(opacity = (at.opacity / original.opacity).coerceIn(0f,1f))
        if (copy.startTime != old.startTime) changed = changed.copy(delayFrames = offset.delayFrames + (copy.startTime - old.startTime) * fps / 1000f)
        if (changed != offset) result = result.update(copy.id) { it.copy(node = it.node.withChild("trueCopy", changed.node(sourceId).copy(children = copy.node.child("trueCopy")!!.children))) }
    }
    return result
}

fun Scene.refreshMotionTree(): Scene {
    val nested = node.children.map { n ->
        if (n.name != "embedScene") n else n.child("scene")?.let { inner -> n.withChild("scene", Scene(inner).refreshMotionTree().node) } ?: n
    }
    return Scene(node.copy(children = nested)).refreshMotionTools()
}

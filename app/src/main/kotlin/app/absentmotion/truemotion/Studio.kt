package app.absentmotion.truemotion

import app.absentmotion.model.*
import app.truemotion.port.ExpressionMath
import kotlin.math.*

object Studio {
    private val transforms = setOf("location", "pivot", "scale", "rotation", "opacity", "skew", "tilt")

    fun array(scene: Scene, id: Long, count: Int, columns: Int, dx: Float, dy: Float,
              ring: Boolean, radius: Float, staggerFrames: Float, orient: Boolean): Scene {
        require(count in 2..1000 && columns > 0)
        require(listOf(dx, dy, radius, staggerFrames).all(Float::isFinite))
        val source = scene.layer(id) ?: return scene
        var result = scene
        var below = id
        for (i in 1 until count) {
            val angle = 2.0 * PI * i / count
            val x = if (ring) (cos(angle) - 1).toFloat() * radius else (i % columns) * dx
            val y = if (ring) sin(angle).toFloat() * radius else (i / columns) * dy
            val turn = if (ring && orient) -Math.toDegrees(angle).toFloat() else 0f
            val start = source.startTime.toLong() + (i * staggerFrames * 1000.0 / scene.fps).roundToLong()
            require(start >= 0 && start + source.endTime - source.startTime <= Int.MAX_VALUE)
            var copy = copyLayers(listOf(source), preserveOutsideParents = true).single()
                .withLabel("${source.label} ${i + 1}").movedTo(start.toInt())
            copy = offset(copy, "location", floatArrayOf(x, y, 0f))
            if (turn != 0f) copy = offset(copy, "rotation", floatArrayOf(turn))
            result = result.addAbove(copy, below)
            below = copy.id
        }
        return result
    }

    private fun offset(layer: Layer, name: String, delta: FloatArray): Layer {
        val tf = layer.node.child("transform") ?: XmlNode("transform")
        val prop = tf.child(name) ?: XmlNode(name, mapOf("value" to if (name == "location") "0,0,0" else "0"))
        fun moved(value: String): String = formatFloats(floats(value, FloatArray(delta.size)).let { v ->
            FloatArray(maxOf(v.size, delta.size)) { (v.getOrNull(it) ?: 0f) + (delta.getOrNull(it) ?: 0f) }
        })
        val shifted = prop.copy(attrs = prop.attrs.mapValues { (k, v) -> if (k == "value") moved(v) else v },
            children = prop.children.map { if (it.name == "kf" && it["v"] != null) it.with("v", moved(it["v"]!!)) else it })
        return layer.copy(node = layer.node.withChild("transform", tf.withChild(name, shifted)))
    }

    fun reverseKeys(layer: Layer): Layer {
        fun visit(node: XmlNode): XmlNode {
            val keys = node.keyframes
            if (keys.isEmpty()) return node.copy(children = node.children.map { if (it.name == "scene") it else visit(it) })
            val reversed = keys.indices.reversed().map { i ->
                val key = if (node.name == "location") keys[i].reversedPathHandles() else keys[i]
                key.copy(t = 1f - keys[i].t, e = keys.getOrNull(i + 1)?.e?.let { Easing.parse(it).reversed()?.toString() })
            }
            return node.withKeyframes(reversed)
        }
        return layer.copy(node = visit(layer.node))
    }

    fun pasteTransform(source: Layer, target: Layer, fit: Boolean, relative: Boolean): Layer {
        val src = source.node.child("transform") ?: return target
        val dst = target.node.child("transform") ?: XmlNode("transform")
        val ratio = if (fit) 1f else (source.endTime - source.startTime).toFloat() / maxOf(1, target.endTime - target.startTime)
        var result = dst.with("trueAutoOrient", src["trueAutoOrient"])
        for (p in src.children.filter { it.name in transforms }) {
            var value = p
            if (p.keyframes.isNotEmpty()) {
                require(p.keyframes.all { it.t * ratio in 0f..1f })
                value = p.withKeyframes(p.keyframes.map { it.copy(t = it.t * ratio) })
            }
            if (relative && p.name == "location") {
                val from = floats(p.valueAt(0f), floatArrayOf(0f, 0f, 0f))
                val to = target.location
                val layer = Layer(XmlNode("nullobj", children = listOf(XmlNode("transform", children = listOf(value)))))
                value = offset(layer, p.name, FloatArray(3) { to[it] - (from.getOrNull(it) ?: 0f) })
                    .node.child("transform")!!.child(p.name)!!
            }
            result = result.withChild(p.name, value)
        }
        return target.copy(node = target.node.withChild("transform", result))
    }

    fun sequence(scene: Scene, ids: Set<Long>, gapFrames: Float): Scene {
        require(gapFrames.isFinite())
        var cursor = scene.layers.filter { it.id in ids }.minOfOrNull { it.startTime }?.toDouble() ?: return scene
        return scene.withLayers(scene.layers.map { layer ->
            if (layer.id !in ids) layer else {
                require(cursor >= 0 && cursor + layer.endTime - layer.startTime <= Int.MAX_VALUE)
                layer.movedTo(cursor.roundToInt()).also {
                    val next = scene.frameAt(it.endTime) + 1
                    cursor = scene.frameStart(next).toDouble() + gapFrames * 1000.0 / scene.fps
                }
            }
        })
    }

    fun expression(layer: Layer, ref: PropRef, source: String, fps: Int): Layer {
        val program = ExpressionMath.compile(source)
        val original = layer.element(ref) ?: XmlNode(ref.name, mapOf("value" to when (ref) {
            PropRef.Location, PropRef.Pivot -> "0,0,0"
            PropRef.Scale -> "1,1"
            PropRef.Opacity -> "1"
            else -> "0"
        }))
        val duration = maxOf(1, layer.endTime - layer.startTime) / 1000.0
        val count = ceil(duration * fps).toInt().coerceAtLeast(1)
        require(count <= 100_000)
        val context = ExpressionMath.Context().apply {
            this.duration = duration
            seed = layer.id
            first = original.keyframes.firstOrNull()?.t?.times(duration) ?: Double.POSITIVE_INFINITY
            last = original.keyframes.lastOrNull()?.t?.times(duration) ?: Double.NEGATIVE_INFINITY
            sampler = ExpressionMath.Sampler { s -> floats(original.valueAt((s / duration).toFloat()), floatArrayOf(0f)).map(Float::toDouble).toDoubleArray() }
        }
        val keys = (0..count).map { i ->
            val t = minOf(1.0, i.toDouble() / fps / duration)
            context.time = t * duration
            context.absTime = layer.startTime / 1000.0 + context.time
            context.value = context.sampler.sample(context.time)
            Keyframe(t.toFloat(), formatFloats(program.evaluate(context).map(Double::toFloat).toFloatArray()))
        }
        val transformed = original.withKeyframes(keys).with("trueExpression", source)
        return layer.withElement(ref, transformed)
    }

    fun bop(scene: Scene, layer: Layer, amount: Float, decayMs: Int): Layer {
        require(amount.isFinite() && amount >= 0 && decayMs > 0)
        val beats = scene.bookmarks.filter { it in layer.startTime..layer.endTime }
        require(beats.isNotEmpty())
        val original = layer.element(PropRef.Scale) ?: XmlNode("scale", mapOf("value" to "1,1"))
        val span = maxOf(1, layer.endTime - layer.startTime)
        val count = ceil(span * scene.fps / 1000.0).toInt().coerceAtLeast(1)
        require(count <= 100_000)
        val keys = (0..count).map { i ->
            val ms = minOf(span, (i * 1000.0 / scene.fps).roundToInt())
            val t = ms.toFloat() / span
            val time = layer.startTime + ms
            val beat = beats.lastOrNull { it <= time }
            val pulse = if (beat == null) 0f else (1f - (time - beat).toFloat() / decayMs).coerceIn(0f, 1f).let { it * it }
            val base = floats(original.valueAt(t), floatArrayOf(1f, 1f))
            Keyframe(t, formatFloats(FloatArray(base.size) { base[it] * (1 + amount * pulse) }))
        }
        return layer.withElement(PropRef.Scale, original.withKeyframes(keys))
    }
}

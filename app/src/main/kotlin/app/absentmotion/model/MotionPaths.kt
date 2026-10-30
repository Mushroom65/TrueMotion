package app.absentmotion.model

import app.absentmotion.engine.NativeEngine
import kotlin.math.atan2
import kotlin.math.hypot

data class PathVector(val x: Float = 0f, val y: Float = 0f) {
    operator fun plus(other: PathVector) = PathVector(x + other.x, y + other.y)
    operator fun minus(other: PathVector) = PathVector(x - other.x, y - other.y)
    operator fun times(k: Float) = PathVector(x * k, y * k)
    val length: Float get() = hypot(x, y)
    fun text(): String = formatFloats(floatArrayOf(x, y))
}

fun Keyframe.pathPoint(): PathVector = floats(v, floatArrayOf(0f, 0f)).let { PathVector(it[0], it[1]) }
fun Keyframe.pathHandle(incoming: Boolean): PathVector = floats(extra[if (incoming) "pathIn" else "pathOut"], floatArrayOf(0f, 0f)).let { PathVector(it[0], it[1]) }

fun pathPoint(a: Keyframe, b: Keyframe, u: Float): PathVector {
    val p = a.pathPoint(); val q = b.pathPoint()
    val out = a.pathHandle(false); val inside = b.pathHandle(true)
    if (out == PathVector() && inside == PathVector()) return p + (q - p) * u
    val s = 1 - u
    return p * (s*s*s) + (p + out) * (3*s*s*u) + (q + inside) * (3*s*u*u) + q * (u*u*u)
}

fun pathHeading(a: Keyframe, b: Keyframe, u: Float): Float {
    val p = a.pathPoint(); val q = b.pathPoint()
    val out = a.pathHandle(false); val inside = b.pathHandle(true)
    fun tangent(at: Float): PathVector {
        if (out == PathVector() && inside == PathVector()) return q - p
        val s = 1 - at
        return out * (3*s*s) + (q + inside - p - out) * (6*s*at) - inside * (3*at*at)
    }
    var d = tangent(u)
    if (d.length < 1e-5f) d = tangent(if (u >= .5f) u - .001f else u + .001f)
    return if (d.length < 1e-5f) 0f else Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat()
}

internal fun XmlNode.pathValue(a: Keyframe, b: Keyframe, u: Float): String? {
    if (name != "location" || this["truePath"] != "true") return null
    val p = pathPoint(a, b, u)
    val av = floats(a.v, floatArrayOf(0f, 0f, 0f)); val bv = floats(b.v, floatArrayOf(0f, 0f, 0f))
    return formatFloats(floatArrayOf(p.x, p.y, av[2] + (bv[2] - av[2]) * u))
}

val Layer.pathEnabled: Boolean get() = element(PropRef.Location)?.get("truePath") == "true"
val Layer.pathAutoOrient: Boolean get() = node.child("transform")?.get("trueAutoOrient") == "true"

fun Layer.pathAngle(): Float {
    if (!pathAutoOrient) return 0f
    val keys = keyframes(PropRef.Location)
    if (keys.size < 2 || !t.isFinite()) return 0f
    val index = keys.indexOfFirst { it.t > t }.let { if (it < 0) keys.lastIndex else it.coerceAtLeast(1) }
    val a = keys[index - 1]; val b = keys[index]
    if (b.t <= a.t) return 0f
    val linear = ((t - a.t) / (b.t - a.t)).coerceIn(0f, 1f)
    val u = b.e?.let { NativeEngine.ease(it, linear) } ?: linear
    return if (pathEnabled) pathHeading(a, b, u) else pathHeading(a.copy(extra = emptyMap()), b.copy(extra = emptyMap()), u)
}

fun Layer.withMotionPath(enabled: Boolean = true): Layer {
    val p = element(PropRef.Location) ?: return this
    return withElement(PropRef.Location, p.with("truePath", if (enabled) "true" else null))
}

fun Layer.withPathAutoOrient(enabled: Boolean): Layer = copy(node = node.withChild("transform",
    (node.child("transform") ?: XmlNode("transform")).with("trueAutoOrient", if (enabled) "true" else null)))

private fun Layer.changedPathKeys(change: (List<Keyframe>) -> List<Keyframe>): Layer {
    val prop = element(PropRef.Location) ?: return this
    return withElement(PropRef.Location, prop.with("truePath", "true").withKeyframes(change(prop.keyframes)))
}

fun Layer.withPathPoint(index: Int, point: PathVector): Layer {
    require(point.x.isFinite() && point.y.isFinite())
    return changedPathKeys { keys -> keys.mapIndexed { i, k -> if (i != index) k else {
        val values = floats(k.v, floatArrayOf(0f, 0f, 0f)); values[0] = point.x; values[1] = point.y
        k.copy(v = formatFloats(values))
    } } }
}

fun Layer.withPathHandle(index: Int, incoming: Boolean, value: PathVector, linked: Boolean): Layer {
    require(value.x.isFinite() && value.y.isFinite())
    return changedPathKeys { keys -> keys.mapIndexed { i, k -> if (i != index) k else {
        var extra = k.extra + ((if (incoming) "pathIn" else "pathOut") to value.text())
        if (linked && value.length > 1e-5f) {
            val length = k.pathHandle(!incoming).length.takeIf { it > 1e-5f } ?: value.length
            extra = extra + ((if (incoming) "pathOut" else "pathIn") to (value * (-length / value.length)).text())
        }
        k.copy(extra = extra)
    } } }
}

fun Layer.withSmoothedPath(index: Int? = null, linear: Boolean = false): Layer = changedPathKeys { keys ->
    keys.mapIndexed { i, k ->
        if (index != null && i != index) return@mapIndexed k
        var inside = PathVector(); var out = PathVector()
        val p = k.pathPoint()
        val previous = keys.getOrNull(i - 1); val next = keys.getOrNull(i + 1)
        if (!linear) {
            if (previous != null && next != null) {
                val span = next.t - previous.t
                if (span > 1e-6f) {
                    val delta = next.pathPoint() - previous.pathPoint()
                    inside = delta * (-(k.t - previous.t) / span / 3)
                    out = delta * ((next.t - k.t) / span / 3)
                }
            } else {
                if (previous != null) inside = (previous.pathPoint() - p) * (1f/3)
                if (next != null) out = (next.pathPoint() - p) * (1f/3)
            }
        }
        k.copy(extra = k.extra + ("pathIn" to inside.text()) + ("pathOut" to out.text()))
    }
}

fun Layer.withPathEndpoints(width: Int, height: Int): Layer {
    if (keyframes(PropRef.Location).size >= 2) return withMotionPath()
    val at = location
    val old = element(PropRef.Location) ?: XmlNode("location")
    val start = PathVector(at[0], at[1])
    val finish = start + PathVector(width * .25f, -height * .15f)
    // Preserve the current position as the first key, with a visible editable end.
    return withElement(PropRef.Location, old.with("truePath", "true").withKeyframes(listOf(
        Keyframe(0f, formatFloats(floatArrayOf(start.x, start.y, at[2]))),
        Keyframe(1f, formatFloats(floatArrayOf(finish.x, finish.y, at[2]))),
    )))
}

internal fun Keyframe.reversedPathHandles(): Keyframe = copy(extra = (extra - "pathIn" - "pathOut") +
    listOfNotNull(extra["pathOut"]?.let { "pathIn" to it }, extra["pathIn"]?.let { "pathOut" to it }).toMap())

/** Convert our former effect-backed controls once, retaining their original data. */
internal fun Scene.withLegacyMotionPaths(): Scene {
    fun visit(node: XmlNode): XmlNode {
        val children = node.children.map(::visit)
        val current = if (children == node.children) node else node.copy(children = children)
        if (current.name !in Scene.LAYER_TAGS) return current
        val layer = Layer(current)
        val legacy = layer.effects.firstOrNull { it["id"] in setOf("lab.toji.motionPath", "lab.toji.motionpath") && it["hidden"] != "true" } ?: return current
        val position = layer.element(PropRef.Location) ?: return current
        if (position.keyframes.size < 2) return current
        val converted = if (position["truePath"] == "true") layer else layer.withElement(PropRef.Location,
            position.with("truePath", "true").withKeyframes(position.keyframes.map { key ->
                fun value(name: String) = legacy.effectProperty(name)?.valueAt(key.t)?.finiteFloat() ?: 0f
                key.copy(extra = key.extra + ("pathIn" to PathVector(value("inX"),value("inY")).text()) +
                    ("pathOut" to PathVector(value("outX"),value("outY")).text()))
            }))
        return converted.node.copy(children = converted.node.children.map { if (it === legacy) it.with("hidden", "true").with("truePathConverted", "true") else it })
    }
    return Scene(visit(node))
}

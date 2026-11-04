package app.absentmotion.model

import kotlin.math.ceil

/** A scalar channel; vectors can link each axis independently. Values use scene units. */
data class ValueChannel(val ref: PropRef, val axis: Int = 0)
data class ValueLink(val source: Long, val channel: ValueChannel, val multiply: Float = 1f, val offset: Float = 0f, val delayMs: Int = 0)

private fun ValueChannel.attributes() = mapOf("kind" to ref.kind.name, "name" to ref.name, "effect" to "${ref.effectIndex}", "axis" to "$axis")
private fun XmlNode.channel(): ValueChannel? = runCatching {
    ValueChannel(PropRef(PropRef.Kind.valueOf(this["kind"]!!), this["name"]!!, this["effect"]?.toIntOrNull() ?: -1), this["axis"]!!.toInt())
}.getOrNull()
private fun XmlNode.link(): ValueLink? = channel()?.let { c ->
    this["source"]?.toLongOrNull()?.let { ValueLink(it, c, this["multiply"]?.finiteFloat() ?: 1f, this["offset"]?.finiteFloat() ?: 0f, this["delay"]?.toIntOrNull() ?: 0) }
}
private fun XmlNode.base() = child("trueLinkBase")?.copy(name = name) ?: copy(children = children.filterNot { it.name == "trueValueLink" })

fun ValueChannel.defaults(): FloatArray = when (ref) {
    PropRef.Location, PropRef.Pivot -> floatArrayOf(0f, 0f, 0f)
    PropRef.Scale -> floatArrayOf(1f, 1f)
    PropRef.Tilt, PropRef.Skew -> floatArrayOf(0f, 0f)
    PropRef.Opacity -> floatArrayOf(1f)
    else -> floatArrayOf(0f)
}
fun Layer.valueLink(channel: ValueChannel): ValueLink? = element(channel.ref)?.children
    ?.firstOrNull { it.name == "trueValueLink" && it["targetAxis"] == "${channel.axis}" }?.link()
fun Scene.resolvedValueLink(id: Long, channel: ValueChannel): ValueLink? {
    val metadata = layer(id)?.element(channel.ref)?.children?.firstOrNull { it.name == "trueValueLink" && it["targetAxis"] == "${channel.axis}" } ?: return null
    val link = metadata.link() ?: return null
    val token = metadata["effectKey"] ?: return link
    val index = layer(link.source)?.effects?.indexOfFirst { it["trueLinkKey"] == token } ?: -1
    return link.copy(channel = link.channel.copy(ref = link.channel.ref.copy(effectIndex = index)))
}
fun Layer.numericChannels(): List<ValueChannel> {
    val transforms = listOf(PropRef.Location, PropRef.Rotation, PropRef.Scale, PropRef.Opacity, PropRef.Tilt, PropRef.Skew, PropRef.Pivot)
        .flatMap { ref -> ValueChannel(ref).defaults().indices.map { ValueChannel(ref, it) } }
    fun channels(ref: PropRef, property: XmlNode): List<ValueChannel> {
        if (property["type"] !in setOf("float", "vec2", "vec3", "point", "int")) return emptyList()
        val values = property.valueAt(t)?.split(',')?.map { it.trim().finiteFloat() ?: return emptyList() } ?: return emptyList()
        return values.indices.map { ValueChannel(ref, it) }
    }
    return transforms + node.children.filter { it.name == "property" }.flatMap { channels(PropRef.shape(it["name"] ?: ""), it) } +
        effects.flatMapIndexed { index, effect -> effect.children.filter { it.name == "property" }.flatMap { channels(PropRef.effect(index, it["name"] ?: ""), it) } }
}

fun Scene.withValueLink(target: Long, channel: ValueChannel, link: ValueLink): Scene {
    require(link.multiply.isFinite() && link.offset.isFinite() && link.delayMs in -3_600_000..3_600_000)
    val dst = requireNotNull(layer(target)); val src = requireNotNull(layer(link.source))
    require(channel in dst.numericChannels() && link.channel in src.numericChannels()) { "Unsupported channel" }
    require(dst.node.child("trueCopy") == null && src.node.child("trueCopy") == null) { "Link the original layer" }
    val old = dst.element(channel.ref) ?: XmlNode(channel.ref.name, mapOf("value" to formatFloats(channel.defaults())))
    val base = old.child("trueLinkBase") ?: old.copy(name = "trueLinkBase", children = old.children.filterNot { it.name == "trueValueLink" })
    val effect = if (link.channel.ref.kind == PropRef.Kind.Effect) src.effects[link.channel.ref.effectIndex] else null
    val token = effect?.get("trueLinkKey") ?: effect?.let { java.util.UUID.randomUUID().toString() }
    val prepared = if (effect != null) update(src.id) { it.withEffect(link.channel.ref.effectIndex) { e -> e.with("trueLinkKey", token) } } else this
    val metadata = XmlNode("trueValueLink", link.channel.attributes() + mapOf("targetAxis" to "${channel.axis}", "source" to "${link.source}",
        "multiply" to "${link.multiply}", "offset" to "${link.offset}", "delay" to "${link.delayMs}"))
        .with("effectKey", token)
    val next = prepared.update(target) { it.withElement(channel.ref, old.copy(children = old.children.filterNot {
        it.name == "trueValueLink" && it["targetAxis"] == "${channel.axis}"
    }).withChild("trueLinkBase", base).let { p -> p.copy(children = p.children + metadata) }) }
    return next.refreshValueLinks() // Validates the entire dependency graph before committing.
}

fun Scene.withoutValueLink(target: Long, channel: ValueChannel): Scene = update(target) { layer ->
    val old = layer.element(channel.ref) ?: return@update layer
    val rest = old.copy(children = old.children.filterNot { it.name == "trueValueLink" && it["targetAxis"] == "${channel.axis}" })
    // Disconnecting keeps the currently evaluated animation.
    layer.withElement(channel.ref, if (rest.children.none { it.name == "trueValueLink" }) rest.withChild("trueLinkBase", null) else {
        val base = rest.copy(name = "trueLinkBase", children = rest.children.filterNot { it.name in setOf("trueValueLink", "trueLinkBase") })
        rest.withChild("trueLinkBase", base)
    })
}

/** Compile persistent links to ordinary animation keys, used identically by preview and export. */
fun Scene.refreshValueLinks(): Scene {
    val all = layers + bones
    val ports = all.filter { it.node.child("trueCopy") == null }.flatMap { l -> l.numericChannels().filter { l.valueLink(it) != null }.map { l.id to it } }
    if (ports.isEmpty()) return this
    val missing = ports.filter { (id, channel) ->
        val link = resolvedValueLink(id, channel)
        link == null || layer(link.source)?.numericChannels()?.contains(link.channel) != true
    }
    if (missing.isNotEmpty()) return missing.fold(this) { s, (id, channel) -> s.withoutValueLink(id, channel) }.refreshValueLinks()
    val source = all.associateBy { it.id }
    val visiting = mutableSetOf<Pair<Long, ValueChannel>>()
    val checked = mutableSetOf<Pair<Long, ValueChannel>>()
    fun validate(port: Pair<Long, ValueChannel>) {
        if (port in checked) return
        require(visiting.add(port)) { "Parameter link cycle" }
        resolvedValueLink(port.first, port.second)?.let { validate(it.source to it.channel) }
        visiting.remove(port); checked.add(port)
    }
    ports.forEach(::validate)
    fun sample(id: Long, channel: ValueChannel, ms: Double, depth: Int = 0): Float {
        require(depth < 128) { "Parameter link chain is too long" }
        val l = source[id] ?: return channel.defaults().getOrElse(channel.axis) { 0f }
        val link = resolvedValueLink(id, channel)
        if (link != null && source[link.source] != null) {
            return (sample(link.source, link.channel, ms - link.delayMs, depth + 1) * link.multiply + link.offset).also { require(it.isFinite()) }
        }
        val t = ((ms - l.startTime) / (l.endTime - l.startTime).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        val value = l.element(channel.ref)?.base()?.valueAt(t)
        return floats(value, channel.defaults()).getOrElse(channel.axis) { 0f }
    }
    fun animated(id: Long, channel: ValueChannel): Boolean {
        val l = source[id] ?: return false
        val link = resolvedValueLink(id, channel)
        return if (link != null && source[link.source] != null) animated(link.source, link.channel) else l.element(channel.ref)?.base()?.isKeyframed == true
    }
    var next = this
    var budget = 0
    for ((id, channels) in ports.groupBy({ it.first }, { it.second })) {
        val l = source.getValue(id)
        for ((ref, axes) in channels.groupBy { it.ref }) {
            val prop = l.element(ref) ?: continue
            val defaults = ValueChannel(ref).defaults()
            val n = maxOf(defaults.size, floats(prop.base().valueAt(0f), defaults).size, axes.maxOf { it.axis } + 1)
            val needsKeys = prop.base().isKeyframed || axes.any { animated(id, it) }
            val count = if (needsKeys) ceil((l.endTime - l.startTime) * fps / 1000.0).toInt().coerceAtLeast(1) else 0
            budget += count + 1
            require(budget <= 500_000) { "Too many linked animation samples" }
            fun value(t: Float): String {
                val values = floats(prop.base().valueAt(t), defaults).copyOf(n)
                for (axis in axes) values[axis.axis] = sample(id, axis, l.startTime + (l.endTime - l.startTime) * t.toDouble())
                return formatFloats(values)
            }
            val baked = if (needsKeys) prop.withKeyframes((0..count).map { i ->
                val t = minOf(1f, i * 1000f / fps / (l.endTime - l.startTime).coerceAtLeast(1))
                Keyframe(t, value(t))
            }) else prop.copy(attrs = prop.attrs + ("value" to value(0f)), children = prop.children.filterNot { it.name == "kf" })
            next = next.update(id) { it.withElement(ref, baked) }
        }
    }
    return next
}

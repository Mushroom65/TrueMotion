package app.absentmotion.model

fun copyLayers(
    layers: List<Layer>, startTime: Int? = null, preserveOutsideParents: Boolean = false,
    move: (Layer, Int) -> Layer = { layer, start -> layer.movedTo(start) }, newId: () -> Long = Layer::newId,
): List<Layer> {
    val ids = mutableMapOf<Long, Long>()
    fun collect(node: XmlNode) {
        if ((node.name in Scene.LAYER_TAGS || node.name == "bone")) node["id"]?.toLongOrNull()?.let { ids.getOrPut(it, newId) }
        node.children.forEach(::collect)
    }
    layers.forEach { collect(it.node) }
    fun remap(node: XmlNode): XmlNode {
        var result = node
        if (node.name in setOf("trueValueLink", "trueCopy")) node["source"]?.toLongOrNull()?.let(ids::get)?.let { result = result.with("source", "$it") }
        if (node.name == "trueCopyId") for (attr in listOf("from", "to")) node[attr]?.toLongOrNull()?.let(ids::get)?.let { result = result.with(attr, "$it") }
        if ((node.name in Scene.LAYER_TAGS || node.name == "bone")) {
            node["id"]?.toLongOrNull()?.let { result = result.with("id", ids[it]?.toString()) }
            node["parent"]?.toLongOrNull()?.let { result = result.with("parent", ids[it]?.toString() ?: node["parent"].takeIf { preserveOutsideParents }) }
            result = result.withSiblingRefs(ids::get)
        }
        val children = node.children.map(::remap).map { child ->
            if (node.name == "effect" && app.absentmotion.truemotion.Particles.isEffect(node) && child.name == "property" && child["name"] == "sourceLayer") {
                ids[child["value"]?.toLongOrNull()]?.let { child.with("value", "$it") } ?: child
            } else child
        }
        return result.copy(children = children)
    }
    val shift = if (startTime == null || layers.isEmpty()) 0 else startTime - layers.minOf { it.startTime }
    return layers.map { move(Layer(remap(it.node)), it.startTime + shift) }
}

internal fun Scene.withCopiedLayerIds(): Scene {
    val originals = layers + bones
    val mapped = originals.zip(copyLayers(originals)).associate { (before, after) -> before.id to after.node }
    return Scene(node.copy(children = node.children.map { if (it.name in Scene.LAYER_TAGS || it.name == "bone") mapped[Layer(it).id] ?: it else it }))
}

internal fun XmlNode.withSiblingRefs(map: (Long) -> Long?): XmlNode {
    var result = this
    for (name in listOf("noiseId", "pathLayer")) this[name]?.toLongOrNull()?.let(map)?.let { result = result.with(name, it.toString()) }
    for (slot in MotionSlot.entries) MotionRecord.parse(this[slot.attr])?.takeIf { it.transition }?.let { record ->
        map(record.partner)?.let { result = result.with(slot.attr, record.copy(partner = it).toString()) }
    }
    return result
}

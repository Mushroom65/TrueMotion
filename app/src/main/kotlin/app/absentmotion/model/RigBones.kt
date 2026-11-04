package app.absentmotion.model

/** Bones belong to the scene's rig, not the layer stack. */
val Scene.bones: List<Layer> get() = node.children.filter { it.name == "bone" }.map(::Layer)
val Layer.isBone: Boolean get() = kind == "bone"
val Layer.rigLength: Float get() = node["length"]?.finiteFloat()?.coerceIn(1f, 10000f) ?: 160f

internal fun XmlNode.withMigratedBones(): XmlNode {
    val children = children.map { it.withMigratedBones() }
    return if (name == "nullobj" && this["trueBone"] == "true") copy(name = "bone", attrs = attrs - setOf("trueBone", "trueBoneLength") + ("length" to (this["trueBoneLength"] ?: "160")), children = children)
    else if (children == this.children) this else copy(children = children)
}

/** Compatibility with the existing native transform evaluator, at the render boundary only. */
fun XmlNode.withNativeBones(): XmlNode {
    val children = children.map { it.withNativeBones() }
    return if (name == "bone") copy(name = "nullobj", children = children) else if (children == this.children) this else copy(children = children)
}

fun Scene.boneAncestors(ids: Set<Long>): List<Layer> {
    val found = linkedSetOf<Long>()
    for (id in ids) {
        var current = layer(id)
        val visited = hashSetOf<Long>()
        while (current != null && visited.add(current.id)) {
            if (current.isBone) found += current.id
            current = current.node["parent"]?.toLongOrNull()?.let(::layer)
        }
    }
    return bones.filter { it.id in found }
}

fun Scene.pruneBones(only: Set<Long> = bones.map { it.id }.toSet()): Scene {
    val used = boneAncestors(layers.map { it.id }.toSet()).map { it.id }.toSet()
    return Scene(node.copy(children = node.children.filterNot { it.name == "bone" && Layer(it).id in only && Layer(it).id !in used }))
}

/** A resource saved from a rigged part includes the other parts of the same skeleton. */
fun Scene.riggedParts(id: Long): Set<Long> {
    val own = boneAncestors(setOf(id)).map { it.id }.toSet()
    if (own.isEmpty()) return setOf(id)
    return layers.filter { boneAncestors(setOf(it.id)).any { bone -> bone.id in own } }.map { it.id }.toSet()
}

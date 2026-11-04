package app.absentmotion.model

/** Bones belong to the scene's rig, not the layer stack. */
val Scene.bones: List<Layer> get() = node.children.filter { it.name == "bone" }.map(::Layer)
val Layer.isBone: Boolean get() = kind == "bone"
val Layer.rigLength: Float get() = node["length"]?.finiteFloat()?.coerceIn(1f, 10000f) ?: 160f

/** Static inverse-bind transform. Artwork animation stays in its original coordinate system. */
data class RigBinding(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double, val z: Float = 0f) {
    fun node() = XmlNode("trueRigBind", mapOf("a" to "$a", "b" to "$b", "c" to "$c", "d" to "$d", "tx" to "$tx", "ty" to "$ty", "z" to "$z"))
    fun transform(): XmlNode {
        val sx = kotlin.math.hypot(a,b)
        val sy = (a*d-b*c)/sx
        val shear = (a*c+b*d)/(sx*sy)
        fun prop(name: String, vararg v: Float) = XmlNode(name,mapOf("value" to formatFloats(v)))
        return XmlNode("transform",children=listOf(prop("location",tx.toFloat(),ty.toFloat(),z),
            prop("rotation",Math.toDegrees(kotlin.math.atan2(b,a)).toFloat()),prop("scale",sx.toFloat(),sy.toFloat()),
            prop("skew",kotlin.math.atan(shear).toFloat(),0f)))
    }
}

val Layer.rigBinding: RigBinding? get() = node.child("trueRigBind")?.let { n ->
    fun v(key: String) = n[key]?.toDoubleOrNull()?.takeIf(Double::isFinite)
    val a=v("a")?:return@let null;val b=v("b")?:return@let null;val c=v("c")?:return@let null
    val d=v("d")?:return@let null;val tx=v("tx")?:return@let null;val ty=v("ty")?:return@let null
    RigBinding(a,b,c,d,tx,ty,n["z"]?.finiteFloat()?:0f)
}

internal fun XmlNode.withMigratedBones(): XmlNode {
    val children = children.map { it.withMigratedBones() }
    return if (name == "nullobj" && this["trueBone"] == "true") copy(name = "bone", attrs = attrs - setOf("trueBone", "trueBoneLength") + ("length" to (this["trueBoneLength"] ?: "160")), children = children)
    else if (children == this.children) this else copy(children = children)
}

/** Compatibility with the existing native transform evaluator, at the render boundary only. */
fun XmlNode.withNativeBones(): XmlNode {
    val children = children.map { it.withNativeBones() }
    if (name == "scene") {
        val used = children.mapNotNull { it["id"]?.toLongOrNull() }.toMutableSet()
        var next = -1L
        val compiled = children.flatMap { child ->
            val binding = if (child.name in Scene.LAYER_TAGS) Layer(child).rigBinding else null
            if (binding == null) listOf(child) else {
                while (next in used) next--
                val id=next--;used+=id
                val attrs=mapOf("id" to "$id", "startTime" to (child["startTime"]?:"0"),"endTime" to (child["endTime"]?:"0")) +
                    (child["parent"]?.let { mapOf("parent" to it) }?:emptyMap())
                listOf(XmlNode("nullobj",attrs,listOf(binding.transform())),child.with("parent","$id").withChild("trueRigBind",null))
            }
        }
        return copy(children=compiled)
    }
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

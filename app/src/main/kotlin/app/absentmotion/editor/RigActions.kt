package app.absentmotion.editor

import app.absentmotion.model.*
import kotlin.math.*

val Layer.isRigBone: Boolean get() = kind == "nullobj" && node["trueBone"] == "true"
val Layer.boneLength: Float get() = node["trueBoneLength"]?.finiteFloat()?.coerceIn(1f, 10000f) ?: 160f

fun EditorState.addRigBone(parentId: Long?, label: String, length: Float): Long {
    val parent = parentId?.let { scene.layer(it) }?.takeIf { it.isRigBone }
    val id = Layer.newId()
    val start = parent?.startTime ?: scene.frameStart(frame)
    val end = parent?.endTime ?: scene.endTimeFor(frame, settings.defaultLayerDuration * 1000)
    var bone = Layer(XmlNode("nullobj", mapOf("id" to "$id", "label" to label.ifBlank { "Bone" }, "startTime" to "$start", "endTime" to "$end", "trueBone" to "true", "trueBoneLength" to "${length.coerceIn(1f, 10000f)}")))
        .withTransform("location", if (parent == null) scene.width / 2f else parent.boneLength, if (parent == null) scene.height / 2f else 0f, 0f)
        .withTransform("rotation", 0f)
    if (parent != null) bone = bone.copy(node = bone.node.with("parent", "${parent.id}"))
    bone = bone.copy(node = bone.node.withChild("trueRest", bone.node.child("transform")!!.copy(name = "trueRest")))
    edit { it.add(bone) }
    selectedId = id
    return id
}

/** FK and IK write ordinary rotation keys: no runtime dependency on the rig panel. */
internal fun Layer.withRigRotation(degrees: Float, keyed: Boolean): Layer {
    var bone = this
    if (keyed && bone.keyframes(PropRef.Rotation).isEmpty()) {
        val value = bone.element(PropRef.Rotation)?.get("value") ?: "0"
        bone = bone.withElement(PropRef.Rotation, XmlNode("rotation").withKeyframes(listOf(Keyframe(0f, value))))
    }
    return if (keyed) bone.withTransform("rotation", degrees) else bone.withElement(PropRef.Rotation, XmlNode("rotation", mapOf("value" to "$degrees")))
}

data class TwoBoneAngles(val root: Float, val child: Float)

/** Target is in the root's parent space relative to the root origin. */
fun solveTwoBoneIK(a: Float, b: Float, x: Float, y: Float, flipped: Boolean): TwoBoneAngles {
    require(listOf(a, b, x, y).all { it.isFinite() } && a > 0 && b > 0)
    val c = ((x.toDouble() * x + y.toDouble() * y - a.toDouble() * a - b.toDouble() * b) / (2.0 * a * b)).coerceIn(-1.0, 1.0)
    val elbow = acos(c) * if (flipped) -1 else 1
    val shoulder = atan2(y.toDouble(), x.toDouble()) - atan2(b * sin(elbow), a + b * cos(elbow))
    return TwoBoneAngles(Math.toDegrees(shoulder).toFloat(), Math.toDegrees(elbow).toFloat())
}

fun EditorState.poseTwoBoneIK(tipId: Long, x: Float, y: Float, flipped: Boolean, keyed: Boolean): Boolean {
    val tip = scene.layer(tipId)?.takeIf { it.isRigBone } ?: return false
    val root = tip.node["parent"]?.toLongOrNull()?.let { scene.layer(it) }?.takeIf { it.isRigBone } ?: return false
    val atRoot = root.at(sampleMs, scene.fps)
    val atTip = tip.at(sampleMs, scene.fps)
    val unitScale = (atRoot.scale + atTip.scale).all { abs(it - 1f) < 0.001f }
    require(unitScale && abs(atTip.location[0] - root.boneLength) < 0.01f && abs(atTip.location[1]) < 0.01f &&
        abs(atRoot.skew[0]) < 0.001f && abs(atRoot.skew[1]) < 0.001f &&
        abs(atTip.skew[0]) < 0.001f && abs(atTip.skew[1]) < 0.001f) {
        app.absentmotion.i18n.AppText.get(app.absentmotion.R.string.rig_ik_invalid)
    }
    val angles = solveTwoBoneIK(root.boneLength, tip.boneLength, x, y, flipped)
    edit { it.update(root.id, sampleMs) { b -> b.withRigRotation(angles.root, keyed) }
        .update(tip.id, sampleMs) { b -> b.withRigRotation(angles.child, keyed) } }
    return true
}

fun EditorState.bindRigLayers(ids: Set<Long>, boneId: Long?): Int {
    val art = ids.filter { scene.layer(it)?.isRigBone == false }
    val (next, skipped) = scene.parented(art, boneId)
    edit { next }
    return skipped
}

fun EditorState.captureRigRest() = edit { s -> s.layers.filter { it.isRigBone }.fold(s) { acc, b -> acc.update(b.id, sampleMs) { at ->
    val transform = XmlNode("trueRest", children = listOf("location", "rotation", "scale", "pivot").map { name ->
        val ref = PropRef(PropRef.Kind.Transform, name)
        XmlNode(name, mapOf("value" to (at.element(ref)?.valueAt(at.t) ?: if (name == "scale") "1,1" else "0,0,0")))
    })
    at.copy(node = at.node.withChild("trueRest", transform))
} } }

fun EditorState.restoreRigRest(keyed: Boolean) = edit { s -> s.layers.filter { it.isRigBone }.fold(s) { acc, b -> acc.update(b.id, sampleMs) { at ->
    b.node.child("trueRest")?.children?.fold(at) { bone, prop ->
        val ref = PropRef(PropRef.Kind.Transform, prop.name)
        var next = bone
        if (keyed && next.keyframes(ref).isEmpty()) next = next.withElement(ref, XmlNode(prop.name).withKeyframes(listOf(Keyframe(0f, next.element(ref)?.get("value") ?: prop["value"].orEmpty()))))
        if (keyed) next.withValue(ref, prop["value"].orEmpty()) else next.withElement(ref, prop)
    } ?: at
} } }

/** Save a whole selected rig, including its artwork; flatten external parenting first. */
fun Scene.rigModel(ids: Set<Long>, title: String): Scene {
    val wanted = ids.toMutableSet()
    if (wanted.isEmpty()) wanted += layers.filter { it.kind != "camera" && it.kind != "audio" }.map { it.id }
    var changed = true
    while (changed) {
        val before = wanted.size
        layers.filter { it.id in wanted }.mapNotNull { it.node["parent"]?.toLongOrNull() }.forEach { wanted += it }
        layers.filter { it.node["parent"]?.toLongOrNull() in wanted }.forEach { wanted += it.id }
        changed = before != wanted.size
    }
    val picked = layersToCopy(wanted)
    require(picked.isNotEmpty())
    val start = picked.minOf { it.startTime }
    val copies = copyLayers(picked, 0)
    return Scene.create(title, width, height, fps, 0).let { base -> copies.fold(media.fold(base) { acc, m -> acc.withMedia(m) }) { acc, l -> acc.add(l) } }
        .let { Scene(it.node.with("trueRigModel", "1").with("totalTime", "${picked.maxOf { l -> l.endTime } - start}")) }
}

fun EditorState.insertRigModel(model: Scene) {
    val id = Layer.newId()
    val copied = Scene(model.node.copy(children = model.node.children.filterNot { it.name in Scene.LAYER_TAGS } + copyLayers(model.layers).map { it.node }))
    val layer = Layer(XmlNode("embedScene", mapOf("id" to "$id", "label" to model.title, "trueRigModel" to "1",
        "startTime" to "$timeMs", "endTime" to "${timeMs + model.totalTime}"), listOf(copied.node)))
        .withTransform("location", scene.width / 2f, scene.height / 2f, 0f)
    edit { model.media.fold(it) { acc, m -> acc.withMedia(m) }.add(layer) }
    MultiSelect.clear()
    selectedId = id
    addOpen = false
}

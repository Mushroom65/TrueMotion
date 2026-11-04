package app.absentmotion.editor

import app.absentmotion.model.*
import kotlin.math.*

internal fun Layer.withRigRotation(degrees: Float, keyed: Boolean): Layer {
    var bone = this
    if (keyed && bone.keyframes(PropRef.Rotation).isEmpty()) {
        val value = bone.element(PropRef.Rotation)?.get("value") ?: "0"
        bone = bone.withElement(PropRef.Rotation, XmlNode("rotation").withKeyframes(listOf(Keyframe(0f, value))))
    }
    return if (keyed) bone.withTransform("rotation", degrees) else bone.withElement(PropRef.Rotation, XmlNode("rotation", mapOf("value" to "$degrees")))
}

data class TwoBoneAngles(val root: Float, val child: Float)
fun solveTwoBoneIK(a: Float, b: Float, x: Float, y: Float, flipped: Boolean): TwoBoneAngles {
    require(listOf(a, b, x, y).all { it.isFinite() } && a > 0 && b > 0)
    val c = ((x.toDouble() * x + y.toDouble() * y - a.toDouble() * a - b.toDouble() * b) / (2.0 * a * b)).coerceIn(-1.0, 1.0)
    val elbow = acos(c) * if (flipped) -1 else 1
    val shoulder = atan2(y.toDouble(), x.toDouble()) - atan2(b * sin(elbow), a + b * cos(elbow))
    return TwoBoneAngles(Math.toDegrees(shoulder).toFloat(), Math.toDegrees(elbow).toFloat())
}

internal fun Scene.rigLocal(world: Pt, parentId: Long?, time: Int): Pt {
    val parent = parentId?.let(::layer) ?: return world
    val local = decompose(Place(world.x, world.y, 0f, 0f, 1f, 1f, 0f), place(parent, time)) ?: return world
    return Pt(local.x, local.y)
}

/** Draw a bone on the selected artwork and bind it in the same undoable operation. */
internal fun Scene.withDrawnBone(artId: Long, parentId: Long?, from: Pt, to: Pt, id: Long, time: Int): Scene {
    val art = layer(artId)?.takeUnless { it.kind in setOf("camera", "audio", "nullobj", "bone") } ?: return this
    val parent = parentId?.let(::layer)?.takeIf { it.isBone }
    val start = rigLocal(from, parent?.id, time)
    val end = rigLocal(to, parent?.id, time)
    val dx = end.x - start.x; val dy = end.y - start.y
    val length = hypot(dx, dy)
    if (!length.isFinite() || length < 1f) return this
    var bone = Layer(XmlNode("bone", mapOf("id" to "$id", "label" to art.label, "length" to "${length.coerceAtMost(10000f)}",
        "startTime" to "${minOf(art.startTime, parent?.startTime ?: art.startTime)}", "endTime" to "${maxOf(art.endTime, parent?.endTime ?: art.endTime)}")))
        .withTransform("location", start.x, start.y, 0f).withTransform("rotation", Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
    if (parent != null) bone = bone.copy(node = bone.node.with("parent", "${parent.id}"))
    bone = bone.copy(node = bone.node.withChild("trueRest", bone.node.child("transform")!!.copy(name = "trueRest")))
    val next = add(bone)
    return next.update(art.id) { next.rebased(it, bone.id) ?: it }
}

fun EditorState.openRig() {
    endGesture(); playing = false; cameraMode = false; addOpen = false; rigEditing = true
    rigBoneId = selected?.node?.get("parent")?.toLongOrNull()?.takeIf { scene.layer(it)?.isBone == true }
    panel = EditorPanel.Tools; keyTarget = null
    MultiSelect.clear()
}

internal fun EditorState.drawRigBone(parentId: Long?, from: Pt, to: Pt) {
    val art = selectedId ?: return
    val id = Layer.newId()
    edit { it.withDrawnBone(art, parentId, from, to, id, sampleMs) }
    if (scene.layer(id) != null) rigBoneId = id
}

internal fun EditorState.bindViewportBone(id: Long) {
    val art = selected ?: return
    val next = scene.rebased(art, id) ?: return
    edit { it.update(art.id) { next } }
    rigBoneId = id
}

internal fun EditorState.poseViewportBone(id: Long, target: Pt, rootHandle: Boolean, allowIK: Boolean = true) {
    val bone = scene.layer(id)?.takeIf { it.isBone } ?: return
    val at = bone.at(sampleMs, scene.fps)
    if (rootHandle) {
        val p = scene.rigLocal(target, bone.node["parent"]?.toLongOrNull(), sampleMs)
        edit("rig-pose") { s -> s.update(id, sampleMs) { b ->
            val seeded = if (rigKeyed && b.keyframes(PropRef.Location).isEmpty()) b.withElement(PropRef.Location, XmlNode("location").withKeyframes(listOf(Keyframe(0f, formatFloats(b.location))))) else b
            if (rigKeyed) seeded.withTransform("location", p.x, p.y, b.location[2]) else b.withElement(PropRef.Location, XmlNode("location", mapOf("value" to formatFloats(floatArrayOf(p.x, p.y, b.location[2])))))
        } }
        return
    }
    val parent = bone.node["parent"]?.toLongOrNull()?.let { scene.layer(it) }
    if (allowIK && rigTool == 2 && parent?.isBone == true && abs(at.location[0] - parent.rigLength) < .01f && abs(at.location[1]) < .01f) {
        val p = scene.rigLocal(target, parent.node["parent"]?.toLongOrNull(), sampleMs)
        val origin = parent.at(sampleMs, scene.fps).location
        val a = solveTwoBoneIK(parent.rigLength, bone.rigLength, p.x - origin[0], p.y - origin[1], rigFlip)
        edit("rig-pose") { s -> s.update(parent.id, sampleMs) { it.withRigRotation(a.root, rigKeyed) }.update(id, sampleMs) { it.withRigRotation(a.child, rigKeyed) } }
    } else {
        val p = scene.rigLocal(target, parent?.id, sampleMs)
        val location = at.location
        val angle = Math.toDegrees(atan2((p.y - location[1]).toDouble(), (p.x - location[0]).toDouble())).toFloat()
        edit("rig-pose") { it.update(id, sampleMs) { b -> b.withRigRotation(angle, rigKeyed) } }
    }
}

internal fun EditorState.toggleRigKey() {
    val id = rigBoneId ?: return
    edit { it.update(id, sampleMs) { bone -> bone.withKeyframeToggled(PropRef.Rotation, "0") } }
    rigKeyed = true
}

internal fun EditorState.deleteViewportBone() {
    val id = rigBoneId ?: return
    val related = scene.boneAncestors(setOf(id)).map { it.id }.toSet()
    edit { it.unparentChildrenOf(setOf(id)).remove(id).pruneBones(related) }
    rigBoneId = null
}

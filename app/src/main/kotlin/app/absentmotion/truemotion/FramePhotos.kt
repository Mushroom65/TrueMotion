package app.absentmotion.truemotion

import app.absentmotion.editor.imageLayer
import app.absentmotion.ai.AI_TAG
import app.absentmotion.audio.AUDIO_FX_TAG
import app.absentmotion.model.*
import kotlin.math.roundToInt

/** Source photos keep the selected geometry and pose, without effects or audio. */
internal object FramePhotos {
    data class Plan(val capture: Scene, val photo: Layer, val width: Int, val height: Int)

    fun plan(scene: Scene, sourceId: Long?, frame: Int, seconds: Float, uri: String, label: String, id: Long, maxSide: Int = 4096): Plan? {
        if (!seconds.isFinite() || seconds <= 0 || maxSide < 1) return null
        val at = scene.frameStart(frame)
        val start = scene.frameStart(frame)
        val source = sourceId?.let { scene.layer(it) ?: return null }
        if (source != null && (source.mediaFill?.video != true || !source.activeAt(at))) return null
        val record = source?.mediaFill?.let { scene.media(it.uri) }
        val rawWidth = if (source == null) scene.width else record?.width ?: return null
        val rawHeight = if (source == null) scene.height else record?.height ?: return null
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val ratio = minOf(1.0, maxSide.toDouble() / maxOf(rawWidth, rawHeight))
        val width = (rawWidth * ratio).roundToInt().coerceAtLeast(1)
        val height = (rawHeight * ratio).roundToInt().coerceAtLeast(1)
        val media = MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "filename" to uri.substringAfterLast('/'), "type" to "image/png", "width" to "$width", "height" to "$height")))
        val duration = (seconds.toDouble() * 1000).coerceIn(1.0, 3_600_000.0).roundToInt()
        val end = scene.endTimeFor(frame, duration)
        if (source == null) {
            val photo = scene.imageLayer(media, frame, 1)!!.withId(id).withLabel(label).withTimes(start, end)
                .withProp("size", "vec2", scene.width / 2f, scene.height / 2f)
            return Plan(scene, photo, width, height)
        }
        // Decode the same source clock as the selected clip, without baking its visual effects.
        val timingAttrs = listOf("inTime", "outTime", "speed", "loop").mapNotNull { key -> source.node[key]?.let { key to it } }.toMap()
        val raw = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "${source.startTime}", "endTime" to "${source.endTime}",
            "fillType" to "media", "fillVideo" to source.mediaFill!!.uri, "mediaFillMode" to "stretch", "s" to ".rect") + timingAttrs,
            listOfNotNull(source.node.child("speedMap")) + source.effects.filter { it["id"] == "absent.timequant" }))
            .withTransform("location", rawWidth / 2f, rawHeight / 2f, 0f).withProp("size", "vec2", rawWidth / 2f, rawHeight / 2f)
        val capture = Scene.create("", rawWidth, rawHeight, scene.fps, 0).add(raw, listOf(record!!))
        fun constant(node: XmlNode): XmlNode {
            if (node.isKeyframed) return node.copy(children = node.children.filterNot { it.name == "kf" }.map(::constant))
                .with("value", node.valueAt(source.at(at, scene.fps).t))
            return node.copy(children = node.children.map(::constant))
        }
        val removed = setOf("speedMap", "effect", "gain", AUDIO_FX_TAG, AI_TAG, "scene")
        var node = source.node.copy(children = source.node.children.filterNot { it.name in removed }.map(::constant))
        for (key in listOf("fillVideo", "inTime", "outTime", "speed", "loop", "trueSpeedContentEnd", "genIn", "genOut", "genLoop", "hidden", "clippingMask")) node = node.with(key, null)
        node = node.with("id", "$id").with("label", label).with("startTime", "$start").with("endTime", "$end")
            .with("fillType", "media").with("fillImage", uri)
        val pose = source.at(at, scene.fps)
        if (pose.pathAutoOrient) {
            val transform = node.child("transform") ?: XmlNode("transform")
            node = node.withChild("transform", transform.with("trueAutoOrient", null).withChild("rotation",
                XmlNode("rotation", mapOf("value" to "${pose.transform("rotation", 0f)[0] + pose.pathAngle()}"))))
        }
        return Plan(capture, Layer(node), width, height)
    }
}

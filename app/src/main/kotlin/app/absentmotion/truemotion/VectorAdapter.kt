package app.truemotion.vector

import android.app.Activity
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.EditorPanel
import app.absentmotion.model.*

object VectorAdapter {
    @JvmStatic fun isVector(layer: Any?): Boolean = (layer as? Layer)?.node?.get("templatePPId")?.startsWith("truevector:v1:") == true
    @JvmStatic fun current(holder: Any, original: Any?): Any? = (original as? Layer)?.id?.let { (holder as EditorState).scene.layer(it) }

    @JvmStatic fun open(activity: Activity, state: EditorState, create: Boolean) {
        L10n.use(activity)
        val layer = if (create) null else state.selected?.takeIf { isVector(it) } ?: return
        val document = if (create) VectorDocument(state.scene.width, state.scene.height, state.scene.fps.toDouble(), state.scene.fps * 3)
            else VectorAnimation.decode(layer!!.node["templatePPId"]!!)
        VectorEditor(activity, state, layer, document).show()
    }

    @JvmStatic fun commit(activity: Activity, holder: Any, original: Any?, document: VectorDocument) {
        val state = holder as EditorState
        val old = original as? Layer
        if (old != null) check(state.scene.layer(old.id) != null)
        VectorRaster.prepare(activity, document)
        val marker = VectorAnimation.marker(document)
        val xml = document.nativeScene()
        fun adapt(node: XmlNode): XmlNode {
            var n = node.copy(children = node.children.map(::adapt))
            if (n.name == "scene") {
                n = n.with("fps", (n["fps"]?.toDoubleOrNull()?.toInt() ?: document.fps.toInt()).coerceAtLeast(1).toString()).with("totalTime", maxOf(0, (n["totalTime"]?.toIntOrNull() ?: 1) - 1).toString())
            } else if (n.name in Scene.LAYER_TAGS) n["endTime"]?.toIntOrNull()?.let { end -> n = n.with("endTime", maxOf(n["startTime"]?.toIntOrNull() ?: 0, end - 1).toString()) }
            if (n.name == "text") n = n.with("font", "googlefonts?name=Roboto&weight=400").with("align", "center")
            return n
        }
        var nested = adapt(XmlNode.parse(xml))
        nested = Scene(nested).withCopiedLayerIds().node
        val start = old?.startTime ?: state.timeMs
        val id = old?.id ?: Layer.newId()
        var layer = old ?: Layer(XmlNode("embedScene", mapOf("id" to "$id", "label" to L10n.t("Векторная анимация"), "startTime" to "$start")))
            .withTransform("location", state.scene.width / 2f, state.scene.height / 2f, 0f)
        layer = layer.copy(node = layer.node.with("templatePPId", marker).with("endTime", (start + document.duration() - 1).toString()).withChild("scene", nested))
        val media = nested.mediaUris().map { MediaDeclaration(XmlNode("media", mapOf("uri" to it, "filename" to it.substringAfterLast('/')))) }
        state.edit { scene ->
            val edited = if (old == null) scene.add(layer, media) else scene.update(id) { layer }
            media.fold(edited) { s, m -> s.withMedia(m) }
        }
        state.selectedId = id
        state.panel = EditorPanel.Tools
    }
}

package app.truemotion.port

import android.content.Context
import androidx.compose.ui.geometry.Offset
import app.absentmotion.editor.EditorState
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.NativeEngine
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.exportVideo
import app.absentmotion.i18n.AppText
import app.absentmotion.library.AppSettings
import app.absentmotion.library.ProjectSaver
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.Recovery
import app.absentmotion.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal object GroupsProbe {
    fun run(context: Context, native: NativeEngine, directory: File): JSONObject = runBlocking {
        AppText.install(context)
        val source = Scene.parse("""<scene title="True Groups" width="320" height="320" fps="30" totalTime="999" bgcolor="#00000000">
          <shape id="409" label="Root sibling" hidden="true" startTime="0" endTime="3999"/>
          <embedScene id="401" label="Main group" startTime="0" endTime="999"><transform><location value="160,160,0"/></transform>
          <scene width="320" height="320" fps="30" totalTime="999" bgcolor="#00000000" retime="off">
            <shape id="402" label="Animated shape" startTime="0" endTime="999" fillType="color"><transform><location><kf t="0" v="90,160,0"/><kf t="1" v="230,160,0"/></location></transform>
              <fillColor value="#ffff2050"/><path d="M-40,-40L40,-40L40,40L-40,40Z"/></shape>
            <embedScene id="403" label="Nested group" startTime="0" endTime="999"><transform><location value="160,160,0"/></transform>
              <scene width="320" height="320" fps="30" totalTime="999" bgcolor="#00000000" retime="off">
                <shape id="404" label="Nested shape" startTime="0" endTime="999" fillType="color"><transform><location value="160,260,0"/></transform><fillColor value="#ff3060ff"/><path d="M-20,-20L20,-20L20,20L-20,20Z"/></shape>
              </scene>
            </embedScene>
          </scene></embedScene></scene>""")
        fun render(scene: Scene, at: Double): ByteArray {
            native.setScene(scene.node.toXml())
            return checkNotNull(native.still(at, 320, 320))
        }
        val before = render(source, 983.0)
        check((3 until before.size step 4).count { before[it].toInt() != 0 } > 1000)
        val extended = source.update(401) { it.trimmedTo(0, 3999) }
        check(before.contentEquals(render(extended, 3500.0))) { "Extended group lost or changed its held frame" }
        check(before.contentEquals(render(Scene.parse(extended.toXml()), 3500.0)))
        check(source.layer(401)!!.nested!!.layers == extended.layer(401)!!.nested!!.layers)
        // Retiming uses the same clock for native drawing, nested media and the editor.
        for (method in listOf("loop", "stretch", "blank")) {
            val mode = extended.update(401) { it.copy(node = it.node.withChild("scene", it.nested!!.node.with("retime", method))) }
            val ms = mode.layer(401)!!.nestedTime(2500.0, 30).ms
            if (ms < 0) check(render(mode, 2500.0).all { it.toInt() == 0 })
            else check(render(mode, 2500.0).contentEquals(render(source, ms))) { "Native $method clock differs from nested media" }
        }
        val oldBroken = source.update(401) { it.withTimes(0, 3999) }
        check(oldBroken.withGroupHolds() == extended)
        val store = ProjectStore(context)
        val runtime = EngineThread(0, context)
        val id = "true-groups-regression"
        val saver = ProjectSaver(id, store, Recovery(File(directory, "group-recovery")), failed = { error -> throw AssertionError("Group save", error) })
        try {
            val state = EditorState(id, oldBroken, store, runtime, AppSettings(context), saver)
            state.frame = 75
            state.canvasZoom = 2f
            state.canvasPan = Offset(10f, 15f)
            state.timelineZoom = 140f
            check(state.enterGroup(401))
            check(state.groupPath == listOf(401L) && state.frame == 29 && state.scene.layers.size == 2)
            state.selectedId = 402
            state.editSelected { it.withFill("color", 0xff00d9a5.toInt()) }
            check(state.document.groupScene(listOf(401))!!.layer(402)!!.fillColor == 0xff00d9a5.toInt())
            state.undo()
            check(state.scene.layer(402)!!.fillColor == 0xffff2050.toInt())
            state.redo()
            check(state.scene.layer(402)!!.fillColor == 0xff00d9a5.toInt())
            state.renameScene("Renamed group")
            check(state.sceneTitle == "Renamed group" && state.document.title == "True Groups")
            state.undo()
            check(state.sceneTitle == "Main group")
            check(state.enterGroup(403))
            state.selectedId = 404
            state.editSelected { it.withLabel("Nested edit") }
            check(state.document.groupScene(listOf(401, 403))!!.layer(404)!!.label == "Nested edit")
            check(state.leaveGroup() && state.selectedId == 403L)
            check(state.leaveGroup() && state.selectedId == 401L && state.frame == 75)
            check(state.canvasZoom == 2f && state.canvasPan == Offset(10f, 15f) && state.timelineZoom == 140f)
            state.endGesture()
            saver.flush().get()
            val saved = checkNotNull(store.load(id))
            check(saved.layers.map { it.id } == listOf(409L, 401L)) { "Saving a group overwrote the root project" }
            check(saved.groupScene(listOf(401))!!.layer(402)!!.fillColor == 0xff00d9a5.toInt())
            check(saved.groupScene(listOf(401, 403))!!.layer(404)!!.label == "Nested edit")
            val next = EditorState(id, saved, store, runtime, AppSettings(context), saver)
            next.restoreGroupPath(listOf(401, 403))
            check(next.groupPath == listOf(401L, 403L) && next.scene.layer(404)!!.label == "Nested edit")
            next.leaveGroup(); next.leaveGroup()
            val added = source.layer(401)!!.withId(450)
            next.edit { it.add(added) }
            check(next.enterGroup(450))
            next.undo()
            check(next.groupPath.isEmpty() && next.scene.layer(450) == null && next.document.layer(401) != null)
            // The held video remains decodable; held time has rate zero so it cannot replay stale audio.
            val video = File(directory, "particle-reference.mp4")
            check(video.isFile)
            val uri = video.toURI().toString()
            val videoScene = extended.update(401) { group ->
                group.copy(node = group.node.withChild("scene", group.nested!!.update(402) { layer ->
                    layer.copy(node = layer.node.with("fillType", "media").with("fillVideo", uri).with("inTime", "0").with("outTime", "1000"))
                }.node))
            }.withMedia(MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "type" to "video/mp4", "width" to "320", "height" to "320", "duration" to "1000000", "fps" to "30"))))
            val lastVideo = runtime.exportSession(videoScene.toXml()) { still(983, 320, 320) }
            val heldVideo = runtime.exportSession(videoScene.toXml()) { still(3500, 320, 320) }
            check(lastVideo != null && heldVideo != null && lastVideo.contentEquals(heldVideo)) { "Video in extended group did not hold its final frame" }
            val clock = videoScene.layerTracks().first { it.layer.id == 402L }.timeAt(3500.0, true)!!
            check(clock.rate == 0.0)
            check(runtime.stillPng(extended.toXml(), 3500, 320, 320, File(directory, "groups-export-reference.png")))
            FileOutputStream(File(directory, "groups.mp4")).use { output ->
                exportVideo(runtime, extended, extended.toXml(), ExportSettings(320, 30), output.fd, null, progress = {})
            }
            store.save(id, extended)
            File(directory, "groups.xml").writeText(extended.toXml())
        } finally { runtime.close() }
        val languages = listOf("en", "ru", "zh-CN").map { tag ->
            val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
            context.createConfigurationContext(config).getString(app.absentmotion.R.string.true_group_edit)
        }
        check(languages == listOf("Edit group", "Редактировать группу", "编辑组"))
        JSONObject().put("nestedEditor", true).put("rootDocumentSaved", true).put("undoRedo", true)
            .put("restoredPath", true).put("restoredParentView", true).put("undoAncestorCreation", true)
            .put("nativeHeldFrameExact", true).put("innerKeysUnchanged", true).put("reopenExact", true)
            .put("retimingModes", JSONArray(listOf("freeze", "loop", "stretch", "blank")))
            .put("heldVideoExact", true).put("heldAudioClockStopped", true).put("UILanguages", JSONArray(languages))
            .put("png", true).put("mp4", true)
    }
}

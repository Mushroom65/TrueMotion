package app.truemotion.port

import android.content.Context
import android.media.MediaExtractor
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.Resampler
import app.absentmotion.engine.AudioEffectRuntime
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.NativeEngine
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.exportVideo
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.*
import app.absentmotion.truemotion.AudioMotion
import app.absentmotion.truemotion.AudioReactive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

internal object AudioReactiveProbe {
    fun run(context: Context, native: NativeEngine, directory: File): JSONObject = runBlocking {
        val store = ProjectStore(context)
        val original = checkNotNull(store.load("true-audio-regression"))
        val config = AudioMotion.Settings(source = 203, amount = .7f, attackMs = 0f, releaseMs = 40f, gate = 0f)
        val live = original.update(103) { AudioReactive.put(it, config) }
        check(live.layer(103)!!.node.child("transform") == original.layer(103)!!.node.child("transform"))
        check(live.layer(103)!!.keyframes(PropRef.Scale).isEmpty())
        val runtime = AudioEffectRuntime(context)
        val prepared = runtime.prepare(live)
        check(prepared.signals.size == 1)
        native.setAudioSignals(prepared.signals); native.setScene(prepared.scene.node.toXml())
        fun area(pixels: ByteArray): Int = (0 until pixels.size step 4).count { (pixels[it + 1].toInt() and 255) > 100 }
        val sceneSets = native.sceneSetCount; val uploads = native.audioUploadCount
        val frames = (0..14).map { time ->
            val ms = time * 33
            ms to checkNotNull(native.still(ms.toDouble(), 320, 320))
        }
        val small = frames.minBy { area(it.second) }; val large = frames.maxBy { area(it.second) }
        check(area(large.second) > area(small.second) * 1.3)
        check(native.sceneSetCount == sceneSets && native.audioUploadCount == uploads)
        val bigQuad = native.layerQuad(103, large.first.toDouble())!!
        val smallQuad = native.layerQuad(103, small.first.toDouble())!!
        check(bigQuad[2] - bigQuad[0] > smallQuad[2] - smallQuad[0])
        val quiet = live.withSettings(320, 320, 30, original.bgColor).update(203) { Layer(it.node.withChild("gain", XmlNode("gain", mapOf("value" to "0")))) }
        val silent = runtime.prepare(quiet)
        check(silent.signals.single().response.at(100.0) == 0f)
        val moved = live.update(203) { Layer(it.node.with("startTime", "1000")) }
        val movedAudio = runtime.prepare(moved)
        check(movedAudio.signals.single().key != prepared.signals.single().key)
        check(movedAudio.signals.single().response.startMs == 1000)
        val gainConfig = config.copy(normalize = false, gain = 3f)
        val beforeGain = runtime.prepare(original.update(103) { AudioReactive.put(it, gainConfig) })
        val afterGain = runtime.prepare(original.update(203) { Layer(it.node.withChild("gain", XmlNode("gain", mapOf("value" to "0.8")))) }.update(103) { AudioReactive.put(it, gainConfig) })
        check(afterGain.signals.single().response.levels.max() > beforeGain.signals.single().response.levels.max() * 1.9)
        val disabled = live.update(103) { it.withEffect(0) { e -> e.with("hidden", "true") } }
        check(runtime.prepare(disabled).signals.isEmpty())
        val reopened = Scene.parse(live.toXml())
        check(reopened.layer(103) == live.layer(103))
        val engine = EngineThread(0, context)
        try {
            engine.setScene(live)
            withTimeout(30_000) {
                while (true) {
                    val shown = engine.previewForTest(320, 320)
                    if (shown != null && area(shown) > 16000 && !engine.audioEffects.value.pending) break
                    delay(50)
                }
            }
            engine.setScene(quiet)
            withTimeout(30_000) {
                while (true) {
                    val shown = engine.previewForTest(320, 320)
                    if (shown != null && area(shown) == 12100 && !engine.audioEffects.value.pending) break
                    delay(50)
                }
            }
            engine.setScene(live)
            withTimeout(30_000) {
                while (true) {
                    val shown = engine.previewForTest(320, 320)
                    if (shown != null && area(shown) > 16000 && !engine.audioEffects.value.pending) break
                    delay(50)
                }
            }
            val exact = engine.exportSession(live.toXml()) { still(large.first, 320, 320) }
            check(exact != null && exact.contentEquals(large.second)) { "Live preview and exact export differ" }
            val png = File(directory, "audio-live.png")
            check(engine.stillPng(live.toXml(), large.first, 320, 320, png))
            val short = Scene(live.node.with("totalTime", "999").copy(children = live.node.children.map { node ->
                if (node.name in Scene.LAYER_TAGS) node.with("endTime", "999") else node
            }))
            val mp4 = File(directory, "audio-live.mp4")
            val mix = OfflineMix.of(context, short, Resampler.HIGH, 48000)
            try {
                FileOutputStream(mp4).use { output -> exportVideo(engine, short, short.toXml(), ExportSettings(320, 30), output.fd, mix, progress = {}) }
            } finally { (mix as? Closeable)?.close() }
            val extractor = MediaExtractor()
            val mimes = try {
                extractor.setDataSource(mp4.absolutePath)
                List(extractor.trackCount) { extractor.getTrackFormat(it).getString("mime") }
            } finally { extractor.release() }
            check(mimes.any { it?.startsWith("video/") == true } && mimes.any { it?.startsWith("audio/") == true })
            store.save("true-audio-regression", live.withTitle("True Audio Live"))
            JSONObject().put("zeroGeneratedKeys", true).put("nativeFrameAreas", JSONArray(listOf(area(small.second), area(large.second))))
                .put("frameLoopSceneParses", native.sceneSetCount - sceneSets).put("frameLoopSignalUploads", native.audioUploadCount - uploads)
                .put("nativeSelectionResponds", true).put("gainMuteTimingInvalidation", true).put("reopenKeepsDependency", true)
                .put("asyncPreviewReady", true).put("automaticSceneEditUpdates", true).put("exportPixelsEqual", true).put("pngExport", true)
                .put("mp4Tracks", JSONArray(mimes)).put("mp4Bytes", mp4.length())
        } finally { engine.close() }
    }

    fun anchor(native: NativeEngine): JSONObject {
        val scenarios = listOf(
            "<transform><location value=\"160,160,0\"/><scale value=\"1.4,0.7\"/><rotation value=\"31\"/><skew value=\"0.12,-0.08\"/></transform>",
            "<transform><location value=\"160,160,0\"/><scale value=\"1.1,0.9\"/><rotation value=\"-20\"/><tilt value=\"25,15\"/></transform>",
        )
        var checked = 0
        for (transform in scenarios) for (parent in listOf(false, true)) {
            val outer = if (parent) "<nullobj id=\"300\" startTime=\"0\" endTime=\"1999\"><transform><location value=\"10,20,0\"/><scale value=\"0.8,1.2\"/><rotation value=\"14\"/><tilt value=\"10,5\"/></transform></nullobj>" else ""
            val scene = Scene.parse("""<scene width="320" height="320" fps="30" totalTime="1999">$outer<shape id="103" parent="${if (parent) 300 else 0}" startTime="0" endTime="1999" fillType="color">$transform<fillColor value="#ff00d9a5"/><path d="M-55,-55 L55,-55 L55,55 L-55,55 Z"/></shape></scene>""")
            native.setScene(scene.toXml())
            val before = native.layerQuad(103, 0.0)!!
            val local = native.localBounds(103, 0.0)!!
            check(local[0] <= -55 && local[2] >= 55)
            val location = checkNotNull(native.reanchor(103, 0.0, -40f, 35f))
            val changed = scene.update(103) { it.withTransform("pivot", -40f, 35f).withTransform("location", *location) }
            native.setScene(changed.toXml())
            val after = native.layerQuad(103, 0.0)!!
            check(before.indices.all { kotlin.math.abs(before[it] - after[it]) < .05f }) { "Reanchor changed geometry: ${before.toList()} => ${after.toList()}" }
            checked++
        }
        return JSONObject().put("keepsProjectedGeometry", checked).put("rotationScaleSkewTiltAndParent", true)
    }
}

package app.truemotion.port

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import app.absentmotion.audio.OfflineMix
import app.absentmotion.audio.Resampler
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.captureFramePhoto
import app.absentmotion.editor.videoLayer
import app.absentmotion.engine.EngineThread
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.FloatMix
import app.absentmotion.export.exportVideo
import app.absentmotion.library.*
import app.absentmotion.model.*
import app.absentmotion.truemotion.FramePhotos
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

internal object VideoToolsProbe {
    fun run(context: Context, directory: File): JSONObject = runBlocking {
        val store = ProjectStore(context)
        val id = "true-video-tools-regression"
        val folder = store.mediaDir(id).apply { mkdirs() }
        val fixture = File(folder,"video-tools.mp4")
        context.createPackageContext("app.truemotion.test",0).assets.open("video-tools.mp4").use { input -> fixture.outputStream().use(input::copyTo) }
        val uri = fixture.toURI().toString()
        val media = MediaDeclaration(XmlNode("media",mapOf("uri" to uri,"filename" to fixture.name,"type" to "video/mp4","width" to "160","height" to "96","duration" to "8000000","fps" to "30")))
        val base = Scene.create("True Video Tools",320,320,30,0)
        val video = base.videoLayer(media,0)!!.withId(501).withLabel("Video source")
        val source = base.add(video,listOf(media))
        val timed = source.update(501) { it.withTimes(1000,4499).copy(node=it.withTimes(1000,4499).node
            .with("speed","2").with("inTime","1000").with("outTime","8000")) }
        val engine = EngineThread(0,context)
        val saver = ProjectSaver(id,store,Recovery(File(directory,"photo-recovery")),failed={ error->throw AssertionError("Photo save",error) })
        fun png(path:String):ByteArray = BitmapFactory.decodeFile(path).let { bitmap ->
            checkNotNull(bitmap)
            ByteBuffer.allocate(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(it) }.array().also { bitmap.recycle() }
        }
        fun greens(bytes:ByteArray)=(0 until bytes.size step 4).count { (bytes[it+1].toInt() and 255)>(bytes[it].toInt() and 255)+40 }
        fun difference(a:ByteArray,b:ByteArray):Int { check(a.size==b.size); return a.indices.maxOf { abs((a[it].toInt() and 255)-(b[it].toInt() and 255)) } }
        try {
            val state = EditorState(id,timed,store,engine,AppSettings(context),saver)
            state.frame=75; state.selectedId=501
            val at=state.timeMs
            val before=state.document
            val made=state.captureFramePhoto(context,true,2f)
            val photo=state.scene.layer(made)!!
            check(photo.mediaFill?.video==false && photo.effects.isEmpty() && photo.keyframeTimes().isEmpty())
            val file=File(java.net.URI(photo.mediaFill!!.uri))
            check(file.isFile && store.inMediaFolder(file.toURI().toString()))
            val pixels=png(file.path)
            check(greens(pixels)>1000) { "Wrong source frame: trim and 2x should select the green half" }
            val plan=FramePhotos.plan(timed,501,75,2f,file.toURI().toString(),"Expected",999)!!
            val expected=engine.exportSession(plan.capture.toXml()) { still(at,plan.width,plan.height) }!!
            check(difference(expected,pixels)<=1) { "Source photo differs from the decoded frame" }
            state.undo(); check(state.document.node==before.node && file.isFile)
            state.redo(); check(state.scene.layer(made)!=null && file.isFile)
            state.endGesture(); saver.flush().get()
            check(store.load(id)!!.layer(made)!!.mediaFill!!.uri==file.toURI().toString())
            // Snapshot effects and alpha into the project, then display the photo on its own.
            state.edit { document -> document.update(made) { it.copy(node=it.node.copy(children=it.node.children+
                XmlNode("effect",mapOf("id" to "lab.true.grade"),listOf(XmlNode("property",mapOf("name" to "exposure","type" to "float","value" to "-1")))))) }
                .add(Layer(XmlNode("shape",mapOf("id" to "503","startTime" to "0","endTime" to "4499","fillType" to "color"),listOf(
                    XmlNode("transform",children=listOf(XmlNode("location",mapOf("value" to "40,40,0")))),
                    XmlNode("fillColor",mapOf("value" to "#801060ff")),XmlNode("path",mapOf("d" to "M-35,-35L35,-35L35,35L-35,35Z")),
                )))) }
            state.frame=75
            val composition=state.scene
            val reference=engine.exportSession(composition.toXml()) { still(state.timeMs,320,320) }!!
            val snapshot=state.captureFramePhoto(context,false,1f)
            val snapshotLayer=state.scene.layer(snapshot)!!
            val standalone=Scene(state.scene.node.copy(children=state.scene.node.children.filter { it.name=="media" }+snapshotLayer.node))
            val shown=engine.exportSession(standalone.toXml()) { still(standalone.frameStart(75),320,320) }!!
            check(difference(reference,shown)<=2) { "Composition photo changed the captured pixels" }
            // A scene edit during capture must discard the result, including its private PNG.
            val pngCount=folder.listFiles { f->f.extension=="png" }!!.size
            val stale=async(start=CoroutineStart.UNDISPATCHED) { runCatching { state.captureFramePhoto(context,false,1f) } }
            state.edit { it.withTitle("Changed during capture") }
            check(stale.await().exceptionOrNull()?.message==context.getString(app.absentmotion.R.string.true_photo_changed))
            check(folder.listFiles { f->f.extension=="png" }!!.size==pngCount)
            val cancelled=launch(start=CoroutineStart.UNDISPATCHED) { state.captureFramePhoto(context,false,1f) }
            cancelled.cancelAndJoin()
            check(folder.listFiles { f->f.extension=="png" }!!.size==pngCount)
            // Capturing inside a nested composition must still save the entire root project.
            val group=Layer(XmlNode("embedScene",mapOf("id" to "601","label" to "Video group","startTime" to "0","endTime" to "4499"),listOf(
                XmlNode("transform",children=listOf(XmlNode("location",mapOf("value" to "160,160,0")))),timed.node,
            )))
            val grouped=base.add(group,listOf(media))
            val nested=EditorState(id,grouped,store,engine,AppSettings(context),saver)
            check(nested.enterGroup(601)); nested.selectedId=501; nested.frame=75
            val inside=nested.captureFramePhoto(context,true,1f)
            check(nested.document.layer(601)!!.nested!!.layer(inside)!=null)
            nested.endGesture(); saver.flush().get()
            check(store.load(id)!!.layer(601)!!.nested!!.layer(inside)!=null)
            // 100x uses complete output frames and keeps the audio mix active until its source end.
            val fast=source.update(501) { source.withPlaybackSpeed(it,100f,0f,0) }
            check(fast.totalTime==99 && fast.showsAnyFrame(0,fast.totalTime))
            val first=engine.exportSession(fast.toXml()) { still(16,320,320) }!!
            val second=engine.exportSession(fast.toXml()) { still(50,320,320) }!!
            check(greens(first)<100 && greens(second)>1000) { "100x video did not advance from red to green" }
            val mix=checkNotNull(OfflineMix.of(context,fast,Resampler.HIGH,48000))
            var rms=0.0
            try {
                val sound=(mix as FloatMix).floats(10_000,60_000)
                rms=sqrt(sound.sumOf { it.toDouble()*it }/sound.size)
                check(rms>.005) { "100x audio is silent: $rms" }
                FileOutputStream(File(directory,"speed100.mp4")).use { output ->
                    exportVideo(engine,fast,fast.toXml(),ExportSettings(320,30),output.fd,mix,progress={})
                }
            } finally { (mix as? Closeable)?.close() }
            check(engine.stillPng(fast.toXml(),50,320,320,File(directory,"speed100-reference.png")))
            val extractor=MediaExtractor()
            val mimes=try { extractor.setDataSource(File(directory,"speed100.mp4").path); List(extractor.trackCount) { extractor.getTrackFormat(it).getString("mime") } } finally { extractor.release() }
            check("video/avc" in mimes && "audio/mp4a-latm" in mimes)
            // Keep independent fixtures for manual UI checks after instrumentation.
            nested.endGesture(); saver.flush().get()
            store.save(id,source)
            File(directory,"frame-source.png").writeBytes(file.readBytes())
            File(directory,"frame-source.xml").writeText(plan.capture.toXml())
            val langs=listOf("en","ru","zh-CN").map { tag ->
                val config=android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
                context.createConfigurationContext(config).getString(app.absentmotion.R.string.true_photo_title)
            }
            check(langs==listOf("Frame to photo layer","Кадр в фотослой","帧转照片图层"))
            JSONObject().put("sourceTrimSpeedExact",true).put("sourceFrameGreenPixels",greens(pixels)).put("staticPoseNoEffectsNoAudio",true)
                .put("projectPrivatePng",true).put("undoRedoKeepsPng",true).put("reopen",true).put("compositionPixelsMaxError",difference(reference,shown))
                .put("staleDiscardAndCleanup",true).put("cancelCleanup",true).put("nestedRootSaved",true)
                .put("speed100VideoAdvances",true).put("speed100AudioRMS",rms).put("speed100Tracks",JSONArray(mimes)).put("speed100DurationMs",100)
                .put("UILanguages",JSONArray(langs))
        } finally { engine.close() }
    }
}

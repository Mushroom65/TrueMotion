package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.engine.NativeEngine
import app.absentmotion.export.writeAlightPackage
import app.absentmotion.library.*
import app.absentmotion.model.*
import app.truemotion.vector.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import kotlin.math.abs

internal object Next012Probe {
    fun run(context: Context, engine: NativeEngine, directory: File): JSONObject = runBlocking {
        val report = JSONObject()
        fun render(xml: String, name: String): ByteArray {
            engine.setCameraView(true)
            engine.setScene(xml)
            val pixels = checkNotNull(engine.still(0.0, 256, 256))
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            check((3 until pixels.size step 4).count { (pixels[it].toInt() and 255) > 0 } > 100) { "Empty $name" }
            return pixels
        }
        val square = """<shape id="1" startTime="0" endTime="999" fillType="color"><transform><location value="128,128,0"/></transform><fillColor value="#ffed335d"/><path d="M-40,-40 L40,-40 L40,40 L-40,40 Z"/></shape>"""
        fun scene(camera: String = "", content: String = square) = """<scene width="256" height="256" fps="30" totalTime="999" bgcolor="#00000000">$content$camera</scene>"""
        fun camera(x: Float = 0f, y: Float = 0f, z: Float = 0f, ortho: Boolean = false) = """<camera id="2" label="Camera XYZ" startTime="0" endTime="999" type="${if (ortho) "orthographic" else "perspective"}"><transform><location value="128,128,-221.7025"/><tilt value="$x,$y"/><rotation value="$z"/></transform><fov value="60"/></camera>"""
        val neutral = render(scene(), "camera-no-layer")
        val flat = render(scene(camera()), "camera-neutral")
        check(neutral.contentEquals(flat)) { "A neutral camera changed a flat project" }
        val cases = JSONArray()
        for (ortho in listOf(false, true)) for ((label, xyz) in listOf("x" to floatArrayOf(8f,0f,0f), "y" to floatArrayOf(0f,8f,0f), "z" to floatArrayOf(0f,0f,30f), "xyz" to floatArrayOf(5f,-7f,15f))) {
            val xml = scene(camera(xyz[0],xyz[1],xyz[2],ortho))
            val pixels = render(xml, "camera-$label-$ortho")
            check(!pixels.contentEquals(flat)) { "Camera $label had no effect" }
            val quad = checkNotNull(engine.layerQuad(1,0.0))
            check(quad.size == 8 && quad.all(Float::isFinite))
            if (!ortho && label == "y") {
                val left = abs(quad[7]-quad[1]); val right = abs(quad[5]-quad[3])
                check(abs(left-right) > 1f) { "Camera Y did not create perspective foreshortening: ${quad.toList()}" }
            }
            val reopened = render(Scene.parse(xml).toXml(), "camera-reopen-$label-$ortho")
            check(pixels.contentEquals(reopened)) { "Camera changed on save/reopen" }
            cases.put(JSONObject().put("axis",label).put("orthographic",ortho).put("quad",JSONArray(quad.toList())))
        }
        report.put("cameraNeutralExact",true).put("cameraCases",cases)
        // A new canonical marker and the old template marker must both reopen as editable animation.
        val doc = VectorDocument(256,256,30.0,30)
        val curve = VectorDocument.Shape().apply {
            closed = true; strokeWidth = 0.0; fill = 0xfff2a531.toInt()
            nodes.add(VectorDocument.Node(20.0,20.0)); nodes.add(VectorDocument.Node(150.0,20.0)); nodes.add(VectorDocument.Node(80.0,150.0))
        }
        val image = File(context.filesDir,"media/next012-probe/vector-source.wav").apply { parentFile!!.mkdirs() }
        val pcm = ByteArray(44100 * 2)
        val wave = ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).put(pcm)
        image.writeBytes(wave.array())
        doc.tracks[0].keys[0]!!.shapes.add(curve)
        val media = VectorDocument.Shape().apply { mediaUri=image.toURI().toString(); mediaType="audio"; mediaDuration=1000 }
        doc.tracks[0].keys[0]!!.shapes.add(media)
        val marker = VectorAnimation.marker(doc)
        val vector = Layer(XmlNode("embedScene", mapOf("id" to "3", "label" to "Editable vector", "startTime" to "0", "endTime" to "999", VectorAdapter.DocumentAttribute to marker), listOf(XmlNode.parse(doc.nativeScene()))))
            .withTransform("location",128f,128f,0f)
        check(VectorAdapter.isVector(vector))
        check(VectorAdapter.isVector(vector.copy(node=vector.node.with(VectorAdapter.DocumentAttribute,null).with("templatePPId",marker))))
        check(!VectorAdapter.isVector(vector.copy(node=vector.node.with(VectorAdapter.DocumentAttribute,null))))
        val vectorScene = Scene(XmlNode("scene",mapOf("title" to "012 Vector + camera", "width" to "256", "height" to "256", "fps" to "30", "totalTime" to "999"),listOf(vector.node,XmlNode.parse(camera()))))
        render(vectorScene.toXml(),"vector-inside-main-scene")
        val out = ByteArrayOutputStream()
        writeAlightPackage(vectorScene,vectorScene.toXml(),out,MediaAccess.forPhone(context.filesDir),{ File(URI(it)).inputStream() },{})
        val packageFile = File(directory,"editable-vector-camera.alight").apply { writeBytes(out.toByteArray()) }
        val isolated = File(directory,"roundtrip").apply { mkdirs() }
        val store = ProjectStore(isolated)
        val imported = Importer(store, access=MediaAccess.forPhone(isolated)).import { packageFile.inputStream() }.single()
        val restored = store.load(imported.id)!!
        val restoredVector = restored.layers.single { it.kind=="embedScene" }
        check(VectorAdapter.isVector(restoredVector))
        val decoded = VectorAnimation.decode(VectorAdapter.marker(restoredVector)!!)
        val shapes = decoded.tracks[0].keys[0]!!.shapes
        check(shapes.first().nodes.size==3)
        val importedMedia = shapes.single { it.mediaUri.isNotEmpty() }.mediaUri
        check(importedMedia != media.mediaUri && File(URI(importedMedia)).readBytes().contentEquals(image.readBytes()))
        check(restored.node.storedMediaUris().none { it == media.mediaUri })
        check(restored.layers.single { it.kind=="camera" }.node.child("transform") == XmlNode.parse(camera()).child("transform"))
        val uiId = Importer.forApp(context,ProjectStore(context)).import { packageFile.inputStream() }.single().id
        NativeEngine(NativeEngine.Backend.GL).use { gl ->
            check(!gl.failed) { gl.info }
            gl.setCameraView(true); gl.setScene(scene(camera(5f,-7f,15f)))
            val pixels = checkNotNull(gl.still(0.0,256,256))
            check((3 until pixels.size step 4).count { (pixels[it].toInt() and 255)>0 } > 100)
            report.put("openGLCamera",JSONObject(gl.info))
        }
        report.put("alightVectorAndMediaRoundtrip",true).put("legacyVectorMarker",true).put("uiProject",uiId)
        report
    }
}

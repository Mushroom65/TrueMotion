package app.truemotion.port

import android.content.Context
import android.media.MediaExtractor
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.NativeEngine
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.exportVideo
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.*
import app.absentmotion.truemotion.Particles
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal object ParticlesProbe {
    fun run(context:Context,native:NativeEngine,directory:File):JSONObject=runBlocking {
        val catalog=EffectCatalog.parse { context.assets.open("effects/$it") }
        val def=checkNotNull(catalog.find(Particles.ID))
        fun property(layer:Layer,name:String,value:String)=layer.withEffect(0) { it.withEffectProperty(name,def.params.first { p->p.id==name }.type,value,0f,0f..0f) }
        var scene=Scene.create("True Particles",320,320,30,0)
        var emitter=Particles.create(scene,0,def,"Particles").withId(301).withTimes(0,199999)
        for((name,value) in mapOf("seed" to "9223372036854775807","amount" to "64","worldScale" to "230","turbulence" to "0.25","drag" to "0.3","attractStrength" to "0.1")) emitter=property(emitter,name,value)
        scene=scene.add(emitter)
        fun render(document:Scene,at:Double):ByteArray {
            native.setScene(document.node.toXml())
            return checkNotNull(native.still(at,320,320))
        }
        fun area(p:ByteArray)=(3 until p.size step 4).count { (p[it].toInt() and 255)>8 }
        val late=render(scene,183123.0)
        check(area(late)>150) { "No particles after 183 seconds" }
        native.still(0.0,320,320); native.still(199333.0,320,320)
        check(late.contentEquals(native.still(183123.0,320,320))) { "Particle seek changed the result" }
        val reopened=Scene.parse(scene.toXml())
        check(late.contentEquals(render(reopened,183123.0))) { "Reopen changed seed or simulation" }
        val frame=render(scene,1370.0)
        native.setScene(scene.node.toXml())
        for(i in 0..40) native.still(i*33.0,320,320)
        check(frame.contentEquals(native.still(1370.0,320,320))) { "Frame order affects particles" }
        val source=Layer(XmlNode("shape",mapOf("id" to "302","label" to "Texture source","startTime" to "0","endTime" to "199999","hidden" to "true","fillType" to "color"),listOf(
            XmlNode("fillColor",mapOf("value" to "#ff00d9a5")),XmlNode("path",mapOf("d" to "M-30,-30L30,-30L30,30L-30,30Z")),
        )))
        var texture=Particles.withSource(emitter,0,302)
        for((name,value) in mapOf("tint" to "#ffffffff","colorStart" to "#ffffffff","colorEnd" to "#ffffffff","additive" to "false","radius" to ".15")) texture=property(texture,name,value)
        val textured=scene.update(301) { texture }.add(source)
        val green=render(textured,1370.0)
        fun greenCount(p:ByteArray)=(0 until p.size step 4).count { (p[it+1].toInt() and 255)>(p[it].toInt() and 255)+40 && (p[it+3].toInt() and 255)>10 }
        check(greenCount(green)>300) { "Hidden layer texture was not rendered: ${greenCount(green)}" }
        val red=textured.update(302) { it.withFill("color",0xffff2040.toInt()) }
        check(!green.contentEquals(render(red,1370.0))) { "Changing a dependency did not update particles" }
        // A tiny original rectangle cannot crop the generated geometry or selection.
        val tiny=scene.update(301) { it.copy(node=it.node.copy(children=it.node.children.filterNot { n->n.name=="property"&&n["name"]=="size" }+
            XmlNode("property",mapOf("name" to "size","type" to "vec2","value" to "1,1")))) }
        check(frame.contentEquals(render(tiny,1370.0))) { "Original shape bounds cropped particles" }
        val bounds=native.localBounds(301,1370.0)!!
        check(bounds[2]-bounds[0]>20 && bounds[3]-bounds[1]>20)
        val changed=scene.update(301) { property(it,"gravityX","2") }
        check(!frame.contentEquals(render(changed,1370.0))) { "Gravity X did not affect the native render" }
        val cycle=textured.update(302) { original -> original.addEffect(def.instance()).let { Particles.withSource(it,0,301) } }
        render(cycle,1000.0) // Imported cycles must terminate even if the UI disallows them.
        val groups=source.copy(node=XmlNode("embedScene",source.node.attrs,listOf(XmlNode("scene",mapOf("width" to "80","height" to "80","fps" to "30","totalTime" to "199999"),listOf(
            source.withId(303).withHidden(false).withTransform("location",40f,40f,0f).node,
        )))))
        val grouped=textured.update(302) { groups }
        check(greenCount(render(grouped,1370.0))>200) { "Group texture failed" }
        val engine=EngineThread(0,context)
        try {
            val noBackground=textured.withSettings(320,320,30,0)
            engine.setScene(noBackground)
            val exact=engine.exportSession(noBackground.toXml()) { still(1370,320,320) }
            check(exact!=null && green.contentEquals(exact)) { "Particle exact export differs from native preview" }
            check(engine.stillPng(noBackground.toXml(),1370,320,320,File(directory,"particles.png")))
            val short=Scene(noBackground.node.with("totalTime","999").copy(children=noBackground.node.children.map { n->if(n.name in Scene.LAYER_TAGS) n.with("endTime","999") else n }))
            val file=File(directory,"particles.mp4")
            FileOutputStream(file).use { output->exportVideo(engine,short,short.toXml(),ExportSettings(320,30),output.fd,null,progress={}) }
            check(engine.stillPng(short.toXml(),500,320,320,File(directory,"particles-export-reference.png")))
            val extractor=MediaExtractor()
            val mime=try { extractor.setDataSource(file.absolutePath); List(extractor.trackCount) { extractor.getTrackFormat(it).getString("mime") } } finally { extractor.release() }
            check(mime.any { it?.startsWith("video/")==true })
            val videoFile=File(directory,"particle-reference.mp4")
            context.createPackageContext("app.truemotion.test",0).assets.open("particle-reference.mp4").use { input -> videoFile.outputStream().use(input::copyTo) }
            val videoSource=source.copy(node=source.node.with("fillType","media").with("fillVideo",videoFile.toURI().toString())
                .with("inTime","0").with("outTime","1000").with("endTime","999"))
            val videoScene=short.update(302) { videoSource }.withMedia(MediaDeclaration(XmlNode("media",mapOf(
                "uri" to videoFile.toURI().toString(),"type" to "video/mp4","width" to "320","height" to "320","duration" to "1000000","fps" to "30",
            ))))
            var videoPixels:ByteArray?=null
            repeat(3) {
                for(at in listOf(0,467,900)) {
                    videoPixels=engine.exportSession(videoScene.toXml()) { still(at,320,320) }
                    check(videoPixels!=null && greenCount(videoPixels!!)>50) { "Hidden reference video failed at $at ms" }
                }
            }
            File(directory,"particles-video.xml").writeText(videoScene.toXml())
            videoPixels?.let { File(directory,"particles-video.rgba").writeBytes(it) }
            val visibleVideo=videoScene.remove(301).update(302) { it.withHidden(false).withTransform("location",160f,160f,0f) }
            val visiblePixels=engine.exportSession(visibleVideo.toXml()) { still(500,320,320) }
            visiblePixels?.let { File(directory,"particles-video-source.rgba").writeBytes(it) }
            check(videoPixels!=null && greenCount(videoPixels)>50 && visiblePixels!=null && greenCount(visiblePixels)>50)
            ProjectStore(context).save("true-particles-regression",textured.withTitle("True Particles"))
            JSONObject().put("largeSeedAfter183Seconds",true).put("frameOrderIndependent",true).put("reopenExact",true)
                .put("hiddenLayerTextureGreenPixels",greenCount(green)).put("sourceEditsUpdate",true).put("groupTexture",true)
                .put("sourceCycleTerminates",true).put("originalBoundsDoNotCrop",true).put("gravityXResponds",true)
                .put("exactExportPixelsEqual",true).put("png",true).put("mp4Tracks",JSONArray(mime)).put("mp4Bytes",file.length())
                .put("hiddenVideoDecoded",true)
                .put("hiddenVideoColdCaptures",9)
        } finally { engine.close() }
    }
}

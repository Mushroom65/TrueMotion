package app.truemotion.port

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import app.absentmotion.compat.withImportedGeometry
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.NativeEngine
import app.absentmotion.library.Importer
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.*
import app.absentmotion.truemotion.Particles
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.*

internal object Next013Probe {
    fun run(context:Context,native:NativeEngine,directory:File):JSONObject=runBlocking {
        val report=JSONObject()
        val catalog=EffectCatalog.parse { context.assets.open("effects/$it") }
        val definition=catalog.find(Particles.ID)!!
        fun property(layer:Layer,name:String,value:String)=layer.withEffect(0) { it.withEffectProperty(name,definition.params.first { p->p.id==name }.type,value,0f,0f..0f) }
        fun save(p:ByteArray,name:String) {
            val b=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
            b.isPremultiplied=false
            b.copyPixelsFromBuffer(ByteBuffer.wrap(p));File(directory,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
        }
        fun render(s:Scene,at:Double=2000.0,name:String?=null):ByteArray {
            native.setCameraView(true);native.setScene(s.toXml());val p=checkNotNull(native.still(at,320,320));if(name!=null)save(p,name);return p
        }
        fun area(p:ByteArray)=(3 until p.size step 4).count { (p[it].toInt() and 255)>8 }
        val base=Scene.create("013 3D particles",320,320,30,0)
        var emitter=Particles.create(base,0,definition,"3D particles").withId(1301)
        for((name,value) in mapOf("dimension" to "1","amount" to "96","emitter" to "2","spawnX" to ".45","spawnY" to ".25","spawnZ" to ".5","spread" to "0","velocityX" to "0","velocityY" to "0","velocityZ" to "0","gravity" to "0","sizeEnd" to "1","worldScale" to "200","softFade" to "false","opacityEnd" to "1","radius" to ".035")) emitter=property(emitter,name,value)
        val world=base.add(emitter)
        val front=render(world,name="particles-front")
        val side=render(world.update(1301) { it.withTilt(0f,90f) },name="particles-side")
        val sideBounds=checkNotNull(native.layerBounds(1301,2000.0))
        check(sideBounds[2]-sideBounds[0]>100 && sideBounds[3]-sideBounds[1]>50) { "3D selection bounds collapsed: ${sideBounds.toList()}" }
        val quad=checkNotNull(native.layerQuad(1301,2000.0))
        check(abs(quad[2]-quad[0])>100) { "3D selection quad collapsed" }
        var selected=false
        for(y in 50..270 step 20) for(x in 20..300 step 20) if(native.hitTest(x.toFloat(),y.toFloat(),2000.0)==1301L) selected=true
        check(selected) { "Edge-on world particles cannot be selected" }
        check(area(side)>200) { "3D particles collapsed to a plane: ${area(side)}" }
        check(!front.contentEquals(side)) { "Turning 3D particles did not rotate their positions" }
        val flat=world.update(1301) { property(it.withTilt(0f,90f),"dimension","0") }
        val flatSide=render(flat,name="particles-2d-side")
        check(area(side)>area(flatSide)*2+100) { "2D/3D projection did not differ: ${area(side)},${area(flatSide)}" }
        check(side.contentEquals(render(Scene.parse(world.update(1301) { it.withTilt(0f,90f) }.toXml()))))
        NativeEngine(NativeEngine.Backend.GL).use { gl ->
            check(!gl.failed) { gl.info };gl.setCameraView(true)
            gl.setScene(world.update(1301) { it.withTilt(0f,90f) }.toXml())
            check(area(checkNotNull(gl.still(2000.0,320,320)))>200)
        }
        val camera=Layer(XmlNode.parse("""<camera id="1302" startTime="0" endTime="3999"><transform><location value="160,160,-277.128"/><tilt value="15,10"/></transform><fov value="60"/></camera>"""))
        val viewed=render(world.add(camera),name="particles-project-camera")
        check(!front.contentEquals(viewed) && area(viewed)>100)
        val near=render(world.update(1301) { property(it,"originZ","-.5") })
        val far=render(world.update(1301) { property(it,"originZ",".5") })
        check(area(near)>area(far)) { "Particle depth did not affect projected size" }
        val ortho=Layer(camera.node.with("type","orthographic")).withTilt(0f,0f)
        val orthoNear=render(world.add(ortho).update(1301) { property(it,"originZ","-.5") })
        val orthoFar=render(world.add(ortho).update(1301) { property(it,"originZ",".5") })
        check(abs(area(orthoNear)-area(orthoFar))<=2) { "Orthographic particle size changed with depth" }
        val behind=render(world.add(ortho).update(1301) { property(it,"originZ","-3") })
        check(area(behind)==0) { "Particles behind an orthographic camera were not clipped" }
        report.put("particleOrthographic",true)
        val ui=ProjectStore(context).create(world.add(camera))
        val exporter=EngineThread(0,context)
        try {
            val short=Scene(world.update(1301) { it.withTilt(0f,90f) }.node.with("totalTime","499"))
            exporter.setScene(short)
            val exact=exporter.exportSession(short.toXml()) { still(2000,320,320) }
            check(side.contentEquals(exact)) { "World particle exact export differs from the viewport" }
            check(exporter.stillPng(short.toXml(),2000,320,320,File(directory,"particles-export.png")))
            val exported=android.graphics.BitmapFactory.decodeFile(File(directory,"particles-export.png").path)
            val pixels=IntArray(320*320)
            try { exported.getPixels(pixels,0,320,0,0,320,320) } finally { exported.recycle() }
            val pngError=pixels.indices.sumOf { i ->
                val c=pixels[i];listOf(c ushr 16 and 255,c ushr 8 and 255,c and 255,c ushr 24).indices.sumOf { j ->
                    abs((side[4*i+j].toInt() and 255)-when(j) { 0->c ushr 16 and 255;1->c ushr 8 and 255;2->c and 255;else->c ushr 24 }).toLong()
                }
            }.toDouble()/side.size
            check(pngError<.1) { "World particle PNG differs from the viewport: $pngError" }
            report.put("particlePNGMeanError",pngError)
            FileOutputStream(File(directory,"particles-3d.mp4")).use { output ->
                app.absentmotion.export.exportVideo(exporter,short,short.toXml(),app.absentmotion.export.ExportSettings(320,30),output.fd,null,progress={})
            }
            val extractor=android.media.MediaExtractor()
            try {
                extractor.setDataSource(File(directory,"particles-3d.mp4").path)
                check((0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString("mime")=="video/avc" })
            } finally { extractor.release() }
        } finally { exporter.close() }
        report.put("particleWorldAreas",JSONArray(listOf(area(front),area(side),area(flatSide))))
            .put("particleProjectCamera",true).put("particleDepthResponds",true).put("particleReopenExact",true).put("particleUIProject",ui)
            .put("particleSelection3D",true).put("particleOpenGL",true).put("particlePNGAndMP4",true)

        // Imported offset pivots must be composed before rotation/scale, including parents.
        val raw="""<scene am="com.alightcreative.tojimotionlab/5.0" width="320" height="320" fps="30" totalTime="999" bgcolor="#00000000"><nullobj id="1" startTime="0" endTime="999"><transform><location value="160,160,0"/><pivot value="30,-20"/><rotation value="25"/><scale value="1.5,.8"/></transform></nullobj><shape id="2" parent="1" startTime="0" endTime="999" fillType="color"><transform><location value="20,-30,0"/><pivot value="7,12"/><rotation value="40"/><scale value=".9,1.3"/></transform><fillColor value="#ff22cc88"/><path d="M-20,-20L20,-20L20,20L-20,20Z"/></shape></scene>"""
        val imported=Scene(XmlNode.parse(raw).withImportedGeometry())
        render(imported,0.0,"imported-parent-pivots")
        val matrix=checkNotNull(native.layerMatrix(2,0.0))
        fun original(x:Double,y:Double,lx:Double,ly:Double,px:Double,py:Double,sx:Double,sy:Double,angle:Double):Pair<Double,Double> {
            val a=Math.toRadians(angle);val dx=(x-px)*sx;val dy=(y-py)*sy
            return lx+px+cos(a)*dx-sin(a)*dy to ly+py+sin(a)*dx+cos(a)*dy
        }
        var maxError=0.0
        for((x,y) in listOf(-20.0 to -20.0,20.0 to -20.0,20.0 to 20.0,-20.0 to 20.0)) {
            val c=original(x,y,20.0,-30.0,7.0,12.0,.9,1.3,40.0)
            val p=original(c.first,c.second,160.0,160.0,30.0,-20.0,1.5,.8,25.0)
            val w=matrix[6]*x+matrix[7]*y+matrix[8]
            maxError=max(maxError,hypot((matrix[0]*x+matrix[1]*y+matrix[2])/w-p.first,(matrix[3]*x+matrix[4]*y+matrix[5])/w-p.second))
        }
        check(maxError<.001) { "Imported hierarchy matrix error: $maxError; ${matrix.toList()}" }
        val beforeAnchor=render(imported,0.0)
        val shifted=checkNotNull(native.reanchor(2,0.0,-15f,8f))
        val reanchored=imported.update(2) { it.withTransform("pivot",-15f,8f).withTransform("location",*shifted) }
        val afterAnchor=render(reanchored,0.0,"imported-anchor-edit")
        val anchorError=beforeAnchor.indices.sumOf { abs((beforeAnchor[it].toInt() and 255)-(afterAnchor[it].toInt() and 255)).toLong() }.toDouble()/beforeAnchor.size
        check(anchorError<.001) { "Editing imported anchor moved its geometry: $anchorError" }
        val reference=Scene.parse("""<scene width="320" height="320" fps="30" totalTime="999"><shape id="10" startTime="0" endTime="999" fillType="color"><transform><location value="50,60,0"/></transform><fillColor value="#ffff4040"/><path d="M-20,-20L20,-20L20,20L-20,20Z"/></shape></scene>""")
        val expected=render(reference,0.0)
        for(size in listOf(80,320,640)) {
            val xml="""<scene am="com.alightcreative.tojimotionlab" width="320" height="320" fps="30" totalTime="999"><embedScene id="11" startTime="0" endTime="999"><transform><location value="50,60,0"/><pivot value="20,100"/></transform><scene width="$size" height="$size" fps="30" totalTime="999"><shape id="10" startTime="0" endTime="999" fillType="color"><transform><location value="${size/2},${size/2},0"/></transform><fillColor value="#ffff4040"/><path d="M-20,-20L20,-20L20,20L-20,20Z"/></shape></scene></embedScene></scene>"""
            check(expected.contentEquals(render(Scene(XmlNode.parse(xml).withImportedGeometry()),0.0,"imported-group-$size"))) { "Group size/pivot moved its contents: $size" }
        }
        report.put("importedParentMatrixError",maxError).put("importedGroupsExact",JSONArray(listOf(80,320,640)))
            .put("importedAnchorEditMeanError",anchorError)
        val follow=XmlNode.parse("""<scene am="com.alightcreative.tojimotionlab" width="320" height="320" fps="30" totalTime="999"><shape id="15" startTime="0" endTime="999" fillType="none"><transform><location value="100,100,0"/></transform><path d="M-20,0L20,0"/></shape><shape id="16" startTime="0" endTime="999" fillType="color"><transform><location value="20,20,0"/><pivot value="10,5"/></transform><path d="M-5,-5L5,-5L5,5L-5,5Z"/><effect id="absent.movealongpath"><property name="progress" type="float" value=".5"/><property name="rotate" type="bool" value="false"/></effect></shape></scene>""").withImportedGeometry()
        render(Scene(follow),0.0)
        val followed=checkNotNull(native.layerMatrix(16,0.0))
        check(abs(followed[0]*10+followed[1]*5+followed[2]-100)<.001 && abs(followed[3]*10+followed[4]*5+followed[5]-100)<.001) { "Imported layer no longer follows a path" }
        report.put("importedFollowPath",true)
        // Standard path-stroke must remain aligned under opacity layers and nested group offsets.
        check(native.loadEffect("""<effect id="probe.identity" sksl="uniform shader image; half4 main(float2 p) { return image.eval(p); }"/>""")=="ok")
        val strokeShape="""<shape id="1401" startTime="0" endTime="999" fillType="color"><transform><location value="160,160,0"/><opacity value=".5"/></transform><fillColor value="#ff22cc88"/><path d="M-40,-30L40,-30L40,30L-40,30Z"/><path-stroke enabled="true" cap="butt" join="miter"><size value="16"/><color value="#ffff2255"/></path-stroke></shape>"""
        val strokeCases=JSONArray()
        for((x,y) in listOf(160 to 160,90 to 110,240 to 220)) {
            val plain=Scene.parse("""<scene width="320" height="320" fps="30" totalTime="999">${strokeShape.replace("160,160,0","$x,$y,0")}</scene>""")
            val expectedStroke=render(plain,0.0)
            val grouped=Scene.parse("""<scene width="320" height="320" fps="30" totalTime="999"><embedScene id="1402" startTime="0" endTime="999"><transform><location value="$x,$y,0"/></transform><scene width="320" height="320" fps="30" totalTime="999">$strokeShape</scene></embedScene></scene>""")
            val actual=render(grouped,0.0,"stroke-group-$x-$y")
            val mean=actual.indices.sumOf { abs((actual[it].toInt() and 255)-(expectedStroke[it].toInt() and 255)).toLong() }.toDouble()/actual.size
            check(mean<.001) { "Stroke/group opacity displaced or cropped its contents at $x,$y: $mean" }
            val withIdentity=Scene.parse(grouped.toXml().replace("</path-stroke>","</path-stroke><effect id=\"probe.identity\"/>"))
            val effected=render(withIdentity,0.0,"stroke-filter-$x-$y")
            val filterError=actual.indices.sumOf { abs((actual[it].toInt() and 255)-(effected[it].toInt() and 255)).toLong() }.toDouble()/actual.size
            check(filterError<.01) { "Effect target shifted/clipped standard stroke: $filterError at $x,$y" }
            strokeCases.put(JSONObject().put("x",x).put("y",y).put("meanError",mean).put("filterError",filterError))
        }
        report.put("standardStrokeGroups",strokeCases)
        val resolver=context.contentResolver
        val media=checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"tm-013-relink-probe.png")
            put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/TrueMotionTests")
        }))
        try {
            val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff22cc88.toInt()) }
            try { resolver.openOutputStream(media)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
            val xml="""<scene width="320" height="320" fps="30" totalTime="999"><shape id="9" fillType="media" fillImage="$media"/></scene>"""
            val store=ProjectStore(context)
            val result=Importer.forApp(context,store).import { xml.byteInputStream() }.single()
            check(store.load(result.id)!!.node.mediaUris()==setOf(media.toString())) { "XML lost accessible MediaStore reference without declarations" }
            val byName=xml.replace(media.toString(),"file:///old-phone/tm-013-relink-probe.png")
            val named=Importer.forApp(context,store).import { byName.byteInputStream() }.single()
            val restored=store.load(named.id)!!.node.mediaUris()
            check(restored.size==1 && app.absentmotion.library.MediaRelink.opens(context,restored.single())) { "Filename relink failed: $restored" }
            store.delete(result.id);store.delete(named.id)
            report.put("xmlMediaWithoutDeclarations",true).put("xmlUniqueFilenameRelink",true)
        } finally { resolver.delete(media,null,null) }
        val fixture=File(context.filesDir,"v013-input.alight")
        if(fixture.exists()) {
            val store=ProjectStore(context)
            val result=Importer.forApp(context,store).import { fixture.inputStream() }.single()
            val scene=store.load(result.id)!!
            val missing=scene.node.mediaUris().filter { uri -> runCatching { context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri),"r")?.use { true } }.getOrDefault(false)!=true }
            check(missing.isEmpty()) { "Packaged media reported missing: $missing" }
            val engine=EngineThread(0,context)
            try {
                for(at in listOf(0,3000,10000,72000)) check(engine.stillPng(scene.toXml(),at,320,320,File(directory,if(at==0) "project122-import.png" else "project122-$at.png")))
            } finally { engine.close() }
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) }
            report.put("project122Imported",result.id).put("project122Media",scene.node.mediaUris().size).put("project122MissingMedia",0)
                .put("project122SourceSHA256",digest).put("project122FramesMs",JSONArray(listOf(0,3000,10000,72000)))
        }
        report
    }
}

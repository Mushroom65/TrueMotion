package app.truemotion.port

import android.content.Context
import app.absentmotion.editor.EditorState
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.NativeEngine
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.exportVideo
import app.absentmotion.library.*
import app.absentmotion.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2

internal object MotionPathsProbe {
    fun run(context: Context, native: NativeEngine, directory: File): JSONObject = runBlocking {
        val raw=Scene.parse("""<scene title="True Motion Path" width="400" height="400" fps="30" totalTime="1999" bgcolor="#00000000"><shape id="701" label="Motion path" startTime="0" endTime="1999" fillType="color"><transform><location><kf t="0" v="60,280,0"/><kf t="1" v="340,280,0"/></location><rotation value="20"/></transform><fillColor value="#ff00d9a5"/><path d="M-22,-10L22,-10L22,10L-22,10Z"/></shape></scene>""")
        val source=raw.update(701) { it.withPathHandle(0,false,PathVector(0f,-240f),false).withPathHandle(1,true,PathVector(0f,-240f),false).withPathAutoOrient(true) }
        val tests=mutableListOf<String>()
        fun quad(scene:Scene,ms:Int):FloatArray { native.setScene(scene.toXml()); return native.layerQuad(701,ms.toDouble())!! }
        var maxError=0f; var maxAngle=0.0
        for(ms in listOf(0,100,250,500,750,1000,1250,1500,1750,1900,1999)) {
            val q=quad(source,ms); val l=source.layerAt(701,ms)!!
            val x=(0..3).sumOf { q[it*2].toDouble() }.toFloat()/4
            val y=(0..3).sumOf { q[it*2+1].toDouble() }.toFloat()/4
            val error=maxOf(abs(x-l.location[0]),abs(y-l.location[1]))
            check(error<.02f) { "Native/Kotlin path at $ms: $x,$y vs ${l.location.toList()}" }
            maxError=maxOf(maxError,error)
            val angle=Math.toDegrees(atan2((q[3]-q[1]).toDouble(),(q[2]-q[0]).toDouble()))
            val expected=20+l.pathAngle()
            val delta=abs(((angle-expected+540)%360)-180)
            check(delta<.02) { "Native auto orient at $ms: $angle vs $expected" }
            maxAngle=maxOf(maxAngle,delta)
        }
        tests+="nativePositionAndOrientation11Times"
        val eased=source.update(701) { l -> l.withElement(PropRef.Location,l.element(PropRef.Location)!!.withKeyframes(l.keyframes(PropRef.Location).mapIndexed { i,k -> if(i==1) k.copy(e="cubicBezier 0.42 0 1 1") else k })) }
        val q=quad(eased,500); val expected=eased.layerAt(701,500)!!.location
        check(abs((0..3).sumOf { q[it*2].toDouble() }/4-expected[0])<.02)
        tests+="temporalEasingPreserved"
        native.setScene(source.toXml()); val original=native.still(1000.0,400,400)!!
        val plain=source.update(701) { it.withMotionPath(false).withPathAutoOrient(false) }
        native.setScene(plain.toXml()); check(!original.contentEquals(native.still(1000.0,400,400)!!))
        tests+="curveChangesRenderedPixels"
        val legacyEffect=XmlNode.parse("""<effect id="lab.toji.motionPath"><property name="inX" type="float" value="0"/><property name="inY" type="float" value="-240"/><property name="outX" type="float" value="0"/><property name="outY" type="float" value="-240"/></effect>""")
        val legacy=raw.update(701) { it.copy(node=it.node.copy(children=it.node.children+legacyEffect)) }
        val converted=Scene.parse(legacy.toXml())
        val expectedLegacy=source.update(701) { it.withPathAutoOrient(false) }
        native.setScene(converted.toXml()); val legacyPixels=native.still(1000.0,400,400)!!
        native.setScene(expectedLegacy.toXml()); check(legacyPixels.contentEquals(native.still(1000.0,400,400)!!))
        tests+="legacyControlSchemaNativePixelsExact"
        val wrapper=Layer(XmlNode("embedScene",mapOf("id" to "710","label" to "Path group","startTime" to "0","endTime" to "1999"),listOf(
            XmlNode("transform",children=listOf(XmlNode("location",mapOf("value" to "200,200,0")))),source.node,
        )))
        val grouped=Scene.create("Path group project",400,400,30,0).add(wrapper)
        native.setScene(grouped.toXml()); val inside=native.still(1000.0,400,400)!!
        check(original.indices.maxOf { abs((original[it].toInt() and 255)-(inside[it].toInt() and 255)) }<=1)
        tests+="nestedGroupRenderMatchesRoot"
        val store=ProjectStore(context); val id="true-motion-path-regression"
        val engine=EngineThread(0,context)
        val saver=ProjectSaver(id,store,Recovery(File(directory,"path-recovery")),failed={ error->throw AssertionError("Path save",error) })
        try {
            val state=EditorState(id,grouped,store,engine,AppSettings(context),saver)
            check(state.enterGroup(710)); state.selectedId=701
            val before=state.document
            withContext(Dispatchers.Main) {
                state.editSelected(key="path-drag") { it.withPathHandle(0,false,PathVector(20f,-200f),true) }
                state.editSelected(key="path-drag") { it.withPathHandle(0,false,PathVector(40f,-180f),true) }
                state.endGesture()
            }
            val changed=state.document
            state.undo(); check(state.document.node==before.node)
            state.redo(); check(state.document.node==changed.node)
            state.endGesture(); saver.flush().get()
            check(store.load(id)!!.groupScene(listOf(710))!!.layer(701)!!.keyframes(PropRef.Location)[0].pathHandle(false)==PathVector(40f,-180f))
            tests+="nestedSaveAndSingleGestureUndoRedo"
            FileOutputStream(File(directory,"motion-path.mp4")).use { output ->
                exportVideo(engine,source,source.toXml(),ExportSettings(400,30),output.fd,null,progress={})
            }
            for(frame in listOf(15,45)) check(engine.stillPng(source.toXml(),source.frameStart(frame),400,400,File(directory,"motion-path-frame-$frame.png")))
            File(directory,"motion-path.xml").writeText(source.toXml())
            tests+="nativeMp4ExportWithReferences"
            // A separate project for actual UI checks; no user projects are touched.
            store.save(id,source)
            val titles=listOf("en","ru","zh-CN").map { tag ->
                val config=android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
                context.createConfigurationContext(config).getString(app.absentmotion.R.string.true_path_title)
            }
            check(titles==listOf("Motion path","Траектория","运动路径"))
            JSONObject().put("checks",JSONArray(tests)).put("maxPositionError",maxError).put("maxAngleErrorDegrees",maxAngle).put("UILanguages",JSONArray(titles))
        } finally { engine.close(); saver.flush().get() }
    }
}

package app.truemotion.port

import android.graphics.Bitmap
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.Scene
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Device-only integration check for the actual Skia/GPU capture and depth compositor. */
internal object Depth017Probe {
    fun run(engine: NativeEngine, directory: File): JSONObject {
        val scene = Scene.parse("""<scene width="256" height="256" fps="30" totalTime="999" bgcolor="#ff000000" trueDepth3D="true">
            <shape id="1" s=".rect" startTime="0" endTime="999" fillType="color"><transform><location value="128,128,0"/><tilt value="0,50"/></transform><property name="size" type="vec2" value="100,70"/><fillColor value="#ffff0000"/></shape>
            <shape id="2" s=".rect" startTime="0" endTime="999" fillType="color"><transform><location value="128,128,0"/><tilt value="0,-50"/></transform><property name="size" type="vec2" value="100,70"/><fillColor value="#ff0000ff"/></shape>
            </scene>""")
        fun render(s: Scene): ByteArray { engine.setScene(s.toXml()); return checkNotNull(engine.still(0.0,256,256)) }
        fun channel(p: ByteArray,x: Int,y: Int,c: Int) = p[(y*256+x)*4+c].toInt() and 255
        fun save(p: ByteArray,name: String) {
            val image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            image.copyPixelsFromBuffer(ByteBuffer.wrap(p))
            File(directory,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
        }
        val crossing=render(scene)
        check(channel(crossing,100,128,0)>230 && channel(crossing,100,128,2)<20) { "Left intersection has incorrect depth" }
        check(channel(crossing,156,128,2)>230 && channel(crossing,156,128,0)<20) { "Right intersection has incorrect depth" }
        save(crossing,"crossing")
        val reverse=Scene(scene.node.copy(children=scene.node.children.reversed()))
        check(crossing.contentEquals(render(reverse))) { "Opaque depth depends on layer order" }
        val legacy=render(Scene(scene.node.with("trueDepth3D",null)))
        check(!crossing.contentEquals(legacy)) { "Shared depth fell back to whole-layer sorting" }
        val transparent=render(scene.update(2) { it.withFill("color",0x800000ff.toInt()) })
        check(channel(transparent,156,128,0) in 110..145 && channel(transparent,156,128,2) in 110..145) { "Transparent surface did not preserve the farther plane" }
        save(transparent,"transparency")
        val camera=Scene.parse(scene.toXml().replace("</scene>","""<camera id="3" type="perspective" startTime="0" endTime="999"><transform><location value="128,128,-221.7025"/><rotation value="90"/></transform><fov value="60"/></camera></scene>"""))
        val rotated=render(camera)
        check(channel(rotated,128,156,0)>230 && channel(rotated,128,100,2)>230) { "Scene camera did not rotate the shared-depth scene" }
        save(rotated,"camera")
        val rig=Scene.parse("""<scene width="256" height="256" fps="30" totalTime="999" bgcolor="#ff000000">
            <bone id="10" length="80" startTime="0" endTime="999"><transform><location value="128,128,40"/><rotation value="20"/><scale value="-1.5,0.6"/></transform></bone>
            <bone id="11" parent="10" length="50" startTime="0" endTime="999"><transform><location value="20,10,0"/><rotation value="40"/></transform></bone>
            <shape id="1" parent="11" s=".rect" startTime="0" endTime="999" fillType="color"><property name="size" type="vec2" value="5,5"/><fillColor value="#ffff0000"/></shape>
            <shape id="2" parent="11" s=".rect" startTime="0" endTime="999" fillType="color"><transform><location value="50,0,0"/></transform><property name="size" type="vec2" value="5,5"/><fillColor value="#ff00ff00"/></shape>
            <camera id="3" type="perspective" startTime="0" endTime="999"><transform><location value="144,120,-221.7025"/><rotation value="25"/></transform><fov value="60"/></camera>
            </scene>""")
        val handles=render(rig)
        val matrix=checkNotNull(engine.layerMatrix(11,0.0))
        fun point(x: Float): Pair<Int,Int> {
            val w=matrix[6]*x+matrix[8]
            return kotlin.math.round((matrix[0]*x+matrix[2])/w).toInt() to kotlin.math.round((matrix[3]*x+matrix[5])/w).toInt()
        }
        for((local,c) in listOf(0f to 0,50f to 1)) {
            val (x,y)=point(local)
            check(x in 0..255 && y in 0..255 && channel(handles,x,y,c)>230) {"Rig handle does not match rendered artwork at $local: $x,$y"}
        }
        save(handles,"rig")
        return JSONObject().put("rigProjectedHandles",true).put("backend",JSONObject(engine.info)).put("crossingPlanes",true).put("orderIndependent",true).put("transparency",true).put("sceneCamera",true)
    }
}

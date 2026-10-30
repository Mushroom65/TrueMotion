package app.absentmotion.editor

import app.absentmotion.model.*
import app.absentmotion.truemotion.FramePhotos
import org.junit.Assert.*
import org.junit.Test

class FramePhotosTest {
    private val scene = Scene.parse("""<scene width="320" height="240" fps="30" totalTime="4999"><media uri="clip" width="640" height="360" duration="9000000"/>
      <shape id="2" label="Clip" startTime="1000" endTime="4999" fillType="media" fillVideo="clip" inTime="500" outTime="8500" speed="2" mediaFillMode="fit" parent="8" genLoop="test">
       <transform><location><kf t="0" v="10,20,0"/><kf t="1" v="110,120,0"/></location><rotation value="90"/><pivot value="5,7"/></transform>
       <property name="size" type="vec2" value="160,90"/><effect id="lab.true.grade"><property name="exposure" value="-1"/></effect><gain value=".5"/>
       <audioEffects><fx id="echo"/></audioEffects><ai kind="background"/>
      </shape></scene>""")
    private fun plan(source: Long? = 2, document: Scene = scene, frame: Int = 45, seconds: Float = 2f, limit: Int = 4096) =
        FramePhotos.plan(document, source, frame, seconds, "file:///photo.png", "Photo", 77, limit)

    @Test fun sourceCaptureKeepsVideoClockAtNativeDimensions() {
        val capture = plan()!!.capture
        assertEquals(640, capture.width)
        assertEquals(360, capture.height)
        val raw = capture.layers.single()
        assertArrayEquals(floatArrayOf(320f,180f,0f),raw.location,0f)
        assertNull(raw.node["parent"])
        assertTrue(raw.effects.isEmpty())
        assertEquals(VideoTiming(scene.layer(2)!!).mediaMs(516.0),VideoTiming(raw).mediaMs(516.0),0.0)
    }

    @Test fun photoFreezesPoseAndKeepsGeometryWithoutEffectsOrAudio() {
        val photo = plan()!!.photo
        assertEquals(77L,photo.id)
        assertEquals(1500,photo.startTime)
        assertEquals(3499,photo.endTime)
        assertFalse(photo.mediaFill!!.video)
        assertEquals("file:///photo.png",photo.mediaFill!!.uri)
        assertArrayEquals(scene.layerAt(2,1500)!!.location,photo.location,.0002f)
        assertEquals(scene.layer(2)!!.pivot.toList(),photo.pivot.toList())
        assertEquals("90",photo.node.child("transform")!!.child("rotation")!!["value"])
        assertEquals("8",photo.node["parent"])
        assertTrue(photo.effects.isEmpty())
        assertTrue(photo.keyframeTimes().isEmpty())
        assertNull(photo.node.child("gain"))
        assertNull(photo.node.child("audioEffects"))
        assertNull(photo.node.child("ai"))
        assertNull(photo.node["genLoop"])
        assertEquals(scene.layer(2)!!.node.child("property"),photo.node.child("property"))
    }

    @Test fun compositionCaptureUsesFullSceneAndCreatesCenteredFlatImage() {
        val result = plan(null)!!
        assertEquals(scene,result.capture)
        assertEquals(320,result.width)
        assertEquals(240,result.height)
        assertArrayEquals(floatArrayOf(160f,120f,0f),result.photo.location,0f)
        assertNull(result.photo.node["parent"])
        assertTrue(result.photo.effects.isEmpty())
        assertEquals("160.000000,120.000000", result.photo.node.child("property")!!["value"])
    }

    @Test fun rejectsMissingOrInactiveVideoWithoutChangingProject() {
        assertNull(plan(99))
        assertNull(plan(frame=0))
        assertNull(plan(document=Scene(scene.node.copy(children=scene.node.children.filterNot { it.name=="media" }))))
        assertEquals("clip",scene.layer(2)!!.mediaFill!!.uri)
    }

    @Test fun textureLimitPreservesAspectAndOriginalLayerGeometry() {
        val result=plan(limit=320)!!
        assertEquals(320,result.width)
        assertEquals(180,result.height)
        assertEquals(scene.layer(2)!!.node.child("property"),result.photo.node.child("property"))
    }

    @Test fun explicitDurationEndsOnProjectFrameBoundary() {
        val photo=plan(seconds=.05f)!!.photo
        assertEquals(scene.frameEnd(scene.frameAt(1549)),photo.endTime)
        assertTrue(scene.showsAnyFrame(photo.startTime,photo.endTime))
        assertNull(plan(seconds=Float.NaN))
        assertNull(plan(seconds=0f))
    }

    @Test fun speedKeysAndTimeQuantizationUseTheOriginalClock() {
        val changed=scene.update(2) { it.copy(node=it.node.copy(children=it.node.children+XmlNode("effect",mapOf("id" to "absent.timequant"),listOf(XmlNode("property",mapOf("name" to "fps","value" to "8"))))).withChild("speedMap",XmlNode("speedMap",children=listOf(
            XmlNode("kf",mapOf("t" to "0","v" to "2")),XmlNode("kf",mapOf("t" to "9000000000","v" to "5")),
        )))) }
        val raw=plan(document=changed)!!.capture.layers.single()
        assertEquals(8f,raw.effects.single().effectProperty("fps")!!.valueAt(0f)!!.toFloat(),0f)
        assertEquals(changed.layer(2)!!.steppedMs(1516.0),raw.steppedMs(1516.0),0.0)
    }
}

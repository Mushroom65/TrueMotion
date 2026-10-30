package app.absentmotion.editor

import app.absentmotion.model.*
import app.absentmotion.truemotion.Studio
import org.junit.Assert.*
import org.junit.Test

class MotionPathsTest {
    private val source = Scene.parse("""<scene width="400" height="400" fps="30"><shape id="1" startTime="0" endTime="2000"><transform><location><kf t="0" v="50,200,5"/><kf t="1" v="350,200,9"/></location><rotation value="20"/></transform></shape></scene>""").layer(1)!!
    private fun curved() = source.withPathHandle(0,false,PathVector(0f,-160f),false).withPathHandle(1,true,PathVector(0f,-160f),false)

    @Test fun noControlsKeepsTheOriginalInterpolation() {
        for(i in 0..10) {
            val a=source.copy(t=i/10f).location; val b=source.withMotionPath().copy(t=i/10f).location
            assertArrayEquals(a,b,.00001f)
        }
    }
    @Test fun cubicChangesXYAndKeepsLinearDepth() {
        val l=curved().copy(t=.5f)
        assertArrayEquals(floatArrayOf(200f,80f,7f),l.location,.0001f)
    }
    @Test fun disablingKeepsHandlesButRestoresLinearMotion() {
        val l=curved().withMotionPath(false).copy(t=.5f)
        assertEquals(200f,l.location[1],.001f)
        assertEquals(curved().keyframes(PropRef.Location),l.keyframes(PropRef.Location))
    }
    @Test fun linkedHandlesKeepTheOppositeLength() {
        val l=curved().withPathHandle(0,true,PathVector(20f,0f),true)
        val opposite=l.keyframes(PropRef.Location)[0].pathHandle(false)
        assertEquals(-160f,opposite.x,.001f); assertEquals(0f,opposite.y,.001f)
        assertEquals(source.keyframes(PropRef.Location)[1].t,l.keyframes(PropRef.Location)[1].t)
    }
    @Test fun smoothUsesNonuniformKeyTimingAndRetainsKeyMetadata() {
        val l=source.withElement(PropRef.Location,source.element(PropRef.Location)!!.withKeyframes(listOf(
            Keyframe(0f,"0,0,0"),Keyframe(.25f,"100,100,0",extra=mapOf("tag" to "keep")),Keyframe(1f,"200,0,0")))).withSmoothedPath()
        val key=l.keyframes(PropRef.Location)[1]
        assertEquals(-200f*.25f/3,key.pathHandle(true).x,.0001f)
        assertEquals(200f*.75f/3,key.pathHandle(false).x,.0001f)
        assertEquals("keep",key.extra["tag"])
    }
    @Test fun autoOrientationIsAdditiveAndDoesNotRewriteRotationKeys() {
        val l=curved().withPathAutoOrient(true)
        assertEquals(-90f,l.pathAngle(),.001f)
        assertEquals(0f,l.copy(t=.5f).pathAngle(),.001f)
        assertEquals(90f,l.copy(t=1f).pathAngle(),.001f)
        assertEquals(source.element(PropRef.Rotation),l.element(PropRef.Rotation))
    }
    @Test fun reversedKeysSwapTheControlsAndExactlyReverseThePath() {
        val a=curved(); val b=Studio.reverseKeys(a)
        for(i in 0..20) assertArrayEquals(a.copy(t=i/20f).location,b.copy(t=1-i/20f).location,.001f)
    }
    @Test fun roundTripAndRetimingKeepThePathAttachedToItsKey() {
        val a=curved().withPathAutoOrient(true)
        val b=Layer(XmlNode.parse(a.node.toXml())).withTimes(1000,5000)
        assertTrue(b.pathAutoOrient && b.pathEnabled)
        assertArrayEquals(a.at(1000,30).location,b.at(1000,30).location,.001f)
        assertArrayEquals(a.at(2000,30).location,b.at(2000,30).location,.001f)
    }
    @Test fun pathCopiesAndRelativePasteRetainGeometryAndOrientation() {
        val a=curved().withPathAutoOrient(true)
        val b=Studio.pasteTransform(a,source.withId(5).withTransform("location",100f,100f,0f),true,false)
        assertTrue(b.pathAutoOrient && b.pathEnabled)
        assertArrayEquals(a.copy(t=.3f).location,b.copy(t=.3f).location,.001f)
        assertEquals(a.keyframes(PropRef.Location)[0].extra,copyLayers(listOf(a)).single().keyframes(PropRef.Location)[0].extra)
    }
    @Test fun aStationaryPathHasFiniteOrientation() {
        val k=Keyframe(0f,"50,50,0")
        assertEquals(0f,pathHeading(k,k.copy(t=1f),.5f),0f)
    }
    @Test fun nestedEditsPreserveTheRootAndOtherLayers() {
        val scene=Scene.create("root",400,400,30,0).add(Layer(XmlNode("embedScene",mapOf("id" to "3","startTime" to "0","endTime" to "2000"),listOf(Scene.create("child",400,400,30,0).add(source).node))))
        val changed=scene.withGroupScene(listOf(3),scene.groupScene(listOf(3))!!.update(1) { curved() })
        assertEquals(scene.title,changed.title)
        assertEquals(80f,changed.groupScene(listOf(3))!!.layer(1)!!.copy(t=.5f).location[1],.001f)
    }
    @Test fun invalidControlValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { source.withPathHandle(0,true,PathVector(Float.NaN,0f),false) }
    }
    @Test fun reparentingKeepsRenderedFramePositionsAndDoesNotDoubleAutoOrient() {
        val a=curved().withPathAutoOrient(true)
        val parent=Layer(XmlNode("nullobj",mapOf("id" to "8","startTime" to "0","endTime" to "2000")))
            .withTransform("location",120f,50f,0f).withTransform("scale",2f,2f).withTransform("rotation",30f)
        val scene=Scene.create("test",400,400,30,0).add(a).add(parent)
        val b=scene.rebased(a,8)!!
        val after=scene.update(1) { b }
        assertFalse(b.pathAutoOrient)
        for(frame in 0..59) {
            val ms=scene.frameTime(frame)
            val expected=scene.place(a,ms); val actual=after.place(b,ms)
            assertEquals(expected.x,actual.x,.002f); assertEquals(expected.y,actual.y,.002f)
            assertEquals(expected.rot,actual.rot,.002f)
        }
    }
    @Test fun sourcePhotoKeepsTheCurrentPathHeading() {
        val video=curved().withPathAutoOrient(true).copy(node=curved().withPathAutoOrient(true).node
            .with("fillType","media").with("fillVideo","clip"))
        val media=MediaDeclaration(XmlNode("media",mapOf("uri" to "clip","width" to "400","height" to "400")))
        val scene=Scene.create("test",400,400,30,0).add(video,listOf(media))
        val plan=app.absentmotion.truemotion.FramePhotos.plan(scene,1,15,2f,"photo.png","Photo",9)!!
        val pose=video.at(scene.frameStart(15),scene.fps)
        assertFalse(plan.photo.pathAutoOrient)
        assertEquals(pose.transform("rotation",0f)[0]+pose.pathAngle(),plan.photo.transform("rotation",0f)[0],.001f)
    }
    @Test fun legacyEffectControlsMigrateAtTheExistingPositionKeyTimes() {
        val effect=XmlNode.parse("""<effect id="lab.toji.motionPath"><property name="inX" type="float" value="0"/><property name="inY" type="float" value="-160"/><property name="outX" type="float" value="0"/><property name="outY" type="float"><kf t="0" v="-160"/><kf t="1" v="-80"/></property></effect>""")
        val old=Scene.create("old",400,400,30,0).add(source.copy(node=source.node.copy(children=source.node.children+effect)))
        val restored=Scene.parse(old.toXml())
        val l=restored.layer(1)!!
        assertTrue(l.pathEnabled)
        assertEquals(80f,l.copy(t=.5f).location[1],.001f)
        assertEquals("true",l.effects.single()["truePathConverted"])
        assertEquals(effect.children,l.effects.single().children)
        assertEquals(restored.node.with("modifiedTime",null),Scene.parse(restored.toXml()).node.with("modifiedTime",null))
    }
    @Test fun hiddenLegacyEffectsDoNotEnableCurves() {
        val effect=XmlNode("effect",mapOf("id" to "lab.toji.motionPath","hidden" to "true"))
        val old=Scene.create("old",400,400,30,0).add(source.copy(node=source.node.copy(children=source.node.children+effect)))
        assertFalse(Scene.parse(old.toXml()).layer(1)!!.pathEnabled)
    }
}

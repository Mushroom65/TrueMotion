package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RigModelTest {
    @Test fun ikReachesTargetWithEitherElbowAndPreservesBoneLengths() {
        for (flip in listOf(false, true)) {
            val angles = solveTwoBoneIK(100f, 80f, 90f, 60f, flip)
            val a = Math.toRadians(angles.root.toDouble())
            val b = Math.toRadians((angles.root + angles.child).toDouble())
            assertEquals(90.0, 100 * cos(a) + 80 * cos(b), 0.001)
            assertEquals(60.0, 100 * sin(a) + 80 * sin(b), 0.001)
            assertEquals(flip, angles.child < 0)
        }
    }

    @Test fun unreachableAndFoldedIkRemainFiniteAndClamped() {
        for ((x, y) in listOf(1000f to 1000f, 0f to 0f, -150f to 10f)) {
            val angles = solveTwoBoneIK(100f, 80f, x, y, false)
            assertTrue(angles.root.isFinite() && angles.child.isFinite())
            val a = Math.toRadians(angles.root.toDouble())
            val b = Math.toRadians((angles.root + angles.child).toDouble())
            val reach = hypot(100 * cos(a) + 80 * cos(b), 100 * sin(a) + 80 * sin(b))
            assertTrue(reach in 19.999..180.001)
        }
    }

    @Test fun invalidIkInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { solveTwoBoneIK(0f, 10f, 0f, 0f, false) }
        assertThrows(IllegalArgumentException::class.java) { solveTwoBoneIK(10f, 10f, Float.NaN, 0f, false) }
    }

    @Test fun keyedFkKeepsRestAtZeroAndWritesPoseAtPlayhead() {
        val bone = Layer(XmlNode("nullobj", mapOf("startTime" to "0", "endTime" to "1000")))
            .withTransform("rotation", 10f).at(500, 30).withRigRotation(60f, true)
        val keys = bone.keyframes(PropRef.Rotation)
        assertEquals(listOf(0f, 0.5f), keys.map { it.t })
        assertEquals(10f, keys.first().v.toFloat(), 0.001f)
        assertEquals(60f, keys.last().v.toFloat(), 0.001f)
    }

    private fun artwork(): Scene = Scene.parse("""<scene width="400" height="300" fps="30">
        <shape id="3" label="Arm" startTime="0" endTime="2999"><transform><location value="200,150,0"/></transform></shape>
        <shape id="4" label="Hand" startTime="0" endTime="2999"><transform><location value="300,150,0"/></transform></shape>
        </scene>""")

    @Test fun torsoCanCarryHeadBothArmsAndBothLegsAtDifferentJoints() {
        var scene=artwork()
        for (id in 5L..8L) scene=scene.add(Layer(XmlNode("shape",mapOf("id" to "$id","startTime" to "0","endTime" to "2999"))).withTransform("location",200f,200f,0f))
        scene=scene.withDrawnBone(3,null,Pt(200f,200f),Pt(200f,100f),20,0)
            .withDrawnBone(4,20,Pt(200f,100f),Pt(200f,60f),21,0)
            .withDrawnBone(5,20,Pt(200f,120f),Pt(130f,160f),22,0)
            .withDrawnBone(6,20,Pt(200f,120f),Pt(270f,160f),23,0)
            .withDrawnBone(7,20,Pt(200f,200f),Pt(170f,280f),24,0)
            .withDrawnBone(8,20,Pt(200f,200f),Pt(230f,280f),25,0)
        assertEquals(5,scene.bones.count { it.node["parent"]=="20" })
        assertEquals("20",scene.layer(3)!!.node["parent"])
        assertEquals(20L,scene.rigAttachment(Pt(200f,200f),10f,0,20)!!.parent)
        assertEquals(20L,scene.rigAttachment(Pt(200f,200f),10f,0,24)!!.parent)
        assertEquals(20L,scene.rigAttachment(Pt(200f,150f),10f,0,20)!!.parent)
        val extended=scene.withDrawnBone(3,20,Pt(200f,200f),Pt(150f,280f),26,0,bindArtwork=false)
        assertEquals("20",extended.layer(3)!!.node["parent"])
        val turned=scene.update(20) { it.withTransform("rotation",0f) }
        for(id in 3L..8L) { assertTrue(turned.place(turned.layer(id)!!,0).x.isFinite()); assertEquals(scene.layer(id)!!.node["parent"],turned.layer(id)!!.node["parent"]) }
    }

    @Test fun drawnBoneBindsArtworkWithoutAddingATimelineLayerOrMovingArtwork() {
        val original = artwork()
        val scene = original.withDrawnBone(3, null, Pt(100f,150f), Pt(200f,150f), 1, 0)
        assertEquals(original.layers.map { it.id }, scene.layers.map { it.id })
        assertEquals(1, scene.bones.size)
        assertEquals("bone", scene.layer(1)!!.kind)
        assertEquals("1", scene.layer(3)!!.node["parent"])
        assertEquals(200f, scene.place(scene.layer(3)!!, 0).x, .001f)
        assertEquals(150f, scene.place(scene.layer(3)!!, 0).y, .001f)
        val rotated = scene.update(1) { it.withTransform("rotation",90f) }
        assertEquals(100f, rotated.place(rotated.layer(3)!!, 0).x, .001f)
        assertEquals(250f, rotated.place(rotated.layer(3)!!, 0).y, .001f)
    }

    @Test fun childBoneIsDrawnInParentSpaceAndBindsTheNextPartImmediately() {
        val scene = artwork().withDrawnBone(3,null,Pt(100f,150f),Pt(200f,150f),1,0)
            .withDrawnBone(4,1,Pt(200f,150f),Pt(300f,150f),2,0)
        assertEquals(2, scene.bones.size)
        assertEquals("1", scene.layer(2)!!.node["parent"])
        assertEquals("2", scene.layer(4)!!.node["parent"])
        assertEquals(100f, scene.layer(2)!!.location[0], .001f)
        assertEquals(0f, scene.layer(2)!!.location[1], .001f)
        assertEquals(300f, scene.place(scene.layer(4)!!,0).x,.001f)
    }

    @Test fun groupsAndMyResourcesRetainEditableBonesAndIndependentIds() {
        val scene = artwork().withDrawnBone(3,null,Pt(100f,150f),Pt(200f,150f),1,0)
            .withDrawnBone(4,1,Pt(200f,150f),Pt(300f,150f),2,0)
        assertEquals(setOf(3L,4L),scene.riggedParts(3))
        val grouped = scene.grouped(scene.riggedParts(3),null,9,"Actor")
        assertTrue(grouped.bones.isEmpty())
        val group = grouped.layer(9)!!
        assertEquals(2,group.nested!!.bones.size)
        assertEquals(2,group.nested!!.layers.size)
        val resources = grouped.elementNodes(group,null)
        assertEquals(1,resources.size)
        assertEquals(2,Layer(resources.single()).nested!!.bones.size)
        val copied = group.nested!!.withCopiedLayerIds()
        val oldIds=(group.nested!!.layers+group.nested!!.bones).map { it.id }.toSet()
        val newIds=(copied.layers+copied.bones).map { it.id }.toSet()
        assertTrue(oldIds.intersect(newIds).isEmpty())
        assertTrue((copied.layers+copied.bones).mapNotNull { it.node["parent"]?.toLongOrNull() }.all { it in newIds })
    }

    @Test fun oldNullBoneProjectsMigrateAndRendererOnlyReceivesCompiledTransforms() {
        val scene=Scene.parse("""<scene><nullobj id="1" trueBone="true" trueBoneLength="90"/><shape id="3" parent="1"/></scene>""")
        assertEquals(listOf(3L),scene.layers.map { it.id })
        assertEquals(90f,scene.bones.single().rigLength,.001f)
        assertFalse(scene.toXml().contains("nullobj"))
        assertTrue(scene.node.withNativeBones().toXml().contains("nullobj"))
        assertEquals(scene.bones,Scene.parse(scene.toXml()).bones)
    }

    @Test fun bindingToAnAnimatedBoneKeepsOriginalKeysAndLetsTheBoneDriveArtwork() {
        val before=artwork().add(Layer(XmlNode.parse("""<bone id="1" length="100" startTime="0" endTime="2999"><transform><location value="100,150,0"/><rotation><kf t="0" v="0"/><kf t="1" v="90"/></rotation></transform></bone>""")))
        val transform=before.layer(3)!!.node.child("transform")
        val at=before.place(before.layer(3)!!,1500)
        val bound=before.withRigParent(3,1,1500)
        assertEquals(transform,bound.layer(3)!!.node.child("transform"))
        assertTrue(bound.layer(3)!!.keyframeTimes().isEmpty())
        assertEquals(at.x,bound.place(bound.layer(3)!!,1500).x,.001f)
        assertEquals(at.y,bound.place(bound.layer(3)!!,1500).y,.001f)
        assertTrue(abs(bound.place(bound.layer(3)!!,2500).y-at.y)>10f)
        assertEquals(bound,bound.withRigParent(3,1,2500))
    }

    @Test fun rebindingAndDeletingAnimatedBonesNeverBakeTheArtworkPerFrame() {
        val animation=XmlNode("location").withKeyframes(listOf(Keyframe(0f,"200,150,0"),Keyframe(1f,"250,170,0")))
        val source=artwork().update(3) { it.withElement(PropRef.Location,animation) }
            .add(Layer(XmlNode.parse("""<bone id="1" length="100" startTime="0" endTime="2999"><transform><location value="100,150,0"/><rotation><kf t="0" v="0"/><kf t="1" v="90"/></rotation></transform></bone>""")))
            .add(Layer(XmlNode.parse("""<bone id="2" length="100" startTime="0" endTime="2999"><transform><location value="300,200,0"/><rotation><kf t="0" v="20"/><kf t="1" v="-40"/></rotation></transform></bone>""")))
        val original=source.layer(3)!!.node.child("transform")
        val first=source.withRigParent(3,1,1000)
        val before=first.place(first.layer(3)!!,2000)
        val second=first.withRigParent(3,2,2000)
        assertEquals(original,second.layer(3)!!.node.child("transform"))
        assertEquals(before.x,second.place(second.layer(3)!!,2000).x,.001f)
        val detached=second.withRigChildrenDetached(2,2000).remove(2)
        assertEquals(original,detached.layer(3)!!.node.child("transform"))
        assertEquals(2,detached.layer(3)!!.keyframes(PropRef.Location).size)
        assertEquals(before.y,detached.place(detached.layer(3)!!,2000).y,.001f)
        val compiled=Scene(detached.node.withNativeBones())
        val bridge=compiled.layer(compiled.layer(3)!!.node["parent"]!!.toLong())!!
        assertEquals("nullobj",bridge.kind)
        assertTrue(bridge.keyframeTimes().isEmpty())
    }

    @Test fun staticBindingPreservesCurvesMirrorsAndAnchorsWithoutNewAnimation() {
        val source=artwork().add(Layer(XmlNode.parse("""<nullobj id="10" startTime="0" endTime="2999"><transform><location value="50,70,0"/><rotation value="30"/><scale value="-2,0.8"/><pivot value="4,7,0"/></transform></nullobj>""")))
            .update(3) {it.copy(node=it.node.with("parent","10"))}
            .add(Layer(XmlNode.parse("""<bone id="1" length="100" startTime="0" endTime="2999"><transform><location value="100,150,0"/><rotation value="-40"/><scale value="1.2,0.7"/></transform></bone>""")))
        val world=source.rigAffine(source.layer(3)!!,1000)
        val bound=source.withRigParent(3,1,1000)
        val next=bound.rigAffine(bound.layer(3)!!,1000)
        for(p in listOf(Pt(0f,0f),Pt(50f,30f),Pt(-20f,70f))) {
            assertEquals(world.map(p).x,next.map(p).x,.001f)
            assertEquals(world.map(p).y,next.map(p).y,.001f)
        }
        assertEquals(source.layer(3)!!.node.child("transform"),bound.layer(3)!!.node.child("transform"))
        val restored=Scene.parse(bound.toXml())
        assertEquals(bound.layers,restored.layers)
        assertEquals(bound.bones,restored.bones)
        val compiled=Scene(bound.node.withNativeBones())
        val native=compiled.rigAffine(compiled.layer(3)!!,1000)
        for(p in listOf(Pt(0f,0f),Pt(50f,30f))) {
            assertEquals(world.map(p).x,native.map(p).x,.003f)
            assertEquals(world.map(p).y,native.map(p).y,.003f)
        }
    }

    @Test fun libraryReplacementKeepsTheBindingOnlyOnItsOuterLayer() {
        val image=MediaDeclaration(XmlNode("media",mapOf("uri" to "file:///rig.png","type" to "image/png")))
        val source=artwork().withMedia(image).update(3) {it.copy(node=it.node.with("fillType","media").with("fillImage",image.uri))}
            .withDrawnBone(3,null,Pt(100f,150f),Pt(200f,150f),1,0)
        val binding=source.layer(3)!!.rigBinding
        val animated=source.withMediaSwitch(3,1000,image)
        assertEquals(binding,animated.layer(3)!!.rigBinding)
        assertTrue(animated.layer(3)!!.nested!!.layers.all {it.rigBinding==null})
        val restored=animated.withStaticLayerAsset(3,image)
        assertEquals(binding,restored.layer(3)!!.rigBinding)
        assertEquals(200f,restored.place(restored.layer(3)!!,1000).x,.001f)
    }

    @Test fun renderBindingsDoNotSplitClippingStacksOrLeakIntoTheLayerList() {
        val source=artwork().update(4) {it.copy(node=it.node.with("clippingMask","true"))}
            .withDrawnBone(4,null,Pt(200f,150f),Pt(300f,150f),1,0)
        assertEquals(listOf(3L,4L),source.layers.map {it.id})
        val compiled=Scene(source.node.withNativeBones())
        assertEquals(listOf(3L,4L),compiled.layers.take(2).map {it.id})
        assertEquals("true",compiled.layer(4)!!.node["clippingMask"])
        assertTrue(compiled.layers.last().id<0)
    }
    @Test fun boneHandlesAndSnappingRespectMirroredNonUniformParentScaleAndPivot() {
        val source=artwork()
            .add(Layer(XmlNode.parse("""<bone id="10" length="100" startTime="0" endTime="2999"><transform><location value="200,150,0"/><scale value="-2,0.5"/><pivot value="5,7,0"/></transform></bone>""")))
            .add(Layer(XmlNode.parse("""<bone id="11" parent="10" length="80" startTime="0" endTime="2999"><transform><location value="10,20,0"/><rotation value="45"/></transform></bone>""")))
        val (root,tip)=source.rigSegment(source.layer(11)!!,0)
        assertEquals(190f,root.x,.001f);assertEquals(156.5f,root.y,.001f)
        assertEquals(190f-160f/sqrt(2f),tip.x,.001f)
        assertEquals(156.5f+40f/sqrt(2f),tip.y,.001f)
        val snap=source.rigAttachment(tip,1f,0,11)!!
        assertEquals(11L,snap.parent);assertEquals(tip.x,snap.point.x,.001f);assertEquals(tip.y,snap.point.y,.001f)
    }

    @Test fun reparentedBonesKeepTheirDisplayedEndpointsAtTheBindingFrame() {
        val source=artwork().withDrawnBone(3,null,Pt(50f,60f),Pt(120f,100f),10,0)
            .withDrawnBone(4,null,Pt(200f,100f),Pt(230f,170f),11,0)
        val before=source.rigSegment(source.layer(11)!!,0)
        val bound=source.withRigParent(11,10,0)
        val after=bound.rigSegment(bound.layer(11)!!,0)
        assertEquals(before.first.x,after.first.x,.001f);assertEquals(before.first.y,after.first.y,.001f)
        assertEquals(before.second.x,after.second.x,.001f);assertEquals(before.second.y,after.second.y,.001f)
        assertTrue(bound.layer(11)!!.keyframeTimes().isEmpty())
    }

    @Test fun drawingARootBoneUsesTheArtworkDepthSoItsProjectedPositionStaysOnTheArtwork() {
        val source=artwork().update(3) {it.withTransform("location",200f,150f,240f)}
        val bound=source.withDrawnBone(3,null,Pt(100f,150f),Pt(200f,150f),10,0)
        assertEquals(240f,bound.layer(10)!!.location[2],.001f)
        assertEquals(240f,bound.place(bound.layer(3)!!,0).z,.001f)
        assertEquals(-240f,bound.layer(3)!!.rigBinding!!.z,.001f)
    }

}

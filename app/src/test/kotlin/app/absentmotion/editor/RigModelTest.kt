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
}

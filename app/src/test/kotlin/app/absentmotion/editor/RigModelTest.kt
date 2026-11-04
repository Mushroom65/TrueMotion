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

    @Test fun savedRigIncludesAncestorsAndArtworkAndCopiesHaveIndependentParentIds() {
        val scene = Scene.parse("""<scene width="400" height="300" fps="30">
            <media uri="arm" type="image/png"/>
            <nullobj id="1" label="Root" trueBone="true" startTime="1000" endTime="3999"/>
            <nullobj id="2" parent="1" label="Child" trueBone="true" startTime="1000" endTime="3999"/>
            <shape id="3" parent="2" fillImage="arm" startTime="1000" endTime="3999"/>
            <shape id="4" label="Unrelated" startTime="0" endTime="999"/>
            </scene>""")
        val model = scene.rigModel(setOf(2), "Actor")
        assertEquals("Actor", model.title)
        assertEquals(3, model.layers.size)
        assertEquals(2, model.layers.count { it.isRigBone })
        assertEquals(2999, model.totalTime)
        assertTrue(model.layers.all { it.startTime == 0 })
        assertTrue(model.layers.none { it.id in setOf(1L, 2L, 3L, 4L) })
        val ids = model.layers.map { it.id }.toSet()
        assertTrue(model.layers.mapNotNull { it.node["parent"]?.toLongOrNull() }.all { it in ids })
        assertTrue(model.node.storedMediaUris().contains("arm"))
    }
}

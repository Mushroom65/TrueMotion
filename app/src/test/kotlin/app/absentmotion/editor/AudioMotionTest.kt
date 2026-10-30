package app.absentmotion.editor

import app.absentmotion.audio.AudioEnvelope
import app.absentmotion.model.*
import app.absentmotion.truemotion.AudioMotion
import org.junit.Assert.*
import org.junit.Test

class AudioMotionTest {
    private fun scene() = Scene.parse("""<scene width="400" height="400" fps="30" totalTime="1999"><media uri="file:///song.wav" duration="2000000"/><bookmark t="0"/><bookmark t="1000"/><shape id="1" startTime="0" endTime="1999"><transform><location value="100,200,0"/><scale><kf t="0" v="1,2"/><kf t="1" v="2,4"/></scale><rotation value="17"/></transform><effect id="unchanged"><property name="x" value="42"/></effect></shape><audio id="2" src="file:///song.wav" startTime="500" endTime="1499" inTime="1000" outTime="3000" speed="2"><gain value="0.4"/></audio></scene>""")
    private val response = AudioEnvelope.Response(0, 1000.0, floatArrayOf(0f, 1f))

    @Test fun reactionPreservesBaseAnimationAndAdjacentPropertiesAndReapplyingDoesNotStack() {
        val scene = scene(); val original = scene.layer(1)!!
        val settings = AudioMotion.Settings(amount = .5f)
        val first = AudioMotion.bake(scene, original, settings, response)
        val again = AudioMotion.bake(scene, first, settings, response)
        assertEquals(first, again)
        assertEquals(original.element(PropRef.Rotation), first.element(PropRef.Rotation))
        assertEquals(original.effects, first.effects)
        val midpoint = floats(first.keyframes(PropRef.Scale).first { it.t > .49f }.v, floatArrayOf())
        assertTrue(midpoint[0] > 1.8f)
        assertEquals(midpoint[0] * 2, midpoint[1], 1e-5f)
        assertEquals(original, AudioMotion.restore(first, AudioMotion.Property.Scale))
    }

    @Test fun savingAndReopeningRetainsTheOriginalKeysForLaterEdits() {
        val scene = scene(); val original = scene.layer(1)!!
        val baked = AudioMotion.bake(scene, original, AudioMotion.Settings(source = 2), response)
        val saved = scene.update(1) { baked }.toXml()
        val reopened = Scene.parse(saved).layer(1)!!
        assertEquals(original.element(PropRef.Scale), AudioMotion.original(reopened, AudioMotion.Property.Scale))
        assertEquals(2L, AudioMotion.Settings.read(AudioMotion.binding(reopened, AudioMotion.Property.Scale)!!.attrs).source)
        assertEquals(original, AudioMotion.restore(reopened, AudioMotion.Property.Scale))
    }

    @Test fun restoresAbsentDefaultPropertiesWithoutLeavingSyntheticTransforms() {
        val layer = Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "1999")))
        val baked = AudioMotion.bake(scene(), layer, AudioMotion.Settings(), response)
        assertEquals(layer, AudioMotion.restore(baked, AudioMotion.Property.Scale))
    }

    @Test fun detachingKeepsEditedKeysButRemovesTheRebuildMetadata() {
        val baked = AudioMotion.bake(scene(), scene().layer(1)!!, AudioMotion.Settings(), response)
        val detached = AudioMotion.detach(baked, AudioMotion.Property.Scale)
        assertEquals(baked.element(PropRef.Scale), detached.element(PropRef.Scale))
        assertNull(AudioMotion.binding(detached, AudioMotion.Property.Scale))
    }

    @Test fun opacityYAndRotationHaveDifferentCorrectUnits() {
        val scene = scene(); val layer = scene.layer(1)!!
        val always = AudioEnvelope.Response(0, 3000.0, floatArrayOf(1f))
        val y = AudioMotion.bake(scene, layer, AudioMotion.Settings(property = AudioMotion.Property.Y, amount = -100f), always)
        assertEquals("100.000000,100.000000,0.000000", y.keyframes(PropRef.Location).first().v)
        val rotation = AudioMotion.bake(scene, layer, AudioMotion.Settings(property = AudioMotion.Property.Rotation, amount = 20f), always)
        assertEquals(37f, rotation.keyframes(PropRef.Rotation).first().v.toFloat(), 0f)
        val opacity = AudioMotion.bake(scene, layer, AudioMotion.Settings(property = AudioMotion.Property.Opacity, amount = 1f), response)
        assertEquals(0f, opacity.keyframes(PropRef.Opacity).first().v.toFloat(), 0f)
        assertTrue(opacity.keyframes(PropRef.Opacity).any { it.v.toFloat() > .99f })
    }

    @Test fun bopIsConfigurableHasAnOffsetAndIsIndependentOfAudioAnalysis() {
        val scene = scene(); val base = scene.layer(1)!!.withElement(PropRef.Scale, XmlNode("scale", mapOf("value" to "1,1")))
        val bop = AudioMotion.bake(scene, base, AudioMotion.Settings(mode = AudioMotion.Mode.Bop, amount = .2f, decayMs = 200f, offsetMs = 100f))
        assertEquals("1.000000,1.000000", bop.keyframes(PropRef.Scale).first().v)
        assertTrue(bop.keyframes(PropRef.Scale).any { it.t < .1f && floats(it.v, floatArrayOf())[0] > 1.19f })
        assertEquals(0f, AudioMotion.pulse(listOf(0), -.1, 100f), 0f)
    }

    @Test fun sourceSignatureIgnoresGeneratedVisualAnimationButTracksAudioTimingAndVolume() {
        val scene = scene(); val initial = AudioMotion.signature(scene, 2, true)
        val baked = scene.update(1) { AudioMotion.bake(scene, it, AudioMotion.Settings(), response) }
        assertEquals(AudioMotion.sourceScene(scene, 2, true).node, AudioMotion.sourceScene(baked, 2, true).node)
        assertEquals(initial, AudioMotion.signature(Scene(scene.node.with("modifiedTime", "123456789")), 2, true))
        assertEquals(initial, AudioMotion.signature(baked, 2, true))
        assertNotEquals(initial, AudioMotion.signature(scene.update(2) { it.copy(node = it.node.with("inTime", "0")) }, 2, true))
        assertNotEquals(initial, AudioMotion.signature(scene, 2, false))
        val audio = AudioMotion.sourceScene(scene, 2, false).layer(2)!!
        assertEquals("2", audio.node["speed"])
        assertEquals("1000", audio.node["inTime"])
        assertNull(audio.node.child("gain"))
        assertNotNull(AudioMotion.sourceScene(scene, 2, true).layer(2)!!.node.child("gain"))
    }

    @Test fun beatGridIsPhaseAccurateAndMergesMarkersWithoutDeletingOrDuplicatingThem() {
        val grid = AudioMotion.grid(500, 2500, 120f, 100f)
        assertEquals(listOf(600, 1100, 1600, 2100), grid)
        val original = scene()
        val changed = AudioMotion.addMarkers(original, grid + listOf(-10, 100_000))
        assertTrue(changed.bookmarks.containsAll(original.bookmarks))
        assertEquals(changed, AudioMotion.addMarkers(changed, grid))
        assertFalse(changed.bookmarks.any { it > original.totalTime })
    }

    @Test fun applyingTheGeneratedSceneIsOneUndoStep() {
        var scene = scene(); val initial = scene
        val history = EditHistory({ scene }) { next, _ -> scene = next }
        history.edit(null, scene.update(1) { AudioMotion.bake(scene, it, AudioMotion.Settings(), response) })
        val baked = scene
        assertEquals(initial, history.undo())
        assertFalse(history.canUndo)
        assertEquals(baked, history.redo())
    }

    @Test fun malformedImportedOptionsAreSanitizedAndLongLayersHavePredictableKeyCosts() {
        val settings = AudioMotion.Settings.read(mapOf("gain" to "NaN", "attackMs" to "Infinity", "gate" to "9", "property" to "invalid", "keyRate" to "-1"))
        assertEquals(1f, settings.gain, 0f)
        assertEquals(15f, settings.attackMs, 0f)
        assertEquals(.95f, settings.gate, 0f)
        assertEquals(AudioMotion.Property.Scale, settings.property)
        assertEquals(61, AudioMotion.keyCount(scene().layer(1)!!, 60, 30))
    }
}

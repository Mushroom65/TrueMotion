package app.absentmotion.editor

import app.absentmotion.audio.AudioEnvelope
import app.absentmotion.model.*
import app.absentmotion.truemotion.AudioMotion
import app.absentmotion.truemotion.AudioReactive
import org.junit.Assert.*
import org.junit.Test

class AudioReactiveTest {
    private fun scene() = Scene.parse("""<scene width="400" height="400" fps="30" totalTime="1999"><shape id="1" startTime="0" endTime="1999"><transform><location value="100,200,0"/><scale><kf t="0" v="1,2"/><kf t="1" v="2,4"/></scale><rotation value="17"/></transform><effect id="original"/></shape><audio id="2" src="file:///music.wav" startTime="0" endTime="1999"/></scene>""")

    @Test fun nativeEffectPreservesAllOriginalAnimationAndDoesNotGenerateKeys() {
        val layer = scene().layer(1)!!
        val settings = AudioMotion.Settings(source = 2, amount = .7f)
        val changed = AudioReactive.put(layer, settings)
        assertEquals(layer.node.child("transform"), changed.node.child("transform"))
        assertEquals(layer.keyframes(PropRef.Scale), changed.keyframes(PropRef.Scale))
        assertEquals(layer.effects, changed.effects.dropLast(1))
        assertEquals(2L, AudioReactive.settings(changed.effects.last()).source)
        assertEquals(settings, AudioReactive.settings(changed.effects.last()))
        assertEquals(changed, AudioReactive.put(changed, settings))
        assertEquals(layer, AudioReactive.remove(changed, settings.property))
    }

    @Test fun conversionFromThePreviousBakedToolRestoresTheOriginalInsteadOfStackingReactions() {
        val scene = scene(); val layer = scene.layer(1)!!; val settings = AudioMotion.Settings(source = 2)
        val baked = AudioMotion.bake(scene, layer, settings, AudioEnvelope.Response(0, 1000.0, floatArrayOf(0f, 1f)))
        val live = AudioReactive.put(baked, settings)
        assertEquals(layer.node.child("transform"), live.node.child("transform"))
        assertNull(AudioMotion.binding(live, settings.property))
        assertEquals(layer, AudioReactive.remove(live, settings.property))
    }

    @Test fun independentInstancesAndHiddenStateSurviveSaveAndReopen() {
        val first = AudioReactive.put(scene().layer(1)!!, AudioMotion.Settings(source = 2))
        val second = AudioReactive.put(first, AudioMotion.Settings(property = AudioMotion.Property.Rotation, source = 2, amount = 25f))
        val hidden = second.withEffect(1) { it.with("hidden", "true") }
        val reopened = Scene.parse(scene().update(1) { hidden }.toXml()).layer(1)!!
        assertEquals(hidden, reopened)
        assertEquals(2, reopened.effects.count { it["id"] == AudioReactive.ID })
        assertEquals(1, AudioReactive.configurations(Scene(reopened.node)).size)
        assertTrue(AudioReactive.put(reopened, AudioMotion.Settings(source = 2)).effects[1]["hidden"] == "true")
    }

    @Test fun dependencyBindingsUseEachNestedCompositionClockWithoutMutatingTheInput() {
        val inner = scene().update(1) { AudioReactive.put(it, AudioMotion.Settings(source = 2)) }
        val outer = Scene.parse("""<scene width="800" height="800" fps="30" totalTime="9999"><embedScene id="20" startTime="3000" endTime="6999" speed="0.5">${inner.node.toXml().substringAfter("?>")}</embedScene></scene>""")
        val references = AudioReactive.configurations(outer)
        assertEquals(1, references.size)
        assertEquals(400, references.single().first.width)
        assertEquals(2L, references.single().second.source)
        val bound = AudioReactive.bind(outer) { composition, _ -> "local-${composition.width}" }
        val effect = bound.layers.single().node.child("scene")!!.children.first { it.name == "shape" }.children.last()
        assertEquals("local-400", effect["signalKey"])
        assertFalse(outer.node.toXml().contains("signalKey"))
        assertTrue(AudioReactive.has(outer.node))
    }

    @Test fun amountTargetAndOffsetReuseTheSameAnalysisBufferButResponseControlsDoNot() {
        val original = AudioMotion.Settings(source = 2)
        val key = AudioReactive.responseKey("source-version", original)
        assertEquals(key, AudioReactive.responseKey("source-version", original.copy(amount = .7f, property = AudioMotion.Property.Rotation, offsetMs = 500f)))
        assertNotEquals(key, AudioReactive.responseKey("new-file-version", original))
        assertNotEquals(key, AudioReactive.responseKey("source-version", original.copy(band = AudioEnvelope.Band.Bass)))
        assertNotEquals(key, AudioReactive.responseKey("source-version", original.copy(releaseMs = 400f)))
    }

    @Test fun respectingAudioVolumeAlsoRespectsMuteWhileBypassCanAnalyzeMutedSources() {
        val muted = scene().update(2) { it.copy(node = it.node.with("hidden", "true")) }
        assertTrue(AudioMotion.sourceScene(muted, 2, true).layer(2)!!.hidden)
        assertFalse(AudioMotion.sourceScene(muted, 2, false).layer(2)!!.hidden)
    }
}

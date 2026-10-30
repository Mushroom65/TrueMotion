package app.absentmotion.editor

import app.absentmotion.effects.EffectDefinition
import app.absentmotion.model.*
import app.absentmotion.truemotion.ColorGrade
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ColorGradeTest {
    private val definition = EffectDefinition.parse(JSONObject(File("src/main/assets/effects/true-grade.json").readText()))
    private fun source(): Scene = Scene.parse("""<scene width="256" height="256" totalTime="2000" fps="30"><shape id="1" startTime="0" endTime="1999"><effect id="other"><property name="amount" value="7"/></effect>${definition.instance().toXml().substringAfter("?>")}</shape></scene>""")

    @Test fun animatedCurveEditsKeepOtherKeysEasingAndAdjacentEffectsAfterReopening() {
        val source = source().update(1) { layer -> layer.withEffect(1) { effect -> effect.withEffectProperty("exposure", "float", "0", 0f, 0f..0f)
            .copy(children = effect.children.map { if (it["name"] == "curveY2") it.copy(attrs = it.attrs - "value", children = listOf(
                XmlNode("kf", mapOf("t" to "0", "v" to "0.3")),
                XmlNode("kf", mapOf("t" to "1", "v" to "0.8", "e" to "cubicBezier 0.2 0.1 0.7 0.9")))) else it }) } }
        val changed = source.update(1, 1000) { ColorGrade.change(it, 1, definition, mapOf("curveY2" to .6f)) }
        val reopened = Scene.parse(changed.toXml())
        val keys = reopened.layers.single().keyframes(PropRef.effect(1, "curveY2"))
        assertEquals(3, keys.size)
        assertEquals("0.3", keys.first().v)
        assertEquals("0.8", keys.last().v)
        assertEquals("cubicBezier 0.2 0.1 0.7 0.9", keys.last().e)
        assertEquals(.6f, keys[1].v.toFloat(), 1e-5f)
        assertEquals(source.layers.single().effects[0], reopened.layers.single().effects[0])
        assertEquals(source.layers.single().effects[1].effectProperty("gradeMix"), reopened.layers.single().effects[1].effectProperty("gradeMix"))
    }

    @Test fun wheelDragIsOneUndoAndRedoTransactionForAllThreeChannels() {
        val initial = source()
        var scene = initial
        val history = EditHistory({ scene }) { next, _ -> scene = next }
        for (n in 1..20) history.edit("grade-1", scene.update(1, 0) { ColorGrade.change(it, 1, definition,
            ColorGrade.rgb(240f, n / 20f).let { rgb -> "RGB".mapIndexed { i, c -> "gamma$c" to rgb[i] }.toMap() }) })
        val final = scene
        assertTrue(history.endGesture())
        assertNotEquals(initial, final)
        assertEquals(initial, history.undo())
        assertFalse(history.canUndo)
        assertEquals(final, history.redo())
        val reopened = Scene.parse(final.toXml()).layers.single()
        assertTrue(ColorGrade.value(reopened, 1, definition, "gammaB") > ColorGrade.value(reopened, 1, definition, "gammaR"))
    }

    @Test fun resetSectionKeepsAnimationAndUnrelatedCorrections() {
        var layer = source().layers.single().at(0, 30)
        val ref = PropRef.effect(1, "liftR")
        layer = layer.withKeyframeToggled(ref, "0")
        layer = ColorGrade.change(layer.at(1000, 30), 1, definition, mapOf("liftR" to .7f, "contrast" to 1.8f))
        val reset = ColorGrade.reset(layer, 1, definition, ColorGrade.wheelKeys("lift"))
        assertEquals(2, reset.keyframes(ref).size)
        assertEquals(0f, ColorGrade.value(reset, 1, definition, "liftR"), 1e-5f)
        assertEquals(1.8f, ColorGrade.value(reset, 1, definition, "contrast"), 1e-5f)
    }

    @Test fun numericValuesAreBoundedAndInvalidInputCannotCorruptTheScene() {
        val layer = source().layers.single()
        val changed = ColorGrade.change(layer, 1, definition, mapOf("exposure" to 100f, "curveB1" to -1f, "contrast" to Float.NaN, "unknown" to 3f))
        assertEquals(4f, ColorGrade.value(changed, 1, definition, "exposure"), 0f)
        assertEquals(0f, ColorGrade.value(changed, 1, definition, "curveB1"), 0f)
        assertEquals(1f, ColorGrade.value(changed, 1, definition, "contrast"), 0f)
        assertEquals(53, changed.effects[1].children.size)
        assertEquals(layer, ColorGrade.change(layer, 0, definition, mapOf("exposure" to 1f)))
    }

    @Test fun wheelRoundTripsHueAndStrengthWithoutNeutralBrightnessDrift() {
        for (hue in 0..359 step 7) for (amount in listOf(.01f, .3f, 1f)) {
            val rgb = ColorGrade.rgb(hue.toFloat(), amount)
            assertEquals(0f, rgb.sum(), 1e-5f)
            val back = ColorGrade.wheel(rgb)
            assertEquals(hue.toFloat(), back.hue, 1e-3f)
            assertEquals(amount, back.amount, 1e-5f)
        }
        assertEquals(0f, ColorGrade.wheel(floatArrayOf(.2f, .2f, .2f)).amount, 0f)
    }

    @Test fun curveGraphSamplesUseTheSameQuarterPointSegmentsAsTheShader() {
        for (n in 0..100) assertEquals(n / 100f, ColorGrade.curve(n / 100f, listOf(.25f, .5f, .75f)), 1e-6f)
        assertEquals(.4f, ColorGrade.curve(.125f, listOf(.8f, .2f, .9f)), 1e-6f)
        assertEquals(.55f, ColorGrade.curve(.625f, listOf(.8f, .2f, .9f)), 1e-6f)
        assertEquals(1f, ColorGrade.curve(1f, listOf(.8f, .2f, .9f)), 0f)
    }

    @Test fun everyShaderParameterIsReachableFromTheNativePanel() {
        val exposed = ColorGrade.primary + ColorGrade.tones.flatMap(ColorGrade::wheelKeys) + listOf("Y", "R", "G", "B").flatMap(ColorGrade::curveKeys) + ColorGrade.bands.flatMap(ColorGrade::hslKeys) + "gradeMix"
        assertEquals(53, exposed.size)
        assertEquals(definition.params.map { it.id }.toSet(), exposed.toSet())
    }
}

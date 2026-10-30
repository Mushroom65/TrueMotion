package app.absentmotion.model

import app.absentmotion.truemotion.Studio
import org.junit.Assert.*
import org.junit.Test

class TrueStudioTest {
    private fun scene() = Scene.parse("""<scene width="800" height="600" fps="30" totalTime="2000"><shape id="1" label="Animated" startTime="0" endTime="1999"><transform><location><kf t="0" v="10,20,0"/><kf t="1" v="110,220,0" e="cubicBezier 0.2 0.1 0.7 0.9"/></location><scale value="1,1"/></transform><path d="M0,0 L10,0 L10,10 Z"/></shape></scene>""")

    @Test fun arraysKeepAnimationAndAssignDistinctIds() {
        val result = Studio.array(scene(), 1, 6, 3, 120f, 80f, false, 200f, 2f, false)
        assertEquals(6, result.layers.size)
        assertEquals(6, result.layers.map { it.id }.toSet().size)
        val copy = result.layers[4]
        assertEquals(267, copy.startTime)
        assertEquals("130.000000,100.000000,0.000000", copy.keyframes(PropRef.Location).first().v)
        assertEquals("230.000000,300.000000,0.000000", copy.keyframes(PropRef.Location).last().v)
        assertEquals("cubicBezier 0.2 0.1 0.7 0.9", copy.keyframes(PropRef.Location).last().e)
        assertEquals(1, scene().layers.size)
    }

    @Test fun reverseKeepsEndpointsAndReversesIncomingEase() {
        val original = scene().layers.single()
        val reversed = Studio.reverseKeys(original)
        val keys = reversed.keyframes(PropRef.Location)
        assertEquals("110,220,0", keys.first().v)
        assertEquals("10,20,0", keys.last().v)
        val easing = Easing.parse(keys.last().e)
        assertEquals(0.3f, easing.p[0], 0.00001f)
        assertEquals(0.8f, easing.p[2], 0.00001f)
    }

    @Test fun expressionBecomesNormalEditableKeys() {
        val layer = scene().layers.single()
        val baked = Studio.expression(layer, PropRef.Scale, "value * (1 + sin(time * 3.141592653589793))", 30)
        assertEquals(61, baked.keyframes(PropRef.Scale).size)
        val middle = floats(baked.keyframes(PropRef.Scale)[15].v, floatArrayOf())
        assertEquals(2f, middle[0], 0.001f)
        assertEquals(2f, middle[1], 0.001f)
        assertTrue(layer.keyframes(PropRef.Scale).isEmpty())
    }

    @Test fun bopUsesMarkersAndReturnsToOriginalScale() {
        val source = scene().withBookmarks(listOf(0, 1000))
        val layer = Studio.bop(source, source.layers.single(), 0.2f, 180)
        assertEquals(1.2f, floats(layer.keyframes(PropRef.Scale).first().v, floatArrayOf())[0], 0.00001f)
        assertEquals(1f, floats(layer.keyframes(PropRef.Scale)[10].v, floatArrayOf())[0], 0.00001f)
    }
}

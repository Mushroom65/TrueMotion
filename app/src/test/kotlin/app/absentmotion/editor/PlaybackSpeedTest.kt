package app.absentmotion.editor

import app.absentmotion.audio.AudioClip
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedTest {
    private val scene=Scene.parse("""<scene width="320" height="320" fps="30" totalTime="7999"><shape id="2" startTime="0" endTime="7999" fillType="media" fillVideo="clip" inTime="0" outTime="8000"><transform><rotation><kf t="0" v="0"/><kf t="1" v="90"/></rotation></transform></shape></scene>""")
    private fun speed(layer:Layer,value:Float)=scene.withPlaybackSpeed(layer,value,0f,0)

    @Test fun logarithmicSliderCoversTheWholeRangeAndRoundTripsPresets() {
        assertEquals(0f,PlaybackSpeed.position(PlaybackSpeed.Min),.00001f)
        assertEquals(1f,PlaybackSpeed.position(100f),.00001f)
        for (v in PlaybackSpeed.presets) assertEquals(v,PlaybackSpeed.value(PlaybackSpeed.position(v)),.0001f)
        val values=(0..100).map { PlaybackSpeed.value(it/100f) }
        assertTrue(values.zipWithNext().all { (a,b)->a<b })
    }

    @Test fun hundredTimesKeepsTheClipVisibleAndAlignsExportEnd() {
        val fast=speed(scene.layer(2)!!,100f)
        assertEquals(100f,VideoTiming(fast).speed(0.0),0f)
        assertEquals(99,fast.endTime)
        assertTrue(scene.showsAnyFrame(fast.startTime,fast.endTime))
        assertEquals(8000.0,VideoTiming(fast).mediaMs(80.0),.001)
    }

    @Test fun commonRatesDoNotAddAnExtraFrameAtExactSecondBoundaries() {
        for ((rate,end) in listOf(1f to 7999,2f to 3999,4f to 1999,8f to 999,10f to 799)) {
            assertEquals("Rate $rate",end,speed(scene.layer(2)!!,rate).endTime)
        }
    }

    @Test fun subFrameClipStillHasOneVisibleFrame() {
        val short=scene.layer(2)!!.withTimes(0,999).copy(node=scene.layer(2)!!.withTimes(0,999).node.with("outTime","1000"))
        val fast=speed(short,100f)
        assertEquals(32,fast.endTime)
        assertTrue(fast.activeAt(scene.frameTime(0)))
    }

    @Test fun changingBackToOneDoesNotAccumulateRoundedTailDuration() {
        val source=scene.layer(2)!!
        var current=source
        repeat(5) { current=speed(speed(current,100f),1f) }
        assertEquals(source.endTime,current.endTime)
        assertEquals(1f,VideoTiming(current).speed(0.0),0f)
    }

    @Test fun animationKeysKeepTheirSourceTimeAcrossSpeedChanges() {
        val source=scene.layer(2)!!
        val fast=speed(source,100f)
        assertEquals(source.at(4000,30).transform("rotation",0f)[0],fast.at(40,30).transform("rotation",0f)[0],.001f)
    }

    @Test fun hundredTimesAudioHasAnActiveSpanAndBecomesSilentPastSourceEnd() {
        val fast=speed(scene.layer(2)!!,100f)
        val clip=AudioClip(fast,"clip")
        val span=clip.span(0.0,480,1000.0/48000,0)!!
        assertTrue(span.inMedia)
        assertEquals(100.0*1000/48000,span.step,.00001)
        val tail=clip.span(90.0,48,1000.0/48000,0)!!
        assertFalse(tail.inMedia)
    }

    @Test fun invalidValuesDoNotCreateBrokenTimingAndManualTrimClearsTailMarker() {
        val source=scene.layer(2)!!
        assertEquals(source,speed(source,Float.NaN))
        assertEquals(100f,VideoTiming(speed(source,500f)).speed(0.0),0f)
        assertEquals(SLOWEST_SPEED,VideoTiming(speed(source,-3f)).speed(0.0),0f)
        val fast=speed(source,100f)
        assertNotNull(fast.node["trueSpeedContentEnd"])
        assertNull(fast.trimmedTo(0,65).node["trueSpeedContentEnd"])
    }
}

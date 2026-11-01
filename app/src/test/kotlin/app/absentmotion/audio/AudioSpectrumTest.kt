package app.absentmotion.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AudioSpectrumTest {
    private fun tone(hz:Double, opposite:Boolean=false):FloatArray = FloatArray(44100*2) { i ->
        (sin(2*PI*hz*(i/2)/44100)*.6*(if(opposite && i%2==1) -1 else 1)).toFloat()
    }
    private fun analyze(pcm:FloatArray, chunk:Int=pcm.size):AudioSpectrum.Data {
        val s=AudioSpectrum.Stream(44100,120,1000)
        var at=0
        while(at<pcm.size) { val end=min(pcm.size,at+chunk);s.add(pcm.copyOfRange(at,end));at=end }
        return s.finish()
    }
    private fun peak(data:AudioSpectrum.Data)=data.bands.indices.maxBy { data.bands[it].drop(4).average() }
    @Test fun distinctFrequenciesOccupyDifferentBins() {
        val bass=analyze(tone(110.0));val high=analyze(tone(6000.0))
        assertTrue(peak(high)-peak(bass)>25)
        assertTrue(abs(ln(AudioSpectrum.frequency(peak(bass).toDouble(),22050.0)/110))<.15)
        assertTrue(abs(ln(AudioSpectrum.frequency(peak(high).toDouble(),22050.0)/6000))<.15)
        assertEquals(25,bass.count)
        assertEquals(120,bass.startMs)
    }
    @Test fun oppositePhaseStereoDoesNotCancel() {
        val same=analyze(tone(440.0));val opposite=analyze(tone(440.0,true))
        same.bands.zip(opposite.bands).forEach { (a,b)->assertArrayEquals(a,b,1e-7f) }
    }
    @Test fun chunkBoundariesAndSilenceAreStable() {
        val whole=analyze(tone(900.0));val chunks=analyze(tone(900.0),1002)
        whole.bands.zip(chunks.bands).forEach { (a,b)->assertArrayEquals(a,b,0f) }
        assertTrue(analyze(FloatArray(44100*2)).bands.all { b->b.all { it==0f } })
    }
    @Test fun longProjectsHaveABoundedEnvelopeBuffer() {
        val s=AudioSpectrum.Stream(44100,0,12*60*60*1000)
        s.add(tone(440.0))
        val d=s.finish()
        assertTrue(d.stepMs>=12*60*60*1000.0/AudioSpectrum.MAX_FRAMES)
        assertTrue(d.count<=AudioSpectrum.MAX_FRAMES)
        assertTrue(d.bytes<=AudioSpectrum.MAX_FRAMES*AudioSpectrum.BINS*4L)
    }
}

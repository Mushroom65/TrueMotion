package app.absentmotion.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioEnvelopeTest {
    private fun tone(hz: Float, amplitude: Float = 1f): FloatArray = FloatArray(32_000) { i -> amplitude * sin(2 * PI * hz * (i / 2) / 16_000).toFloat() }
    private fun analyze(pcm: FloatArray) = AudioEnvelope.Stream(16_000, 1000).also { it.add(pcm) }.finish()

    @Test fun oppositePhaseStereoDoesNotDisappear() {
        val pcm = tone(220f)
        for (i in 1 until pcm.size step 2) pcm[i] *= -1
        val data = analyze(pcm)
        assertEquals(100, data.count)
        assertTrue(data.peak(AudioEnvelope.Band.Full) > .65f)
    }

    @Test fun arbitraryDecoderChunkSizesGiveExactlyTheSameEnvelope() {
        val pcm = tone(731f)
        val whole = analyze(pcm)
        val stream = AudioEnvelope.Stream(16_000, 1000)
        for (start in pcm.indices step 238) stream.add(pcm.copyOfRange(start, minOf(start + 238, pcm.size)))
        val chunked = stream.finish()
        for (band in 0..3) assertArrayEquals(whole.bands[band], chunked.bands[band], 0f)
    }

    @Test fun frequencyControlsRespondToTheirActualBands() {
        val bass = analyze(tone(40f)); val mids = analyze(tone(1000f)); val highs = analyze(tone(5000f))
        fun level(d: AudioEnvelope.Data, b: AudioEnvelope.Band) = d.bands[b.ordinal].drop(10).average()
        assertTrue(level(bass, AudioEnvelope.Band.Bass) > 5 * level(highs, AudioEnvelope.Band.Bass))
        assertTrue(level(highs, AudioEnvelope.Band.High) > 8 * level(bass, AudioEnvelope.Band.High))
        assertTrue(level(mids, AudioEnvelope.Band.Mid) > 3 * level(bass, AudioEnvelope.Band.Mid))
    }

    @Test fun attackAndReleaseFollowTimeAndContinueAfterTheAudioEnds() {
        val samples = floatArrayOf(0f, 1f, 1f, 0f, 0f)
        val data = AudioEnvelope.Data(0, 10.0, List(4) { samples })
        val response = data.response(AudioEnvelope.Band.Full, 1f, 0f, 50f, 100f, false, false)
        assertTrue(response.levels[1] in .17f.. .19f)
        assertTrue(response.levels[2] > response.levels[1])
        assertTrue(response.levels[3] < response.levels[2])
        assertTrue(response.at(60.0) > 0f)
        assertTrue(response.at(2000.0) < .0001f)
        assertEquals(0f, response.at(-1.0), 0f)
    }

    @Test fun normalizationGateAndInverseAreFiniteAndHaveUsefulSilenceBehavior() {
        val data = AudioEnvelope.Data(1000, 10.0, List(4) { floatArrayOf(.01f, .1f, .5f) })
        val normal = data.response(AudioEnvelope.Band.Full, 1f, .1f, 0f, 0f, true, false)
        val inverse = data.response(AudioEnvelope.Band.Full, 1f, .1f, 0f, 0f, true, true)
        assertEquals(0f, normal.levels[0], 0f)
        assertEquals(1f, normal.levels[2], 0f)
        assertEquals(1f, inverse.at(0.0), 0f)
        for (i in normal.levels.indices) assertEquals(1f, normal.levels[i] + inverse.levels[i], 1e-5f)
        val silence = analyze(FloatArray(3200)).response(AudioEnvelope.Band.Full, 8f, .95f, 0f, 0f, true, false)
        assertTrue(silence.levels.all { it == 0f })
    }
}

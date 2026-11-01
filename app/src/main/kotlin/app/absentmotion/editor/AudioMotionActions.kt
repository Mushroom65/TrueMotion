package app.absentmotion.editor

import android.content.Context
import android.net.Uri
import app.absentmotion.R
import app.absentmotion.audio.*
import app.absentmotion.export.FloatMix
import app.absentmotion.i18n.AppText
import app.absentmotion.media.fileVersion
import app.absentmotion.model.*
import app.absentmotion.truemotion.AudioMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.roundToInt

internal val EditorState.audioMotionEditing: Boolean get() = !easingOpen && (panel == EditorPanel.AudioMotion ||
    panel == EditorPanel.Effects && selected?.effects?.getOrNull(effectIndex)?.get("id") == app.absentmotion.truemotion.AudioReactive.ID)

internal fun EditorState.openAudioMotion(tab: Int = 0) {
    if (selected == null) return
    playing = false
    endGesture()
    keypad = null
    easingOpen = false
    audioMotionTab = tab
    effectIndex = selected?.effects?.indexOfFirst { it["id"] == app.absentmotion.truemotion.AudioReactive.ID } ?: -1
    panel = EditorPanel.AudioMotion
}

internal suspend fun analyzeAudioMotion(context: Context, scene: Scene, source: Long?, respectVolume: Boolean, progress: (Float) -> Unit, spectrum: Boolean = false): AudioMotion.Profile = withContext(Dispatchers.IO) {
    val audio = AudioMotion.sourceScene(scene, source, respectVolume)
    val clips = AudioClip.of(audio)
    require(clips.isNotEmpty()) { AppText.get(R.string.true_audio_silent) }
    val start = clips.minOf { it.startMs }.coerceAtLeast(0)
    val end = clips.maxOf { it.endMs }.coerceAtMost(scene.totalTime.coerceAtMost(Int.MAX_VALUE - 1) + 1)
    require(end > start) { AppText.get(R.string.true_audio_no_source) }
    val versions = clips.map { it.uri }.distinct().associateWith { fileVersion(context.contentResolver, Uri.parse(it)) }
    val rate = if(spectrum) 44_100 else 16_000
    val stream = AudioEnvelope.Stream(rate, start)
    val frequencies = if(spectrum) AudioSpectrum.Stream(rate,start,end-start) else null
    val beats = Beats.Stream(rate)
    val mix = OfflineMix.of(context, audio, Resampler.HIGH, rate) ?: error(AppText.get(R.string.true_audio_no_source))
    try {
        var at = start
        while (at < end) {
            currentCoroutineContext().ensureActive()
            val next = minOf(end, at + 250)
            val pcm = if (mix is FloatMix) mix.floats(at * 1000L, next * 1000L) else mix(at * 1000L, next * 1000L).let { shorts -> FloatArray(shorts.size) { shorts[it] / 32768f } }
            stream.add(pcm)
            frequencies?.add(pcm)
            // Avoid cancellation of opposite-phase stereo channels in beat detection.
            val mono = FloatArray(pcm.size / 2) { i -> if (abs(pcm[2 * i]) >= abs(pcm[2 * i + 1])) pcm[2 * i] else pcm[2 * i + 1] }
            beats.add(mono, mono.size)
            at = next
            progress((at - start).toFloat() / (end - start))
        }
        if (mix is OfflineMix && mix.unreadable.isNotEmpty()) error(AppText.get(R.string.true_audio_unreadable))
    } finally { (mix as? Closeable)?.close() }
    currentCoroutineContext().ensureActive()
    val found = beats.beats().map { (start + it).roundToInt() }.filter { it in start until end }.distinct().sorted()
    AudioMotion.Profile(AudioMotion.signature(scene, source, respectVolume), versions, stream.finish(), found, frequencies?.finish())
}

internal suspend fun AudioMotion.Profile.sourcesUnchanged(context: Context): Boolean = withContext(Dispatchers.IO) {
    versions.all { (uri, version) -> fileVersion(context.contentResolver, Uri.parse(uri)) == version }
}

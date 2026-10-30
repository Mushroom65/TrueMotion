package app.absentmotion.engine

import android.content.Context
import android.net.Uri
import app.absentmotion.audio.AudioClip
import app.absentmotion.audio.AudioEnvelope
import app.absentmotion.audio.soundUri
import app.absentmotion.editor.analyzeAudioMotion
import app.absentmotion.media.fileVersion
import app.absentmotion.model.Scene
import app.absentmotion.truemotion.AudioMotion
import app.absentmotion.truemotion.AudioReactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cached audio dependencies, prepared away from the render thread. No work here runs per frame. */
internal class AudioEffectRuntime(private val context: Context) {
    private val mutex = Mutex()
    private val profiles = LinkedHashMap<String, Pair<String, AudioMotion.Profile>>(8, .75f, true)
    private var revision = 0L
    private val responses = LinkedHashMap<String, AudioReactive.Signal>(8, .75f, true)
    private val budget = 64L * 1024 * 1024

    private suspend fun profileKey(scene: Scene, source: Long?, volume: Boolean): String = withContext(Dispatchers.IO) {
        val clips = AudioClip.of(AudioMotion.sourceScene(scene, source, volume))
        val versions = clips.map { it.uri }.distinct().sorted().joinToString("|") { uri -> "$uri=${fileVersion(context.contentResolver, Uri.parse(uri))}" }
        AudioReactive.hash(AudioMotion.signature(scene, source, volume) + "|" + versions)
    }

    suspend fun profile(scene: Scene, source: Long?, volume: Boolean, force: Boolean = false, progress: (Float) -> Unit = {}): Pair<String, AudioMotion.Profile> {
        val key = profileKey(scene, source, volume)
        return mutex.withLock {
            val cached = profiles[key]
            if (cached != null && !force) cached else {
                require(source == null || scene.layer(source)?.soundUri != null) { context.getString(app.absentmotion.R.string.true_audio_no_source) }
                val decoded = if (AudioClip.of(AudioMotion.sourceScene(scene, source, volume)).isEmpty())
                    AudioMotion.Profile(AudioMotion.signature(scene, source, volume), emptyMap(), AudioEnvelope.Data(0, 10.0, List(4) { FloatArray(0) }), emptyList())
                else analyzeAudioMotion(context, scene, source, volume, progress)
                val entry = AudioReactive.hash("$key|${++revision}") to decoded
                profiles[key] = entry
                trim()
                entry
            }
        }
    }

    suspend fun prepare(scene: Scene): AudioReactive.Prepared {
        val references = AudioReactive.configurations(scene)
        if (references.isEmpty()) return AudioReactive.Prepared(scene, scene, emptyList())
        val keys = mutableMapOf<Pair<Scene, AudioMotion.Settings>, String>()
        val signals = linkedMapOf<String, AudioReactive.Signal>()
        for ((composition, settings) in references.distinct()) {
            val (profileKey, data) = profile(composition, settings.source, settings.respectVolume)
            val key = AudioReactive.responseKey(profileKey, settings)
            val signal = mutex.withLock {
                responses[key] ?: withContext(Dispatchers.Default) {
                    val response = data.data.response(settings.band, settings.gain, settings.gate, settings.attackMs, settings.releaseMs, settings.normalize, settings.invert)
                    AudioReactive.Signal(key, response)
                }.also { responses[key] = it; trim() }
            }
            keys[composition to settings] = key
            signals[key] = signal
        }
        require(signals.values.sumOf { it.response.levels.size.toLong() } <= 4_000_000) { context.getString(app.absentmotion.R.string.true_audio_buffer_limit) }
        return AudioReactive.Prepared(scene, AudioReactive.bind(scene) { composition, config -> keys[composition to config] }, signals.values.toList())
    }

    private fun trim() {
        fun bytes() = profiles.values.sumOf { it.second.data.count * 16L } + responses.values.sumOf { it.response.levels.size * 4L }
        while (bytes() > budget && profiles.size + responses.size > 1) {
            if (profiles.isNotEmpty()) profiles.remove(profiles.keys.first())
            else responses.remove(responses.keys.first())
        }
        while (profiles.size > 32) profiles.remove(profiles.keys.first())
        while (responses.size > 64) responses.remove(responses.keys.first())
    }
}

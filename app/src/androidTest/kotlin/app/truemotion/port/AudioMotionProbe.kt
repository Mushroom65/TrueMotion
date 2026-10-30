package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.audio.AudioEnvelope
import app.absentmotion.editor.analyzeAudioMotion
import app.absentmotion.editor.sourcesUnchanged
import app.absentmotion.engine.NativeEngine
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.*
import app.absentmotion.truemotion.AudioMotion
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

internal object AudioMotionProbe {
    fun run(context: Context, engine: NativeEngine, output: File): JSONObject = runBlocking {
        val store = ProjectStore(context)
        val wave = File(store.mediaDir("true-audio-regression").apply { mkdirs() }, "120bpm.wav")
        val rate = 16_000
        val samples = rate * 8
        val bytes = ByteBuffer.allocate(44 + samples * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(samples * 4)
        for (i in 0 until samples) {
            val time = i.toDouble() / rate
            val phase = time % .5
            val volume = .75 * exp(-phase * 35)
            val sample = (sin(2 * PI * 110 * time) * volume * 32767).toInt().toShort()
            bytes.putShort(sample).putShort((-sample).toShort())
        }
        wave.writeBytes(bytes.array())
        val uri = wave.toURI().toString()
        val scene = Scene.parse("""<scene title="True Audio UI" width="320" height="320" fps="30" totalTime="7999" bgcolor="#ff151724"><media uri="$uri" duration="8000000" filename="120bpm.wav"/><shape id="103" label="Audio square" startTime="0" endTime="7999" fillType="color"><transform><location value="160,160,0"/><scale value="1,1"/></transform><fillColor value="#ff00d9a5"/><path d="M-55,-55 L55,-55 L55,55 L-55,55 Z"/></shape><audio id="203" label="120 BPM stereo" src="$uri" startTime="0" endTime="7999" inTime="0" outTime="8000"><gain value="0.4"/></audio></scene>""")
        val raw = analyzeAudioMotion(context, scene, 203, false) {}
        val mixed = analyzeAudioMotion(context, scene, 203, true) {}
        check(raw.data.count in 799..801 && raw.data.peak(AudioEnvelope.Band.Full) > .1f)
        val ratio = mixed.data.peak(AudioEnvelope.Band.Full) / raw.data.peak(AudioEnvelope.Band.Full)
        check(ratio in .38f.. .42f) { "Audio gain ratio: $ratio" }
        check(raw.beats.size >= 8) { "Only ${raw.beats.size} beats detected" }
        val intervals = raw.beats.zipWithNext { a, b -> b - a }.sorted()
        val bpm = 60_000.0 / intervals[intervals.size / 2]
        check(bpm in 110.0..130.0) { "BPM $bpm, beats=${raw.beats}" }
        val timed = scene.update(203) { Layer(it.node.with("startTime", "1000").with("endTime", "2999").with("inTime", "1000").with("outTime", "5000").with("speed", "2")) }
        val trimmed = analyzeAudioMotion(context, timed, 203, false) {}
        check(trimmed.data.startMs == 1000 && trimmed.data.count in 199..201 && trimmed.data.peak(AudioEnvelope.Band.Full) > .1f)
        val muted = scene.update(203) { Layer(it.node.withChild("gain", XmlNode("gain", mapOf("value" to "0")))) }
        val audible = analyzeAudioMotion(context, muted, 203, false) {}
        check(audible.data.peak(AudioEnvelope.Band.Full) > .1f)
        val silent = runCatching { analyzeAudioMotion(context, muted, 203, true) {} }
        check(silent.isFailure || silent.getOrThrow().data.peak(AudioEnvelope.Band.Full) < 1e-6f)
        check(raw.sourcesUnchanged(context))
        val previousModified = wave.lastModified()
        check(wave.setLastModified(previousModified + 2000))
        check(!raw.sourcesUnchanged(context))
        check(wave.setLastModified(previousModified))
        val settings = AudioMotion.Settings(source = 203, amount = .5f, attackMs = 0f, releaseMs = 40f, gate = 0f, respectVolume = false)
        val response = raw.data.response(settings.band, 1f, 0f, 0f, 40f, true, false)
        val original = scene.layer(103)!!
        val baked = scene.update(103) { AudioMotion.bake(scene, it, settings, response) }
        val reopened = Scene.parse(baked.toXml())
        check(AudioMotion.restore(reopened.layer(103)!!, settings.property) == original)
        check(AudioMotion.bake(scene, reopened.layer(103)!!, settings, response) == reopened.layer(103))
        engine.setScene(reopened.toXml())
        val frames = (0..14).map { i ->
            val t = (2 * i + 1) * 500.0 / scene.fps
            val pixels = checkNotNull(engine.still(t, 320, 320))
            val count = (0 until pixels.size step 4).count { (pixels[it + 1].toInt() and 255) > 100 }
            Triple(t, count, pixels)
        }
        val small = frames.minBy { it.second }; val large = frames.maxBy { it.second }
        check(large.second > small.second * 1.2) { "Native audio keys do not animate: ${small.second}..${large.second}" }
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(large.third))
        File(output, "audio-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // This isolated project is for the following manual UI checks; no user project is overwritten.
        store.save("true-audio-regression", scene)
        val titles = listOf("en", "ru", "zh-CN").map { tag ->
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            context.createConfigurationContext(config).getString(app.absentmotion.R.string.true_audio_title)
        }
        check(titles == listOf("Audio animation", "Анимация от звука", "音频动画"))
        JSONObject().put("decodedStereoBins", raw.data.count).put("oppositePhasePreserved", true)
            .put("gainRatio", ratio).put("detectedBPM", bpm).put("beats", JSONArray(raw.beats))
            .put("trimSpeedOffset", true).put("muteAndVolumeBypass", true).put("sourceVersionGuard", true)
            .put("nativeKeyArea", JSONArray(listOf(small.second, large.second)))
            .put("originalRestoredAfterReopen", true).put("idempotentApply", true).put("uiLanguages", JSONArray(titles))
    }
}

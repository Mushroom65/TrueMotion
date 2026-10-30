package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.Scene
import app.absentmotion.model.withEffectProperty
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal object AfterZProbe {
    fun run(context: Context, engine: NativeEngine, directory: File, full: Boolean = true): JSONObject {
        val catalog = EffectCatalog.parse { context.assets.open("effects/$it") }
        check(catalog.message == null && catalog.definitions.size == 773)
        val donor = catalog.definitions.filter { it.dialect == "afterz" }
        check(donor.size == 575)
        val report = JSONObject().put("count", donor.size).put("fullRenderSweep", full)
        val compile = JSONArray()
        val renders = JSONArray()
        val compileStart = SystemClock.elapsedRealtime()
        for (effect in donor) {
            File(directory, "current.txt").writeText("compile ${effect.key}")
            val status = engine.loadEffect(effect.runtimeXml(lazy = false))
            compile.put(JSONObject().put("id", effect.key).put("status", status))
        }
        report.put("compiled", compile).put("compileMs", SystemClock.elapsedRealtime() - compileStart)
        File(directory, "progress.json").writeText(report.toString(2))
        check((0 until compile.length()).all { compile.getJSONObject(it).getString("status") == "ok" }) {
            "Native shader compilation failed; see afterz-validation/progress.json"
        }
        fun scene(effect: String, x: Int = 64) = """<scene width="128" height="128" totalTime="1999" fps="30" bgcolor="#00000000"><shape id="1" startTime="0" endTime="1999" fillType="color"><transform><location value="$x,64,0"/></transform><fillColor value="#fff1578e"/><path d="M-16,-20 L16,-20 L16,20 L-16,20 Z"/>$effect</shape></scene>"""
        fun render(xml: String): ByteArray { engine.setScene(xml); return checkNotNull(engine.still(500.0, 128, 128)) }
        fun save(pixels: ByteArray, name: String) {
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val neutral = render(scene(""))
        save(neutral, "source")
        for (effect in donor.filter { full || it.key in setOf("afterz-1", "afterz-677", "afterz-718", "afterz-768") }) {
            File(directory, "current.txt").writeText("render ${effect.key}")
            val start = SystemClock.elapsedRealtime()
            val pixels = render(scene(effect.instance().toXml().substringAfter("?>")))
            val count = (3 until pixels.size step 4).count { (pixels[it].toInt() and 255) > 0 }
            val sha = MessageDigest.getInstance("SHA-256").digest(pixels).joinToString("") { "%02x".format(it) }
            renders.put(JSONObject().put("id", effect.key).put("ms", SystemClock.elapsedRealtime() - start)
                .put("alphaPixels", count).put("changed", !pixels.contentEquals(neutral)).put("sha256", sha))
            if (full) save(pixels, "render-${effect.key}")
            if (effect.key in setOf("afterz-1", "afterz-200", "afterz-677", "afterz-718", "afterz-768")) save(pixels, effect.key)
            if (renders.length() % 20 == 0) File(directory, "renders.json").writeText(renders.toString(2))
        }
        report.put("renders", renders)
        File(directory, "progress.json").writeText(report.toString(2))
        for (key in listOf("afterz-718", "afterz-677", "afterz-768")) {
            val effect = donor.single { it.key == key }
            var base = effect.instance()
            // Respect the donor defaults: Deep Glow AE starts with exposure 0;
            // Saber/Deep Glow optionally mask the overdraw to the source alpha.
            for ((name, value) in mapOf("overdrawOn" to "true", "exposure" to "1", "glowOcclusion" to "1")) {
                val param = effect.params.firstOrNull { it.id == name } ?: continue
                base = base.withEffectProperty(name, param.type, value, 0f, 0f..0f)
            }
            if (key == "afterz-677") base = base.withEffectProperty("radius", "float", "8", 0f, 0f..0f)
            val pixels = render(scene(base.toXml().substringAfter("?>")))
            save(pixels, "$key-active")
            check(!neutral.contentEquals(pixels)) { "$key is a passthrough" }
            val outside = (0 until 128 * 128).count { i ->
                val x = i % 128; val y = i / 128
                (x !in 47..80 || y !in 43..84) && (pixels[i * 4 + 3].toInt() and 255) > 4
            }
            check(outside > 30) { "$key has no glow outside the source ($outside pixels)" }
            val param = if (key == "afterz-768") "glowInt" else "exposure"
            val changed = base.withEffectProperty(param, "float", "2", 0f, 0f..0f)
            check(!pixels.contentEquals(render(scene(changed.toXml().substringAfter("?>"))))) { "$key ignores $param" }
            val saved = Scene.parse(scene(changed.toXml().substringAfter("?>"))).toXml()
            check(render(saved).contentEquals(render(Scene.parse(saved).toXml()))) { "$key save/reopen differs" }
            report.put("${key}GlowPixels", outside)
        }
        val uri = "afterz-fixture.png"
        val bitmap = Bitmap.createBitmap(32, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 32) bitmap.setPixel(x, y,
            if ((x / 4 + y / 4) % 2 == 0) 0xffed3866.toInt() else 0xff27b5a0.toInt())
        fun media(effect: String, x: Int): ByteArray {
            engine.setScene(scene(effect, x).replace("fillType=\"color\"", "fillType=\"media\" fillImage=\"$uri\""))
            check(engine.setPicture(uri, bitmap))
            return checkNotNull(engine.still(500.0, 128, 128))
        }
        val checker = donor.single { it.key == "afterz-1" }.instance().toXml().substringAfter("?>")
        val mediaBase = media("", 64)
        val a = media(checker, 64)
        val b = media(checker, 80)
        check(!a.contentEquals(mediaBase)) { "Checker ignores image texture" }
        var different = 0
        for (y in 45..82) for (x in 49..78) for (c in 0..3) {
            if (kotlin.math.abs((a[(y * 128 + x) * 4 + c].toInt() and 255) - (b[(y * 128 + x + 16) * 4 + c].toInt() and 255)) > 2) different++
        }
        check(different < 100) { "Moving media detached its texture ($different channels differ)" }
        save(a, "checker-media")
        bitmap.recycle()
        report.put("mediaTextureFollowsLayer", true).put("mediaTranslationDifferentChannels", different)
        val depth = donor.single { it.key == "afterz-777" }.instance()
            .withEffectProperty("intensity", "float", "0", 0f, 0f..0f)
            .withEffectProperty("ambient", "float", "0.2", 0f, 0f..0f)
        val background = """<shape id="2" startTime="0" endTime="1999" fillType="color"><transform><location value="64,64,0"/></transform><fillColor value="#ff20d090"/><path d="M-64,-64 L64,-64 L64,64 L-64,64 Z"/></shape>"""
        val depthImage = render(scene(depth.toXml().substringAfter("?>")).replace("<shape id=\"1\"", "$background<shape id=\"1\""))
        val center = (64 * 128 + 64) * 4
        save(depthImage, "depth-relight-below")
        check((depthImage[center + 1].toInt() and 255) in 10..150) { "Depth relight did not read and darken the picture below: ${List(4) { depthImage[center + it].toInt() and 255 }}" }
        report.put("depthReadsBelow", true)
        val glow = donor.single { it.key == "afterz-718" }.instance()
            .withEffectProperty("exposure", "float", "1", 0f, 0f..0f)
        val exportScene = Scene.parse(scene(glow.toXml().substringAfter("?>")).replace("#00000000", "#ff000000").replace("totalTime=\"1999\"", "totalTime=\"999\""))
        val expected = render(exportScene.toXml())
        val exporter = app.absentmotion.engine.EngineThread(0, context)
        try {
            kotlinx.coroutines.runBlocking {
                exporter.setScene(exportScene)
                val exact = exporter.exportSession(exportScene.toXml()) { still(500, 128, 128) }
                check(exact != null && expected.contentEquals(exact)) { "Multipass export differs from native preview" }
                check(exporter.stillPng(exportScene.toXml(), 500, 128, 128, File(directory, "deepglow-export.png")))
                java.io.FileOutputStream(File(directory, "deepglow-export.mp4")).use { output ->
                    app.absentmotion.export.exportVideo(exporter, exportScene, exportScene.toXml(),
                        app.absentmotion.export.ExportSettings(128, 30), output.fd, null, progress = {})
                }
            }
            val decoder = android.media.MediaMetadataRetriever()
            val decoded = try {
                decoder.setDataSource(File(directory, "deepglow-export.mp4").absolutePath)
                checkNotNull(decoder.getFrameAtTime(500000, android.media.MediaMetadataRetriever.OPTION_CLOSEST))
            } finally { decoder.release() }
            check(decoded.width == 128 && decoded.height == 128)
            var error = 0L
            for (y in 0 until 128) for (x in 0 until 128) {
                val p = decoded.getPixel(x, y)
                for (c in 0..2) error += kotlin.math.abs(((p ushr (16 - 8 * c)) and 255) - (expected[(y * 128 + x) * 4 + c].toInt() and 255))
            }
            decoded.recycle()
            val mean = error.toDouble() / (128 * 128 * 3)
            check(mean < 8) { "Multipass MP4 pixel error $mean" }
            report.put("multipassExactExport", true).put("multipassMP4MeanError", mean)
        } finally { exporter.close() }
        return report
    }
}

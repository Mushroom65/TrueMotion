package app.truemotion.port

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.NativeEngine
import app.absentmotion.library.Importer
import app.absentmotion.library.ProjectStore
import app.absentmotion.model.*
import app.absentmotion.truemotion.Studio
import app.absentmotion.truemotion.Plugins
import app.absentmotion.truemotion.ColorGrade
import app.truemotion.vector.VectorDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

class PortInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = JSONObject()
        try {
            val context = targetContext
            val directory = File(context.filesDir, "port-validation").apply { mkdirs() }
            val gradeTitles = listOf("en", "ru", "zh-CN").map { tag ->
                val config = android.content.res.Configuration(context.resources.configuration)
                config.setLocale(java.util.Locale.forLanguageTag(tag))
                val translated = context.createConfigurationContext(config)
                translated.getString(app.absentmotion.R.string.true_grade_title).also {
                    check(translated.getString(app.absentmotion.R.string.true_grade_curve_point, 25).contains("25"))
                }
            }
            check(gradeTitles == listOf("Color grading", "Цветокоррекция", "调色"))
            report.put("gradeUILanguages", JSONArray(gradeTitles))
            val catalog = EffectCatalog.parse { context.assets.open("effects/$it") }
            NativeEngine(NativeEngine.Backend.GRAPHITE).use { engine ->
                check(!engine.failed) { engine.info }
                report.put("engine", JSONObject(engine.info))
                val loaded = catalog.loadInto(engine::loadEffect)
                report.put("nativeCatalog", JSONObject().put("total",loaded.definitions.size)
                    .put("available",loaded.definitions.count { it.available })
                    .put("unavailable",JSONArray(loaded.definitions.filterNot { it.available }.map { JSONObject().put("id",it.id).put("status",it.status) })))
                val ours = loaded.definitions.filter { it.key.startsWith("true-") }
                check(ours.size == 17 && ours.all { it.available }) { ours.joinToString { "${it.id}: ${it.status}" } }
                report.put("nativeShaders", JSONArray(ours.map { JSONObject().put("id", it.id).put("status", it.status) }))
                val base = """<scene width="256" height="256" totalTime="1999" fps="30" bgcolor="#00000000"><shape id="1" startTime="0" endTime="1999" fillType="color"><transform><location value="128,128,0"/></transform><fillColor value="#ffed335d"/><path d="M-70,-70 L70,-70 L70,70 L-70,70 Z"/>%s</shape></scene>"""
                fun render(xml: String): ByteArray { engine.setScene(xml); return checkNotNull(engine.still(0.0, 256, 256)) }
                for (effect in ours) {
                    val document=base.format(effect.instance().toXml().substringAfter("?>"))
                    val pixels = if(effect.id==app.absentmotion.truemotion.Particles.ID) { engine.setScene(document); checkNotNull(engine.still(1000.0,256,256)) } else render(document)
                    check((3 until pixels.size step 4).any { pixels[it].toInt() != 0 }) { "Empty default render: ${effect.id}" }
                }
                report.put("ownEffectsRendered", ours.size)
                val neutral = render(base.format(""))
                val grade = loaded.find("lab.true.grade")!!
                val graded = render(base.format(grade.instance().toXml().substringAfter("?>")))
                check(neutral.contentEquals(graded)) { "Neutral grade changed the image" }
                val changedEffect = grade.instance().withEffectProperty("exposure", "float", "-2", 0f, 0f..0f)
                val changed = render(base.format(changedEffect.toXml().substringAfter("?>")))
                check(!neutral.contentEquals(changed)) { "Exposure did not change the image" }
                report.put("gradeNeutralExact", true).put("gradeExposureResponds", true)
                val gradeSections = linkedMapOf(
                    "wheels" to mapOf("liftR" to .2f, "gammaB" to -.3f, "gainG" to .4f),
                    "curves" to mapOf("curveY2" to .3f, "curveR1" to .15f),
                    "hsl" to mapOf("hslRedH" to 40f, "hslRedS" to -.6f),
                )
                val gradeSource = Scene.parse(base.format(grade.instance().toXml().substringAfter("?>")))
                for ((section, values) in gradeSections) {
                    val scene = gradeSource.update(1, 0) { ColorGrade.change(it, 0, grade, values) }
                    check(!render(scene.toXml()).contentEquals(neutral)) { "$section did not affect the native render" }
                }
                val animated = gradeSource.update(1, 0) { it.withKeyframeToggled(PropRef.effect(0, "exposure"), "0") }
                    .update(1, 1000) { ColorGrade.change(it, 0, grade, mapOf("exposure" to -2f)) }
                val restored = Scene.parse(animated.toXml())
                check(kotlin.math.abs(ColorGrade.value(restored.layerAt(1, 1000)!!, 0, grade, "exposure") + 2f) < 1e-4f)
                engine.setScene(restored.toXml())
                check(!engine.still(1000.0, 256, 256)!!.contentEquals(neutral))
                report.put("gradeSectionsRender", JSONArray(gradeSections.keys.toList())).put("gradeAnimatedReopen", true)
                val source = Scene.parse(base.format(""))
                val array = Studio.array(source, 1, 6, 3, 25f, 25f, false, 100f, 2f, false)
                check(array.layers.size == 6)
                check(render(array.toXml()).any { it.toInt() != 0 })
                report.put("arrayRendered", true)
                val plugin = Plugins.bundled(context).first { it.id == "builtin.layer_tools" }
                val offCenter = source.update(1) { it.withTransform("location", 10f, 20f, 0f) }
                val plan = Plugins.run(plugin, "center", offCenter, 1, 0, JSONObject())
                val adapted = Plugins.plan(offCenter, plan.getJSONArray("operations"), 0, loaded, app.absentmotion.compat.EffectMigration.read(context, loaded))
                check(adapted.layers.single().location[0] == 128f)
                check(adapted.layers.single().location[1] == 128f && adapted != offCenter)
                report.put("pluginCommand", true)
                val hook = PluginRuntime.hook("exports.hooks.layerAdded=function(api){api.rename(api.event.id,'Hooked');api.state.count=1;};", "layerAdded",
                    Plugins.snapshot(source, 1, 0), JSONObject(), JSONObject().put("id", "1"), JSONObject())
                val hooked = Plugins.plan(source, hook.getJSONArray("operations"), 0, loaded, app.absentmotion.compat.EffectMigration.read(context, loaded))
                check(hooked.layers.single().label == "Hooked" && hook.getJSONObject("state").getInt("count") == 1)
                report.put("pluginHook", true)
                val document = VectorDocument(256, 256, 30.0, 60)
                val shape = VectorDocument.Shape()
                shape.closed = true
                shape.strokeWidth = 0.0
                shape.fill = 0xffffaa11.toInt()
                shape.nodes.add(VectorDocument.Node(30.0, 30.0)); shape.nodes.add(VectorDocument.Node(150.0, 30.0)); shape.nodes.add(VectorDocument.Node(70.0, 150.0))
                document.tracks[0].keys[0]!!.shapes.add(shape)
                val saved = document.json()
                val reopened = VectorDocument.read(saved)
                val vectorPixels = render(reopened.nativeScene())
                check((3 until vectorPixels.size step 4).count { vectorPixels[it].toInt() != 0 } > 100)
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(vectorPixels))
                File(directory, "vector-native.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                report.put("vectorSaveReloadRendered", true)
                report.put("audioMotion", AudioMotionProbe.run(context, engine, directory))
                report.put("audioReactive", AudioReactiveProbe.run(context, engine, directory))
                report.put("anchor", AudioReactiveProbe.anchor(engine))
                report.put("particles", ParticlesProbe.run(context, engine, directory))
                report.put("groups", GroupsProbe.run(context, engine, directory))
                report.put("videoTools", VideoToolsProbe.run(context, directory))
                report.put("motionPaths", MotionPathsProbe.run(context, engine, directory))
                report.put("upstreamV4", UpstreamV4Probe.run(context, directory))
            }
            val fixture = File(context.filesDir, "port-input.alight")
            if (fixture.exists()) {
                val imported = Importer.forApp(context, ProjectStore(context)).import { fixture.inputStream() }
                check(imported.size == 1 && !imported.single().template)
                report.put("realAlightImported", imported.single().id)
            }
            report.put("ok", true)
            File(directory, "report.json").writeText(report.toString(2))
            finish(0, Bundle().apply { putString("report", report.toString()) })
        } catch (error: Throwable) {
            report.put("ok", false).put("error", error.stackTraceToString())
            File(targetContext.filesDir, "port-validation").mkdirs()
            File(targetContext.filesDir, "port-validation/report.json").writeText(report.toString(2))
            finish(1, Bundle().apply { putString("error", error.stackTraceToString()) })
        }
    }
}

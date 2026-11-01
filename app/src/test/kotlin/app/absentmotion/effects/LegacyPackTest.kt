package app.absentmotion.effects

import app.truemotion.port.LegacyRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LegacyPackTest {
    private val catalog by lazy { EffectCatalog.parse { File("src/main/assets/effects", it).inputStream() } }
    @Test fun legacyGraphsHaveResolvableReferencesAndOriginalParameterNames() {
        val legacy = catalog.definitions.filter { it.dialect == "legacy" }
        assertEquals(1746, legacy.size)
        val missing=mutableSetOf<String>()
        for (effect in legacy) {
            assertEquals("tmold.${effect.sourceId}", effect.id)
            assertEquals(effect.params.size, effect.params.map { it.id }.toSet().size)
            for (stage in effect.stages) if (stage.reference.isNotEmpty()) {
                if(catalog.find("tmold.${stage.reference}")==null)missing += effect.sourceId.substringAfterLast('.')
            }
        }
        // Six wrappers in the original donor already refer to absent hidden passes.
        // Keep this limitation explicit and reject any additional dangling dependency.
        assertEquals(setOf("rockfield","starburst","simplegrid","featherfalls","synthwave","starglowparticle"),missing)
    }
    @Test fun everyAnimationScriptRunsAtSeveralFramesWithItsOwnDefaults() {
        val failures = mutableListOf<String>()
        for (effect in catalog.definitions.filter { it.dialect == "legacy" && it.legacyScript.isNotEmpty() }) {
            val p=JSONObject()
            for (param in effect.params) {
                val values=param.default.split(',').mapNotNull(String::toDoubleOrNull)
                p.put(param.id,when(param.kind) {
                    "text" -> param.default
                    "switch" -> param.default=="true"
                    "point", "vector3", "quaternion" -> JSONObject().also { o -> values.forEachIndexed { i,v -> o.put(listOf("x","y","z","w")[i],v) } }
                    "color" -> JSONObject("""{"a":1,"r":0.7,"g":0.2,"b":0.4}""")
                    else -> values.first()
                })
            }
            for (time in listOf(0.0,0.25,0.8)) {
                val q=JSONObject("""{"env":{"duration":4,"startTime":2,"endTime":6,"inTime":2,"fps":30,"frame":30,"prevFrameTime":0.2,"velocity":{"x":30,"y":-10},"angularVelocity":12,"scaleVelocity":0.1,"selected":false,"editMode":false},"el":{"alpha":0.8,"text":"Test Текст 中文","shaderGroups":[0],"transform":{"location":{"x":100,"y":80,"z":0},"pivot":{"x":0,"y":0},"scale":{"x":1,"y":1},"angle":20}}}""")
                q.getJSONObject("env").put("time",time).put("absTime",2+time*4)
                q.put("p",p)
                val result=String(LegacyRuntime.evaluate(effect.id.toByteArray(),effect.legacyScript.toByteArray(),q.toString().toByteArray()))
                if ("error=" in result) failures += "${effect.sourceId} at $time: $result"
            }
        }
        File("build/legacy-script-errors.txt").also { it.parentFile.mkdirs() }.writeText(failures.joinToString("\n"))
        assertTrue(failures.take(12).joinToString("\n"),failures.isEmpty())
    }
    @Test fun aScriptCannotImportJavaOrRunForever() {
        val input="""{"env":{},"el":{},"p":{}}""".toByteArray()
        for(source in listOf("function animate(){while(true){}}","function animate(){return java.lang.System.getProperties();}"))
            assertTrue(String(LegacyRuntime.evaluate("limit".toByteArray(),source.toByteArray(),input)).contains("error="))
    }
}

package app.absentmotion.effects

import app.absentmotion.model.Scene
import app.absentmotion.model.withEffectProperty
import app.absentmotion.model.keyframes
import app.absentmotion.compat.EffectMigration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AfterZPortTest {
    private val assets = File("src/main/assets/effects")
    private val catalog = EffectCatalog.parse { File(assets, it).inputStream() }
    private val donor = catalog.definitions.filter { it.dialect == "afterz" }

    @Test fun donorCategoriesAndRuntimeIdsCannotReplaceOriginalEffects() {
        val manifest = JSONObject(File("../docs/AFTERZ_EFFECT_PORT.json").readText())
        assertEquals(manifest.getInt("converted"), donor.size)
        assertEquals(donor.size, donor.map { it.id }.toSet().size)
        assertTrue(donor.all { it.id.startsWith("amz.") && it.key.startsWith("afterz-") && it.category.startsWith("amz-") })
        assertTrue(catalog.definitions.filter { it.dialect != "afterz" }.none { it.category.startsWith("amz-") || it.id.startsWith("amz.") })
        val originals = manifest.getJSONObject("originalDefinitionsNormalizedSHA256")
        for (key in originals.keys()) {
            val bytes = File(assets, "$key.json").readText().replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("Original effect $key changed", originals.getString(key), hash)
        }
    }

    @Test fun selectorRetainsDonorValuesInsteadOfListPositions() {
        val effect = donor.single { it.key == "afterz-112" }
        val direction = effect.params.single { it.id == "direction" }
        assertEquals(listOf(5f, 3f, 4f, 0f, 1f, 2f), direction.optionValues)
        assertEquals("4.000000", effect.instance().children.single { it["name"] == "direction" }["value"])
        assertTrue(effect.runtimeXml().contains("uniform=\"amzp_direction\""))
    }

    @Test fun xyzValuesStayThreeDimensionalWhenSavedAndReopened() {
        val effect = donor.single { it.key == "afterz-147" }
        val pos = effect.params.single { it.id == "pos" }
        assertEquals("vec3", pos.type)
        val node = effect.instance().withEffectProperty("pos", "vec3", "12,-34,56", 0f, 0f..0f)
        val xml = """<scene width="128" height="128" totalTime="999" fps="30"><shape id="1" startTime="0" endTime="999">${node.toXml().substringAfter("?>")}</shape></scene>"""
        val reopened = Scene.parse(Scene.parse(xml).toXml()).layers.single().node.children.single { it.name == "effect" }
        assertEquals(effect.id, reopened["id"])
        assertEquals("12,-34,56", reopened.children.single { it["name"] == "pos" }["value"])
    }

    @Test fun multipassEffectsKeepIntermediateBuffersAndCompileOnlyWhenUsed() {
        for ((key, count) in listOf("afterz-677" to 8, "afterz-718" to 7, "afterz-768" to 8)) {
            val effect = donor.single { it.key == key }
            assertEquals(count, effect.stages.size)
            assertTrue(effect.stages.last().target.isEmpty())
            assertTrue(effect.stages.dropLast(1).all { it.target.isNotEmpty() })
            assertTrue(effect.textures.any { it.source == "buffer" })
            assertTrue(effect.runtimeXml().contains("lazy=\"true\""))
            assertTrue(effect.runtimeXml(lazy = false).contains("lazy=\"false\""))
        }
    }

    @Test fun donorProjectImportPreservesAnimatedValuesAndOriginalImportRules() {
        val rules = File(assets, "foreign.json").readText()
        val root = JSONObject(rules)
        assertEquals("blur", root.getJSONObject("com.alightcreative.effects.gaussianblur").getString("to"))
        val effect = donor.single { it.key == "afterz-718" }
        val source = JSONObject(File(assets, "${effect.key}.json").readText()).getString("sourceId")
        val xml = """<scene width="128" height="128" totalTime="999" fps="30"><shape id="1" startTime="0" endTime="999"><effect id="$source"><property name="exposure" type="float"><kf t="0" v="0"/><kf t="1" v="2" e="cubicBezier 0.2 0 0.8 1"/></property></effect></shape></scene>"""
        val result = EffectMigration.parse(rules, catalog).migrate(Scene.parse(xml))
        assertTrue(result.changed)
        assertTrue(result.dropped.isEmpty())
        val converted = result.scene.layers.single().node.children.single { it.name == "effect" }
        assertEquals(effect.id, converted["id"])
        val frames = converted.children.single { it["name"] == "exposure" }.keyframes
        assertEquals(listOf(0f, 1f), frames.map { it.t })
        assertEquals(listOf("0.000000", "2.000000"), frames.map { it.v })
        assertEquals("cubicBezier 0.2 0 0.8 1", frames.last().e)
    }
}

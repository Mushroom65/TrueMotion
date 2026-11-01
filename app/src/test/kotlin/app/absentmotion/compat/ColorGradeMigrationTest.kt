package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.*
import app.absentmotion.truemotion.ColorGrade
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ColorGradeMigrationTest {
    companion object {
        private val pack = File("src/main/assets/effects")
        private val catalog by lazy { EffectCatalog.parse { File(pack, it).inputStream() } }
        private val migration by lazy { EffectMigration.parse(File(pack, "foreign.json").readText(), catalog) }
        private val native by lazy { catalog.find(ColorGrade.ID)!! }
        private val classic by lazy { catalog.definitions.single { it.dialect == "legacy" && it.sourceId == ColorGrade.ID } }
    }

    private fun scene(effect: XmlNode, origin: String = "app.absentmotion", forceClassic: Boolean = false): Scene =
        Scene(XmlNode("scene", mapOf("width" to "320", "height" to "320", "fps" to "30", "totalTime" to "2000",
            "am" to origin, "trueLegacyEffects" to forceClassic.toString()), listOf(
            XmlNode("embedScene", mapOf("id" to "1"), listOf(XmlNode("scene", mapOf("width" to "320", "height" to "320"), listOf(
                XmlNode("shape", mapOf("id" to "2", "startTime" to "0", "endTime" to "2000"), listOf(effect)))))))))

    private fun effect(scene: Scene): XmlNode = scene.node.children.single().children.single().children.single().children.single()

    private fun animated(): XmlNode = native.instance().copy(children = native.instance().children.map {
        if (it["name"] == "exposure") XmlNode.parse("""<property name="exposure" type="float"><kf t="0" v="-1"/><kf t="1" v="1.5" e="cubicBezier 0.42 0 0.58 1"/></property>""") else it
    }).with("hidden", "true")

    @Test fun nativeGradeSurvivesOpeningAndImportRegardlessOfClassicOrigin() {
        val original = animated()
        for (origin in listOf("app.absentmotion", "com.alightcreative.motion/5.0", "com.alightcreative.tojimotionlab/5.0")) {
            for (forced in listOf(false, true)) {
                val once = migration.migrate(scene(original, origin, forced))
                assertEquals("$origin forced=$forced", original, effect(once.scene))
                val reopened = migration.migrate(Scene.parse(once.scene.toXml()))
                assertEquals(original, effect(reopened.scene))
                assertFalse(reopened.changed)
                assertTrue(once.dropped.isEmpty())
            }
        }
    }

    @Test fun substitutedNativeGradeIsRecoveredWithoutLosingKeysOrAdvancedControls() {
        val original = animated()
        val broken = original.with("id", classic.id)
        val restored = migration.migrate(scene(broken))
        assertEquals(original, effect(restored.scene))
        assertTrue(restored.changed)
        assertTrue(restored.dropped.isEmpty())
        assertFalse(migration.migrate(restored.scene).changed)
    }

    @Test fun deliberateClassicGradeRetainsItsOwnIdentityAndSettings() {
        val original = classic.instance().with("hidden", "true")
        val kept = migration.migrate(scene(original))
        assertEquals(original, effect(kept.scene))
        assertFalse(kept.changed)
    }

    @Test fun oldGradeIdUsesTheNativeEditorAndKeepsOriginalParameters() {
        val original = classic.instance().with("id", ColorGrade.ID)
        val result = migration.migrate(scene(original, "com.alightcreative.tojimotionlab/5.0"))
        assertEquals(original, effect(result.scene))
        assertTrue(result.dropped.isEmpty())
    }
}

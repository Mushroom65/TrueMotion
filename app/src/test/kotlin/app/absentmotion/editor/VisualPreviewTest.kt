package app.absentmotion.editor

import app.absentmotion.model.Scene
import org.junit.Assert.*
import org.junit.Test

class VisualPreviewTest {
    private val original = Scene.create("original", 256, 256, 30, 0)
    @Test fun previewNeverChangesTheCanonicalSceneAndClearRestoresIt() {
        val draws = ArrayList<Scene>()
        val controller = VisualPreview({ original }) { scene, _ -> draws += scene }
        val owner = Any(); val shown = original.withTitle("unsaved")
        assertTrue(controller.show(owner, original, shown))
        assertEquals(listOf(shown), draws)
        assertEquals("original", original.title)
        controller.clear(owner)
        assertEquals(listOf(shown, original), draws)
    }
    @Test fun anOldPanelCannotClearANewerPanelsPreview() {
        val draws = ArrayList<Scene>()
        val controller = VisualPreview({ original }) { scene, _ -> draws += scene }
        val old = Any(); val next = Any()
        controller.show(old, original, original.withTitle("old"))
        controller.show(next, original, original.withTitle("new"))
        controller.clear(old)
        assertEquals("new", draws.last().title)
        controller.clear(next)
        assertEquals(original, draws.last())
    }
    @Test fun asyncResultFromAnOlderSceneCannotReplaceANewEdit() {
        var canonical = original
        val draws = ArrayList<Scene>()
        val controller = VisualPreview({ canonical }) { scene, _ -> draws += scene }
        canonical = original.withTitle("edited")
        assertFalse(controller.show(Any(), original, original.withTitle("stale")))
        assertTrue(draws.isEmpty())
    }
}

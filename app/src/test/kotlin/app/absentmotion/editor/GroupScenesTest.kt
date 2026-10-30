package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class GroupScenesTest {
    private val source = Scene.parse("""<scene title="Root" width="320" height="240" fps="30" totalTime="2999">
        <media uri="clip" width="1280" height="720" duration="3000000" type="video/mp4"/>
        <shape id="9" label="Sibling" startTime="0" endTime="2999"/>
        <embedScene id="1" label="Outer" startTime="1000" endTime="2999"><transform><rotation value="20"/></transform>
        <scene width="320" height="240" fps="24" totalTime="1999" retime="off">
        <shape id="2" startTime="0" endTime="1999" fillType="media" fillVideo="clip"><transform><rotation><kf t="0" v="0"/><kf t="1" v="90"/></rotation></transform></shape>
        <embedScene id="3" label="Inner" startTime="500" endTime="1499"><scene width="100" height="100" fps="60" totalTime="999" retime="off"><text id="4" startTime="0" endTime="999"/></scene></embedScene>
        </scene></embedScene></scene>""")

    @Test fun nestedEditorGetsItsOwnDimensionsFpsAndInheritedMedia() {
        val nested = source.groupScene(listOf(1, 3))!!
        assertEquals(100, nested.width)
        assertEquals(60, nested.fps)
        assertEquals(3000000L, nested.media("clip")!!.duration)
        assertEquals(listOf(4L), nested.layers.map { it.id })
    }

    @Test fun editDeepGroupPreservesRootSiblingsAndParentTransforms() {
        val inner = source.groupScene(listOf(1, 3))!!
        val next = source.withGroupScene(listOf(1, 3), inner.update(4) { it.withLabel("Edited") })
        assertEquals("Edited", next.groupScene(listOf(1, 3))!!.layer(4)!!.label)
        assertEquals(source.layer(9), next.layer(9))
        assertEquals(source.layer(1)!!.node.child("transform"), next.layer(1)!!.node.child("transform"))
        assertEquals(source.layer(1)!!.startTime, next.layer(1)!!.startTime)
        assertEquals(source.layer(1)!!.endTime, next.layer(1)!!.endTime)
        assertTrue(next.layer(1)!!.nested!!.media.isEmpty())
        assertTrue(next.groupScene(listOf(1))!!.layer(3)!!.nested!!.media.isEmpty())
    }

    @Test fun navigationWithoutEditsDoesNotChangeDocument() {
        assertEquals(source, source.withGroupScene(listOf(1, 3), source.groupScene(listOf(1, 3))!!))
    }

    @Test fun localEditDetachesLinkedInstanceSoReloadCannotOverwriteIt() {
        val linked = source.update(1) { it.copy(node = it.node.with("link", "source-asset")) }
        assertEquals(linked, linked.withGroupScene(listOf(1), linked.groupScene(listOf(1))!!))
        val next = linked.withGroupScene(listOf(1), linked.groupScene(listOf(1))!!.update(2) { it.withLabel("Local") })
        assertNull(next.layer(1)!!.link)
        assertEquals("source-asset", next.layer(1)!!.node["trueSourceLink"])
        assertEquals("Local", next.groupScene(listOf(1))!!.layer(2)!!.label)
    }

    @Test fun mediaAddedInsideGroupIsRetainedAndAvailableAtRoot() {
        val local = source.groupScene(listOf(1, 3))!!
        val record = MediaDeclaration(XmlNode("media", mapOf("uri" to "new-image", "width" to "640")))
        val changed = local.withMedia(record).add(Layer(XmlNode("shape", mapOf("id" to "5", "endTime" to "999", "fillImage" to "new-image"))))
        val next = source.withGroupScene(listOf(1, 3), changed)
        assertEquals(record, next.media("new-image"))
        assertEquals(record, next.groupScene(listOf(1, 3))!!.media("new-image"))
        assertNotNull(Scene.parse(next.toXml()).groupScene(listOf(1, 3))!!.layer(5))
    }

    @Test fun undoRedoInsideGroupUsesWholeDocumentSnapshots() {
        var current = source
        val history = EditHistory({ current }) { next, _ -> current = next }
        val changed = source.groupScene(listOf(1))!!.update(2) { it.withLabel("Changed") }
        history.edit(null, source.withGroupScene(listOf(1), changed))
        history.undo()
        assertEquals(source, current)
        history.redo()
        assertEquals("Changed", current.groupScene(listOf(1))!!.layer(2)!!.label)
        assertEquals(source.layer(9), current.layer(9))
    }

    @Test fun deletedGroupClosesOnlyInvalidPartOfPath() {
        assertEquals(listOf(1L), source.validGroupPath(listOf(1, 777)))
        assertEquals(emptyList<Long>(), source.remove(1).validGroupPath(listOf(1, 3)))
        assertNull(source.groupScene(listOf(1, 777)))
        assertEquals(source, source.withGroupScene(listOf(777), source))
    }

    @Test fun extendingGroupHoldsLastFrameAndLeavesAllInnerKeysUntouched() {
        val group = source.layer(1)!!
        val next = group.trimmedTo(1000, 6999)
        assertEquals("freeze", next.nested!!.retime)
        assertEquals(group.nested!!.layers, next.nested!!.layers)
        assertEquals(1999, next.nested!!.totalTime)
        assertEquals(1979.0, next.nestedTime(6000.0, 30).ms, 0.0)
        assertEquals(0.0, next.nestedTime(6000.0, 30).rate, 0.0)
    }

    @Test fun trimRestoreDoesNotDiscardInnerContentOrChangeKeyTimes() {
        val group = source.layer(1)!!
        val short = group.trimmedTo(1500, 2499)
        val back = short.trimmedTo(1000, 2999)
        assertEquals(group.nested!!.layers, back.nested!!.layers)
        assertEquals(0, back.nestedCut)
        assertEquals(group.nestedTime(1600.0, 30), back.nestedTime(1600.0, 30))
    }

    @Test fun explicitTimingAndCustomSpeedAreNotOverridden() {
        val group = source.layer(1)!!
        for (method in listOf("freeze", "stretch", "loop", "blank", "off")) {
            val inner = group.nested!!.node.with("retime", method).with("trueTimingExplicit", "true")
            val mode = group.copy(node = group.node.withChild("scene", inner))
            assertEquals(inner, mode.trimmedTo(1000, 6999).nested!!.node)
        }
        val fast = group.copy(node = group.node.with("speed", "2"))
        assertEquals("off", fast.trimmedTo(1000, 6999).nested!!.retime)
    }

    @Test fun reopeningAlreadyExtendedGroupRepairsHoldWithoutTouchingKeys() {
        val old = source.update(1) { it.withTimes(1000, 6999) }
        val next = old.withGroupHolds()
        assertEquals("freeze", next.layer(1)!!.nested!!.retime)
        assertEquals(source.layer(1)!!.nested!!.layers, next.layer(1)!!.nested!!.layers)
        assertEquals(next, next.withGroupHolds())
        assertEquals(source, source.withGroupHolds())
    }

    @Test fun holdStopsAudioAfterContentEndsButKeepsVideoFrameActive() {
        val next = source.update(1) { it.trimmedTo(1000, 6999) }
        val track = next.layerTracks().first { it.layer.id == 2L }
        val time = track.timeAt(6000.0, true)!!
        assertEquals(1979.0, time.ms, 0.0)
        assertEquals(0.0, time.rate, 0.0)
    }
}

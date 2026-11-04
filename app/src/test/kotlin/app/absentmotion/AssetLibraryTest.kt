package app.absentmotion

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class AssetLibraryTest {
    private fun media(uri: String, type: String = "image/png") = MediaDeclaration(XmlNode("media", mapOf("uri" to uri, "type" to type, "width" to "100", "height" to "100")))
    private fun source(): Scene = Scene.parse("""<scene width="400" height="300" fps="30" totalTime="3999">
        <media uri="a" type="image/png"/>
        <shape id="1" s=".rect" label="Art" startTime="1000" endTime="3999" fillType="media" fillImage="a" parent="8">
        <transform><location value="50,60,0"/><rotation><kf t="0" v="10"/><kf t="1" v="80"/></rotation></transform>
        <property name="size" type="vec2" value="50,50"/>
        <effect id="example"/>
        </shape><nullobj id="8" startTime="0" endTime="3999"/></scene>""")

    @Test fun unusedLibraryAssetsSurviveLayerDeletionAndRemainPackaged() {
        val library = source().withLibraryMedia(media("a")).withLibraryMedia(media("unused"))
        val removed = library.remove(1)
        assertEquals(setOf("a", "unused"), removed.libraryMedia.map { it.uri }.toSet())
        assertEquals(setOf("a", "unused"), removed.node.storedMediaUris())
        assertEquals(removed.libraryMedia, Scene.parse(removed.toXml()).libraryMedia)
    }

    @Test fun removingLibraryMembershipNeverBreaksUsedLayers() {
        val library = source().withLibraryMedia(media("a")).withLibraryMedia(media("unused"))
        val next = library.withoutLibraryMedia("a").withoutLibraryMedia("unused")
        assertTrue(next.libraryMedia.isEmpty())
        assertNotNull(next.media("a"))
        assertNull(next.media("unused"))
        assertEquals(source().layer(1), next.layer(1))
    }

    @Test fun replacementIsHeldAtExactFrameBoundaryAndKeepsParentTransformAndEffects() {
        val before = source()
        val next = before.withMediaSwitch(1, 2000, media("b"))
        val group = next.layer(1)!!
        assertTrue(group.isMediaSwitch)
        assertEquals(before.layer(1)!!.node["parent"], group.node["parent"])
        assertEquals(before.layer(1)!!.node.child("transform"), group.node.child("transform"))
        assertEquals(before.layer(1)!!.effects, group.effects)
        val clips = group.nested!!.layers
        assertEquals(listOf(0, 1000), clips.map { it.startTime })
        assertEquals(listOf(999, 2999), clips.map { it.endTime })
        assertEquals(listOf("a", "b"), clips.map { it.mediaFill!!.uri })
        assertTrue(clips.first().activeAt(999))
        assertFalse(clips.first().activeAt(1000))
        assertTrue(clips.last().activeAt(1000))
        assertEquals(setOf("a", "b"), next.node.imageUris())
        assertEquals(listOf(1000, 2000, 3999), group.keyframeTimes())
    }

    @Test fun repeatedEditOnSameFrameReplacesOneKeyAndDeleteExtendsPreviousExposure() {
        val two = source().withMediaSwitch(1, 2000, media("b")).withMediaSwitch(1, 2010, media("c"))
        assertEquals(2, two.layer(1)!!.mediaSwitchKeys.size)
        val one = two.withoutMediaSwitchKey(1, 2010)
        assertEquals(1, one.layer(1)!!.mediaSwitchKeys.size)
        assertEquals("a", one.layer(1)!!.nested!!.layers.single().mediaFill!!.uri)
        assertEquals(2999, one.layer(1)!!.nested!!.layers.single().endTime)
    }

    @Test fun videoKeysAreNativeVideoLayersAndDiscoverableForPackagingAndPlayback() {
        val scene = source().withMediaSwitch(1, 2500, media("clip", "video/mp4"))
        val clip = scene.layer(1)!!.nested!!.layers.last()
        assertTrue(clip.mediaFill!!.video)
        assertEquals(0, clip.mediaFill!!.inTime)
        assertEquals("true", clip.node["loop"])
        assertTrue(scene.node.storedMediaUris().contains("clip"))
        assertTrue(scene.layerTracks().any { it.layer.mediaFill?.uri == "clip" })
        assertEquals(scene.layer(1)!!.node, Scene.parse(scene.toXml()).layer(1)!!.node)
    }

    @Test fun switchesOutsideLayerOrOnUnsupportedLayersAreNoOps() {
        val source = source()
        assertEquals(source, source.withMediaSwitch(1, 0, media("b")))
        assertEquals(source, source.withMediaSwitch(8, 2000, media("b")))
        assertEquals(source, source.withMediaSwitch(1, 2000, media("sound", "audio/wav")))
    }

    @Test fun copiedSwitchHasIndependentIdsAndRemappedMediaIncludingFutureKeys() {
        val scene = source().withMediaSwitch(1, 2000, media("b"))
        val first = scene.layer(1)!!
        val copy = copyLayers(listOf(first)).single()
        assertNotEquals(first.id, copy.id)
        assertTrue(first.nested!!.layers.map { it.id }.toSet().intersect(copy.nested!!.layers.map { it.id }.toSet()).isEmpty())
        val mapped = Scene(scene.node.mapMediaUris { "new-$it" })
        assertEquals(listOf("new-a", "new-b"), mapped.layer(1)!!.nested!!.layers.map { it.mediaFill!!.uri })
        // URI-valued keys must agree with their source attributes after package relocation.
        assertEquals(listOf("new-a", "new-b"), mapped.layer(1)!!.mediaSwitchKeys.map { it.v })
    }
    @Test fun movingTimelineDiamondRebuildsExposuresAndManualGroupEditsDetachTrack() {
        val before = source().withMediaSwitch(1, 2000, media("b"))
        val moved = before.update(1) { layer ->
            val track = layer.node.child("trueMediaSwitch")!!
            layer.copy(node = layer.node.withChild("trueMediaSwitch", track.withKeyframes(track.keyframes.mapIndexed { i, k -> if (i == 1) k.copy(t = 0.75f) else k })))
        }.refreshMediaSwitches(before)
        assertEquals(2249, moved.layer(1)!!.nested!!.layers.last().startTime)
        val nested = before.groupScene(listOf(1))!!
        val edited = before.withGroupScene(listOf(1), nested.update(nested.layers.first().id) { it.withLabel("Manual") })
        assertFalse(edited.layer(1)!!.isMediaSwitch)
        assertEquals("Manual", edited.layer(1)!!.nested!!.layers.first().label)
    }

    @Test fun relinkingMediaKeepsLibraryMembershipAndReplacementKeys() {
        val scene = source().withLibraryMedia(media("a")).withMediaSwitch(1, 2000, media("b"))
            .replaceMedia("a", media("relinked"))
        assertEquals(listOf("relinked"), scene.libraryMedia.map { it.uri })
        assertEquals("relinked", scene.layer(1)!!.mediaSwitchKeys.first().v)
        assertEquals("relinked", scene.layer(1)!!.nested!!.layers.first().mediaFill!!.uri)
    }

}

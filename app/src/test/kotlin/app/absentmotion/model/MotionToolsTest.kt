package app.absentmotion.model

import org.junit.Assert.*
import org.junit.Test

class MotionToolsTest {
    private val x = ValueChannel(PropRef.Location, 0)
    private val y = ValueChannel(PropRef.Location, 1)
    private val rotation = ValueChannel(PropRef.Rotation)
    private fun scene() = Scene.parse("""<scene width="500" height="400" fps="30" totalTime="2999">
        <shape id="1" label="Original" startTime="0" endTime="2999"><transform><location value="100,200,50"/><rotation value="10"/><scale value="1,2"/></transform></shape>
        <shape id="2" label="Follower" startTime="500" endTime="2499"><transform><location value="30,40,60"/></transform></shape>
        <shape id="3" label="Third" startTime="0" endTime="2999"/>
        </scene>""")

    @Test fun linksUseGlobalTimeAndPreserveUnlinkedAxes() {
        val source = scene().update(1) { l -> l.withElement(PropRef.Location, XmlNode("location").withKeyframes(listOf(Keyframe(0f,"100,200,50"), Keyframe(1f,"400,200,50")))) }
        val linked = source.withValueLink(2, x, ValueLink(1, x, 2f, 10f, 500))
        val p = linked.layerAt(2, 1500)!!.location
        assertEquals(410.0667f, p[0], .2f)
        assertEquals(40f, p[1], .001f)
        assertEquals(60f, p[2], .001f)
        assertEquals(ValueLink(1,x,2f,10f,500), Scene.parse(linked.toXml()).layer(2)!!.valueLink(x))
    }

    @Test fun chainsUpdateWhenSourceChangesAndRejectCycles() {
        val linked = scene().withValueLink(2, x, ValueLink(1, x, 2f, 10f)).withValueLink(3, rotation, ValueLink(2, x, .5f))
        assertEquals(105f, linked.layer(3)!!.transform("rotation",0f)[0], .001f)
        val updated = linked.update(1) { it.withTransform("location",150f,200f,50f) }.refreshMotionTools()
        assertEquals(155f, updated.layer(3)!!.transform("rotation",0f)[0], .001f)
        assertThrows(IllegalArgumentException::class.java) { linked.withValueLink(1,x,ValueLink(3,rotation)) }
        assertThrows(IllegalArgumentException::class.java) { linked.withValueLink(1,x,ValueLink(1,x,delayMs=100)) }
    }

    @Test fun separateAxesCanLinkAndDisconnectWithoutLosingValues() {
        val linked = scene().withValueLink(2,x,ValueLink(1,x)).withValueLink(2,y,ValueLink(1,x,3f))
        val detached = linked.withoutValueLink(2,x).update(1) { it.withTransform("location",150f,200f,50f) }.refreshMotionTools()
        assertEquals(100f,detached.layer(2)!!.location[0],.001f)
        assertEquals(450f,detached.layer(2)!!.location[1],.001f)
        val missing = linked.remove(1).refreshMotionTools()
        assertNull(missing.layer(2)!!.valueLink(x))
        assertEquals(100f,missing.layer(2)!!.location[0],.001f)
    }

    @Test fun everyCopyHasIndependentOffsetsAndInheritsAnimationEdits() {
        val source = scene().update(1) { it.withElement(PropRef.Location, XmlNode("location").withKeyframes(listOf(Keyframe(0f,"100,200,50"),Keyframe(1f,"400,200,50")))) }
        val repeated = source.withRepeatedCopies(1,3)
        val copies = repeated.repeatedCopies(1)
        val configured = repeated.withCopyOffset(copies[0].id,CopyOffset(20f,-40f,300f,15f,2f,.5f,.7f,6f))
        val copy = configured.layer(copies[0].id)!!
        assertEquals(200,copy.startTime)
        assertEquals(120f,copy.location[0],.001f)
        assertEquals(160f,copy.location[1],.001f)
        assertEquals(350f,copy.location[2],.001f)
        assertEquals(-5f,copy.transform("rotation",0f)[0],.001f)
        assertArrayEquals(floatArrayOf(2f,1f),copy.scale,.001f)
        assertEquals(.7f,copy.opacity,.001f)
        val updated=configured.update(1) { it.withFill("color",0xFFFF0000.toInt()) }.refreshMotionTools()
        assertEquals(0xFFFF0000.toInt(),updated.layer(copy.id)!!.fillColor)
        assertEquals(300f,updated.layer(copy.id)!!.copyOffset!!.z,.001f)
        assertEquals(300f,updated.layer(copies[1].id)!!.location[0],.001f)
        assertEquals(copies.map { it.id },updated.repeatedCopies(1).map { it.id })
    }

    @Test fun copiedGroupsKeepNestedIdsStableAndDetachWhenOriginalIsRemoved() {
        val group=Layer(XmlNode.parse("""<embedScene id="10" startTime="0" endTime="2999"><scene width="500" height="400"><bone id="11" length="100"/><shape id="12" parent="11" startTime="0" endTime="2999"/></scene></embedScene>"""))
        val repeated=scene().add(group).withRepeatedCopies(10,3)
        val first=repeated.repeatedCopies(10).first()
        val ids=(first.nested!!.layers+first.nested!!.bones).map { it.id }
        val refreshed=Scene.parse(repeated.toXml()).refreshMotionTools()
        assertEquals(ids,(refreshed.layer(first.id)!!.nested!!.layers+refreshed.layer(first.id)!!.nested!!.bones).map { it.id })
        assertEquals(refreshed.layer(first.id)!!.nested!!.bones.single().id.toString(),refreshed.layer(first.id)!!.nested!!.layers.single().node["parent"])
        val removed=refreshed.remove(10).refreshMotionTools()
        assertNull(removed.layer(first.id)!!.copySource)
        assertEquals(ids,(removed.layer(first.id)!!.nested!!.layers+removed.layer(first.id)!!.nested!!.bones).map { it.id })
    }

    @Test fun duplicatingLinkedLayersRemapsSourcesAndCopiesFollowLinkedOriginals() {
        val linked=scene().withValueLink(2,x,ValueLink(1,x,2f)).withRepeatedCopies(2,2)
        assertEquals(300f,linked.repeatedCopies(2).single().location[0],.001f)
        val duplicated=copyLayers(linked.layers)
        assertEquals(duplicated[0].id,duplicated[1].valueLink(x)!!.source)
        assertEquals(duplicated[1].id,duplicated.first { it.copySource != null }.copySource)
        val updated=linked.update(1) { it.withTransform("location",200f,200f,50f) }.refreshMotionTools()
        assertEquals(500f,updated.repeatedCopies(2).single().location[0],.001f)
    }

    @Test fun effectLinksFollowTheirEffectWhenReorderedAndFreezeWhenRemoved() {
        val source=scene().update(1) { l -> l.copy(node=l.node.copy(children=l.node.children+listOf(
            XmlNode("effect",mapOf("id" to "one"),listOf(XmlNode("property",mapOf("name" to "amount","type" to "float","value" to "25")))),
            XmlNode("effect",mapOf("id" to "two"),listOf(XmlNode("property",mapOf("name" to "amount","type" to "float","value" to "90"))))
        ))) }
        val linked=source.withValueLink(2,x,ValueLink(1,ValueChannel(PropRef.effect(0,"amount"))))
        val reordered=linked.update(1) { l -> l.copy(node=l.node.copy(children=l.node.children.filterNot { it.name=="effect" }+l.effects.reversed())) }.refreshMotionTools()
        assertEquals(25f,reordered.layer(2)!!.location[0],.001f)
        assertEquals(1,reordered.resolvedValueLink(2,x)!!.channel.ref.effectIndex)
        val removed=reordered.update(1) { l -> l.copy(node=l.node.copy(children=l.node.children.filterNot { it.name=="effect" && it["id"]=="one" })) }.refreshMotionTools()
        assertEquals(25f,removed.layer(2)!!.location[0],.001f)
        assertNull(removed.layer(2)!!.valueLink(x))
    }

    @Test fun groupEditsUpdateRepeatedGroupContentsFromTheDocumentRoot() {
        val original=scene().withRepeatedCopies(1,2)
        val group=Layer(XmlNode("embedScene",mapOf("id" to "10","startTime" to "0","endTime" to "2999"),listOf(original.node)))
        val root=scene().add(group).withRepeatedCopies(10,2)
        val changed=root.update(10) { l -> l.copy(node=l.node.withChild("scene",l.nested!!.update(1) { it.withFill("color",0xFF00FF00.toInt()) }.node)) }.refreshMotionTree()
        assertEquals(0xFF00FF00.toInt(),changed.repeatedCopies(10).single().nested!!.layers.first().fillColor)
        assertEquals(0xFF00FF00.toInt(),changed.layer(10)!!.nested!!.repeatedCopies(1).single().fillColor)
    }

    @Test fun viewportMovesChangeCopyOffsetsAndOriginalMovesPropagateOnce() {
        val original=scene().withRepeatedCopies(1,2)
        val id=original.repeatedCopies(1).single().id
        val moved=original.update(id) { it.withTransform("location",250f,240f,80f).withRotation(30f) }
            .captureCopyTransformEdits(original,0).refreshMotionTree()
        assertArrayEquals(floatArrayOf(250f,240f,80f),moved.layer(id)!!.location,.001f)
        assertEquals(30f,moved.layer(id)!!.rotation,.001f)
        assertEquals(150f,moved.layer(id)!!.copyOffset!!.x,.001f)
        val both=original.update(1) { it.withTransform("location",150f,200f,50f) }.update(id) { it.withTransform("location",250f,200f,50f) }
            .captureCopyTransformEdits(original,0).refreshMotionTree()
        assertEquals(250f,both.layer(id)!!.location[0],.001f)
    }
}

package app.absentmotion.export

import app.absentmotion.editor.*
import app.absentmotion.library.*
import app.absentmotion.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class ViewportRigPackageTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun editableBonesRoundTripThroughProjectPackagesAndResourceCopies()=runBlocking {
        val original=Scene.parse("""<scene width="400" height="300" fps="30"><shape id="3" s=".rect" startTime="0" endTime="999"><transform><location value="200,150,0"/></transform></shape></scene>""")
            .withDrawnBone(3,null,Pt(100f,150f),Pt(200f,150f),1,0)
        val bytes=ByteArrayOutputStream()
        writeProjectPackage(original,original.toXml(),bytes,MediaAccess.Unlimited,{null},{})
        val projects=ProjectStore(temp.newFolder())
        val report=Importer(projects).import {bytes.toByteArray().inputStream()}.single()
        val imported=projects.load(report.id)!!
        assertEquals(1,imported.layers.size)
        assertEquals(1,imported.bones.size)
        assertTrue(imported.layer(imported.layers.single().node["parent"]!!.toLong())!!.isBone)
        val copy=imported.withCopiedLayerIds()
        assertNotEquals(imported.bones.single().id,copy.bones.single().id)
        assertEquals(copy.bones.single().id.toString(),copy.layers.single().node["parent"])
        assertEquals(200f,copy.place(copy.layers.single(),0).x,.001f)
    }

    @Test fun groupingPartOfARigKeepsOutsidePartsAndTheirOriginalBones() {
        val scene=Scene.parse("""<scene width="400" height="300" fps="30"><shape id="3" startTime="0" endTime="999"/><shape id="4" startTime="0" endTime="999"/></scene>""")
            .withDrawnBone(3,null,Pt(10f,10f),Pt(110f,10f),1,0)
        val bound=scene.update(4) {it.copy(node=it.node.with("parent","1"))}
        val grouped=bound.grouped(setOf(3),null,9,"Part")
        assertEquals(1,grouped.bones.size)
        assertEquals("1",grouped.layer(4)!!.node["parent"])
        assertNotEquals(1L,grouped.layer(9)!!.nested!!.bones.single().id)
        assertTrue(grouped.layer(9)!!.nested!!.layer(grouped.layer(9)!!.nested!!.layers.single().node["parent"]!!.toLong())!!.isBone)
    }
}

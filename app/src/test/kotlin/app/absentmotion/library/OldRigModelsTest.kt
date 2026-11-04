package app.absentmotion.library

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class OldRigModelsTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun oldShelfMigratesOnceIntoMyResourcesWithOwnedMediaAndEditableBones() {
        val root=temp.newFolder()
        val old=File(root,"rig-models")
        val model=File(old,"t1").apply {mkdirs()}
        val photo=File(model,"media/photo.png").apply {parentFile!!.mkdirs();writeText("pixels")}
        File(model,"scene.xml").writeText("""<scene title="Actor" width="400" height="300" fps="30"><media uri="${photo.toURI()}" type="image/png"/><nullobj id="1" trueBone="true" trueBoneLength="100"/><shape id="2" parent="1" fillImage="${photo.toURI()}"/></scene>""")
        val projects=ProjectStore(root)
        fun runMigration()=migrateOldRigModels(old,projects) {File(URI(it)).inputStream()}
        runMigration()
        val saved=projects.list().single()
        val asset=projects.load(saved.id)!!
        assertTrue(asset.isElement)
        assertEquals(1,asset.bones.size)
        assertEquals("1",asset.layers.single().node["parent"])
        val uri=asset.node.mediaUris().single()
        assertTrue(uri.startsWith(projects.mediaDir(saved.id).toURI().toString()))
        assertEquals("pixels",File(URI(uri)).readText())
        runMigration()
        assertEquals(1,projects.list().size)
        assertTrue(photo.exists())
    }
}

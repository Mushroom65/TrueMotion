package app.absentmotion.library

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class RigModelMediaTest {
    @get:Rule val temp = TemporaryFolder()
    private fun open(uri: String) = File(URI(uri)).inputStream()

    @Test fun savedAndInsertedModelOwnSeparateCopiesOfAllFutureSwitchMedia() {
        val root = temp.newFolder()
        val original = File(root, "media/source/a.png").apply { parentFile.mkdirs(); writeText("pixels-a") }
        val other = File(root, "media/source/b.png").apply { writeText("pixels-b") }
        fun media(f: File) = MediaDeclaration(XmlNode("media", mapOf("uri" to f.toURI().toString(), "filename" to f.name, "type" to "image/png")))
        val scene = Scene.create("Actor", 100, 100, 30, 0).withMedia(media(original)).add(Layer(XmlNode("shape", mapOf("id" to "1", "startTime" to "0", "endTime" to "999", "fillType" to "media", "fillImage" to original.toURI().toString()))))
            .withMediaSwitch(1, 500, media(other))
        val store = TemplateStore(File(root, "rig-models"), ::open, requireLocalMedia = true)
        val saved = store.localize(scene, File(root, "rig-models/r1/media"))
        val inserted = store.localize(saved, File(root, "media/destination"))
        original.parentFile.deleteRecursively()
        File(root, "rig-models").deleteRecursively()
        assertEquals(setOf("pixels-a", "pixels-b"), inserted.node.storedMediaUris().map { File(URI(it)).readText() }.toSet())
        assertTrue(inserted.layer(1)!!.mediaSwitchKeys.all { it.v in inserted.node.storedMediaUris() })
        assertTrue(inserted.node.storedMediaUris().all { MediaAccess.forPhone(root).allows(it) })
    }

    @Test fun missingMediaFailsModelSaveInsteadOfCreatingBrokenReusableModel() {
        val store = TemplateStore(temp.newFolder(), { null }, requireLocalMedia = true)
        val scene = Scene.parse("""<scene><shape id="1" fillImage="content://missing/photo"/></scene>""")
        assertThrows(java.io.IOException::class.java) { store.localize(scene, temp.newFolder()) }
    }

    @Test fun appAllowsModelMediaOnlyUnderItsPrivateMediaDirectory() {
        val root = temp.newFolder()
        val access = MediaAccess.forPhone(root)
        assertTrue(access.allows(File(root, "rig-models/r1/media/photo.png").toURI().toString()))
        assertFalse(access.allows(File(root, "rig-models/r1/scene.xml").toURI().toString()))
        assertFalse(access.allows(File(root, "rig-models/private.txt").toURI().toString()))
    }
}
